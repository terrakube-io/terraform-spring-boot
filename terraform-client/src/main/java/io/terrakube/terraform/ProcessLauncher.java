package io.terrakube.terraform;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;

@Slf4j
public final class ProcessLauncher {
    private static final long STREAM_DRAIN_TIMEOUT_SECONDS = 60;
    private static final long FORCIBLE_EXIT_TIMEOUT_SECONDS = 5;
    // Budget for draining the readers on the timeout path, shared across both
    // streams. The process is dead by then, so what is left is the pipe buffer
    // (~64 KB) being handed to the listeners: the overrun this exists to absorb
    // measured ~1200 lines. Not STREAM_DRAIN_TIMEOUT_SECONDS, which is a 60s
    // safety net for a wedged reader on the success path and far too long to sit
    // inside a termination.
    private static final long TERMINATED_STREAM_DRAIN_TIMEOUT_SECONDS = 10;
    // Slack on the invokeAll bound below, so the deadline is not exactly the
    // worst case of the task it bounds. The payoff is small - the orphan verdict
    // is a live isAlive() check, so a boundary cancellation only mis-reports in
    // the moment between SIGKILL and reaping - but the arithmetic is clearer
    // with it than without.
    private static final long TERMINATION_SLACK_SECONDS = 1;
    static final long GRACE_PERIOD_SECONDS = 10;

    private static final AtomicInteger TERMINATOR_THREAD_COUNT = new AtomicInteger();
    // Termination cannot run on the launcher's own executor: two reader tasks and
    // the waitFor() task are blocked there for as long as the process lives, so
    // enforcing a timeout on that pool needs a fourth free worker per running
    // command and silently does nothing below that (measured: a 1s timeout left
    // the process alive for 45s at ForkJoinPool parallelism 1 and 2). Static and
    // never shut down: close() must be able to terminate, and a cached pool holds
    // no threads at idle.
    private static final ExecutorService TERMINATOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "terraform-terminate-" + TERMINATOR_THREAD_COUNT.incrementAndGet());
        thread.setDaemon(true);
        // A new thread inherits the context classloader of whoever happened to
        // submit first - for the timeout path that is the JDK's shared delayer
        // thread. In a static pool that is never shut down, that pins an
        // application classloader.
        thread.setContextClassLoader(ProcessLauncher.class.getClassLoader());
        return thread;
    });

    private volatile Process process;
    private ProcessBuilder builder;
    private Consumer<String> outputListener, errorListener;
    private Consumer<ProcessLauncher> startedListener;
    private boolean inheritIO;
    private ExecutorService executor;
    private long timeoutSeconds;

    ProcessLauncher(ExecutorService executor, String... commands) {
        this(executor, null, commands);
    }

    /**
     * @param startedListener notified from inside {@link #launch()} as soon as
     *                        the process exists, so that an owner tracking live
     *                        launchers never sees one before it has a process
     *                        and never misses one that already has.
     */
    ProcessLauncher(ExecutorService executor, Consumer<ProcessLauncher> startedListener, String... commands) {
        assert executor != null;
        this.executor = executor;
        this.startedListener = startedListener;
        this.process = null;
        this.builder = new ProcessBuilder(commands);
        this.timeoutSeconds = 0;
    }

	void setOutputListener(Consumer<String> listener) {
        assert this.process == null;
		this.outputListener = listener;
    }

	void setErrorListener(Consumer<String> listener) {
        assert this.process == null;
		this.errorListener = listener;
    }
    
    void setTimeoutSeconds(long timeoutSeconds) {
        assert this.process == null;
        this.timeoutSeconds = timeoutSeconds;
    }

	void setInheritIO(boolean inheritIO) {
        assert this.process == null;
		this.inheritIO = inheritIO;
    }
    
    void setDirectory(File directory) {
        assert this.process == null;
        this.builder.directory(directory);
    }
    
    void setRedirectErrorStream(boolean redirectErrorStream) {
        assert this.process == null;
        this.builder.redirectErrorStream(redirectErrorStream);
    }

    void appendCommands(String... commands) {
        Stream<String> filteredCommands = Arrays.stream(commands).filter(c -> c != null && c.length() > 0);
        this.builder.command().addAll(filteredCommands.collect(Collectors.toList()));
    }

    void setEnvironmentVariable(String name, String value) {
        assert name != null && name.length() > 0;
        Map<String, String> env = this.builder.environment();
        value = (value != null ? env.put(name, value) : env.remove(name));
    }

    void setOrAppendEnvironmentVariable(String name, String value, String delimiter) {
        assert name != null && name.length() > 0;
        if (value != null && value.length() > 0) {
            String current = System.getenv(name);
            String target = (current == null || current.length() == 0 ? value : String.join(delimiter, current, value));
            this.setEnvironmentVariable(name, target);
        }
    }

    /**
     * Starts the process and returns a future that completes with its exit
     * code once the output listeners have received the complete output.
     *
     * <p>If a timeout was set with {@link #setTimeoutSeconds(long)}, the process
     * and its descendants are terminated when it expires and the returned future
     * completes exceptionally instead. It completes once that termination has
     * finished, which is up to 15 seconds after the deadline (a 10 second grace
     * period plus a 5 second forcible-exit wait), not at the deadline itself.
     * The failure is a {@link TimeoutException}; following
     * the {@link CompletableFuture} convention it reaches callers wrapped in a
     * {@link CompletionException} from {@code join()} or in an
     * {@link ExecutionException} from {@code get()}.
     */
    CompletableFuture<Integer> launch() {
        assert this.process == null;
        if (this.inheritIO) {
            this.builder.inheritIO();
        }
        try {
            this.process = this.builder.start();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        if (this.startedListener != null) {
            try {
                this.startedListener.accept(this);
            } catch (RuntimeException | Error ex) {
                // The process is already running but nothing is tracking it, so
                // it would outlive the JVM unreachable.
                this.process.destroyForcibly();
                throw ex;
            }
        }
        Future<Boolean> outputDrained = null;
        Future<Boolean> errorDrained = null;
        if (!this.inheritIO) {
            if (this.outputListener != null) {
                outputDrained = this.executor.submit(() -> this.readProcessStream(this.process.getInputStream(), this.outputListener));
            }
            if (this.errorListener != null) {
                errorDrained = this.executor.submit(() -> this.readProcessStream(this.process.getErrorStream(), this.errorListener));
            }
        }
        final Future<Boolean> outputDone = outputDrained;
        final Future<Boolean> errorDone = errorDrained;
        CompletableFuture<Integer> result = CompletableFuture.supplyAsync(() -> {
            try {
                int exitCode = this.process.waitFor();
                // Wait for the reader tasks to finish draining the process
                // output before completing, so callers never observe the exit
                // code while the listeners have received only part (or none)
                // of the output. The readers were submitted before this task
                // and read until EOF, so after process exit they only have
                // the remaining pipe buffer left to deliver; the timeout is a
                // safety net that turns a wedged reader into a visible
                // failure instead of silently missing output.
                awaitStreamDrained(outputDone, "output");
                awaitStreamDrained(errorDone, "error");
                return exitCode;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }, this.executor);
        if (this.timeoutSeconds <= 0) {
            return result;
        }
        // orTimeout() completes the future on the JDK's shared delayer thread, so
        // the blocking termination is handed off to TERMINATOR.
        return result
                .orTimeout(this.timeoutSeconds, TimeUnit.SECONDS)
                .whenCompleteAsync((code, ex) -> {
                    if (ex instanceof TimeoutException) {
                        if (!this.terminate(GRACE_PERIOD_SECONDS)) {
                            log.warn("Timed out process {} could not be terminated and may be orphaned", this.builder.command());
                        }
                        // The supplier above joins the readers before completing,
                        // so the success path never hands a caller an exit code
                        // while its listeners are still receiving output. This
                        // path completes the future instead of running that
                        // supplier to the end, so it has to join them here or it
                        // reintroduces terrakube-io/terrakube#3294 - a consumer
                        // reading its output buffer from the failure handler
                        // would see it truncated, or being mutated as it reads.
                        long drainBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(TERMINATED_STREAM_DRAIN_TIMEOUT_SECONDS);
                        this.awaitTerminatedStreamDrained(outputDone, "output", drainBy);
                        this.awaitTerminatedStreamDrained(errorDone, "error", drainBy);
                    }
                    // terminate() restores the interrupt flag for its caller's
                    // benefit; clear it again so a pooled thread does not carry
                    // it into an unrelated task.
                    Thread.interrupted();
                }, TERMINATOR);
    }

    /**
     * Terminates the process, first politely and then forcibly, together with
     * any descendants that outlive it.
     *
     * <p>Declares no checked exception on purpose: it is called from a
     * {@link java.util.function.BiConsumer} lambda, which cannot throw one. An
     * interruption while waiting is restored on the thread and otherwise
     * ignored, which means callers that terminate more than one launcher must
     * clear it between calls - a set flag makes the next {@code waitFor} throw
     * instead of returning false, skipping the forcible kill.
     * {@link #terminateAll} is the only such caller and does exactly that.
     *
     * @param gracePeriodSeconds how long the process may take to shut down on
     *                           its own before it is killed
     * @return true if the process is no longer alive when this returns
     */
    boolean terminate(long gracePeriodSeconds) {
        if (this.process == null) {
            return true;
        }
        ProcessHandle handle = this.process.toHandle();
        // Snapshot the descendants before signalling: once the parent dies its
        // children are reparented and disappear from this view. Best effort, the
        // list may legitimately be empty.
        Set<ProcessHandle> descendants = new LinkedHashSet<>(handle.descendants().collect(Collectors.toList()));

        // ProcessHandle.destroy(), never Process.destroy(). ProcessImpl.destroy(boolean)
        // on unix closes the child's stdin/stdout/stderr unconditionally and
        // immediately after signalling. Terraform writes its interrupt notice to
        // stdout from inside its own SIGTERM handler, and Go terminates on an
        // unhandled SIGPIPE on fd 1/2, so Process.destroy() kills terraform in the
        // middle of its shutdown, before it releases its state lock or reaps its
        // provider plugins. Measured 3/3 killed at exit 141 with Process.destroy()
        // against 3/3 clean shutdowns with toHandle().destroy(). ProcessHandleImpl
        // holds only a pid and a start time, so it has no streams to close. Neither
        // javadoc documents any of this. Do not "simplify" this back to
        // process.destroy().
        handle.destroy();
        try {
            if (!this.process.waitFor(gracePeriodSeconds, TimeUnit.SECONDS)) {
                // The parent is usually still alive here, so it can still be asked
                // about children it forked during the shutdown it did not do.
                descendants.addAll(handle.descendants().collect(Collectors.toList()));
                handle.destroyForcibly();
                this.process.waitFor(FORCIBLE_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        return !this.process.isAlive();
    }

    /**
     * Terminates every launcher in parallel and returns the ones still alive.
     *
     * <p>Parallel, not sequential: every launcher gets the full grace period,
     * and the whole call costs one grace period rather than one per launcher,
     * which is what keeps a shutdown inside a pod's termination grace period.
     *
     * @return the launchers whose process is still alive, i.e. orphaned
     */
    static List<ProcessLauncher> terminateAll(Collection<ProcessLauncher> launchers, long gracePeriodSeconds) {
        List<Callable<Boolean>> terminations = launchers.stream()
                .map(launcher -> (Callable<Boolean>) () -> {
                    try {
                        return launcher.terminate(gracePeriodSeconds);
                    } finally {
                        // Clear the flag terminate() restores, so it does not
                        // reach a sibling termination or a later task on this
                        // thread. There is no matching clear on entry because
                        // ThreadPoolExecutor.runWorker() already clears the flag
                        // before each task while the pool is not stopping, and
                        // TERMINATOR is never shut down - which is also why the
                        // timeout lambda in launch() needs no entry clear.
                        Thread.interrupted();
                    }
                })
                .collect(Collectors.toList());
        try {
            TERMINATOR.invokeAll(terminations,
                    gracePeriodSeconds + FORCIBLE_EXIT_TIMEOUT_SECONDS + TERMINATION_SLACK_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return launchers.stream().filter(launcher -> !launcher.hasExited()).collect(Collectors.toList());
    }

    /**
     * @return true if this launcher has no process left to terminate, either
     *         because it was never launched or because the process has exited.
     */
    boolean hasExited() {
        return this.process == null || !this.process.isAlive();
    }

    /**
     * Waits for a reader to finish delivering output to its listener, on the
     * timeout path. Never throws: the caller is already being handed a
     * {@link TimeoutException} and a wedged reader must not replace it, only be
     * visible in the log.
     */
    private void awaitTerminatedStreamDrained(Future<Boolean> drained, String streamName, long deadlineNanos) {
        if (drained == null) {
            return;
        }
        try {
            drained.get(Math.max(deadlineNanos - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("The {} stream of timed out process {} did not finish draining; its output may be incomplete",
                    streamName, this.builder.command());
        }
    }

    private void awaitStreamDrained(Future<Boolean> drained, String streamName) throws InterruptedException {
        if (drained == null) {
            return;
        }
        try {
            if (!drained.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException(String.format("Failed to capture process %s stream", streamName));
            }
        } catch (ExecutionException | TimeoutException ex) {
            throw new RuntimeException(String.format("Failed to capture process %s stream", streamName), ex);
        }
    }

    private boolean readProcessStream(InputStream stream, Consumer<String> listener) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.accept(line);
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
