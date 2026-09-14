package io.terrakube.terraform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class ProcessLauncherTest {

    private static final ExecutorService POOL = Executors.newWorkStealingPool();
    private static final long FUTURE_TIMEOUT_SECONDS = 30;

    /**
     * Pids of processes these tests fork indirectly. A process whose parent has
     * been killed is reparented and no longer appears under
     * ProcessHandle.current(), so the sweep below cannot find it on its own.
     */
    private static final Set<Long> FORKED_PIDS = ConcurrentHashMap.newKeySet();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdown();
    }

    /**
     * A failing test leaves its subprocesses running, and some of these scripts
     * fork in a loop, so a red run on a developer's machine would otherwise
     * leave processes behind indefinitely.
     */
    @AfterEach
    void reapSubprocesses() {
        // Relies on this JVM being surefire's fork (the default forkCount=1), so
        // its descendants are only what these tests started. At forkCount=0 this
        // would reap Maven's own children instead.
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
        FORKED_PIDS.forEach(pid -> ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
        FORKED_PIDS.clear();
    }

    /**
     * Regression test: the exit-code future returned by launch() must not
     * complete before the output listeners have received the complete
     * process output. Previously the stream reader tasks and the waitFor()
     * future were submitted to the pool independently and never joined, so a
     * caller could observe a successful exit code while the listener had
     * received only part (or none) of the output. TerraformClient callers
     * such as showPlanJson() snapshot the collected output immediately after
     * the future completes, which intermittently produced blank plan JSON
     * (terrakube-io/terrakube#3294).
     *
     * The output (200k lines, ~1.4 MB) is intentionally much larger than the
     * OS pipe buffer so a non-joined reader is very likely to still be
     * draining when waitFor() returns.
     */
    @Test
    void outputIsFullyCapturedWhenExitCodeFutureCompletes() throws Exception {
        final int expectedLines = 200_000;
        for (int run = 0; run < 20; run++) {
            AtomicLong lines = new AtomicLong();
            ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "seq 1 " + expectedLines);
            launcher.setOutputListener(line -> lines.incrementAndGet());
            launcher.setErrorListener(line -> {
            });
            int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(0, exitCode, "process should exit cleanly on run " + run);
            assertEquals(expectedLines, lines.get(),
                    "listener should have received the complete output when the exit-code future completes (run " + run + ")");
        }
    }

    @Test
    void errorStreamIsFullyCapturedWhenExitCodeFutureCompletes() throws Exception {
        final int expectedLines = 50_000;
        AtomicLong lines = new AtomicLong();
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "seq 1 " + expectedLines + " 1>&2");
        launcher.setOutputListener(line -> {
        });
        launcher.setErrorListener(line -> lines.incrementAndGet());
        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, exitCode);
        assertEquals(expectedLines, lines.get(),
                "error listener should have received the complete stderr output when the exit-code future completes");
    }

    @Test
    void exitCodeIsStillReportedForFailingProcess() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "echo out; echo err 1>&2; exit 3");
        List<String> output = new ArrayList<>();
        List<String> error = new ArrayList<>();
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(error::add);
        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(3, exitCode);
        assertEquals(List.of("out"), output);
        assertEquals(List.of("err"), error);
    }

    /**
     * terminate() must kill a running process and let the launch future settle,
     * so that a consumer cancelling a job is not left with a terraform process
     * running for the rest of the JVM's life and a future that never completes.
     * <p>
     * The assertions deliberately pin the <em>graceful</em> path rather than
     * just "the process is gone eventually": the exit code must be 143
     * (SIGTERM), not 137 (SIGKILL), and terminate() must return well inside the
     * grace period. Without both, the test would still pass if terminate() sent
     * no signal at all, because the forcible escalation would clean the process
     * up once the grace period elapsed.
     */
    @Test
    void terminateSignalsProcessGracefullyAndCompletesLaunchFuture() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "exec sleep 30");
        launcher.setOutputListener(line -> {
        });
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        long startedAt = System.nanoTime();
        assertTrue(launcher.terminate(ProcessLauncher.GRACE_PERIOD_SECONDS), "terminate() should report the process dead");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(launcher.hasExited(), "hasExited() should be true after terminate()");
        assertEquals(143, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "the process should have died of SIGTERM (143), not of the forcible SIGKILL escalation (137)");
        assertTrue(elapsedMillis < 5_000,
                "a signalled process should be gone long before the grace period elapses, took " + elapsedMillis + "ms");
    }

    /**
     * When a timeout is configured and expires, the process must be killed and
     * the future the caller holds must fail with a TimeoutException rather than
     * hanging forever. CompletableFuture wraps the failure, so get() surfaces
     * it as the cause of an ExecutionException.
     */
    @Test
    void timeoutTerminatesProcessAndFailsFutureWithTimeoutException() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "exec sleep 30");
        launcher.setTimeoutSeconds(1);
        launcher.setOutputListener(line -> {
        });
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause(),
                "the timeout must surface as a TimeoutException, got " + failure.getCause());
        assertTrue(launcher.hasExited(), "the process must be dead once the timed-out future completes");
    }

    /**
     * Regression test for the finding that termination must not be queued onto
     * the launcher's own executor. Each running command keeps three tasks of
     * that pool blocked until the process dies - two stream readers and the
     * waitFor() supplier - so a pool with fewer than four free workers per
     * command has nothing left to run the termination on, and the timeout
     * silently never fires: the process keeps running and the caller's future
     * never completes. Measured at ForkJoinPool parallelism 1 and 2, a 1 second
     * timeout left the process alive for a 45 second observation window.
     * <p>
     * This test uses a parallelism-1 pool and must FAIL, by timing out in
     * get(), if the termination is handed back to that pool instead of to the
     * dedicated terminator threads.
     */
    @Test
    void timeoutFiresEvenWhenTheLauncherPoolHasNoSpareWorker() throws Exception {
        ForkJoinPool starvedPool = new ForkJoinPool(1);
        try {
            ProcessLauncher launcher = new ProcessLauncher(starvedPool, "bash", "-c", "exec sleep 30");
            launcher.setTimeoutSeconds(1);
            launcher.setOutputListener(line -> {
            });
            launcher.setErrorListener(line -> {
            });
            CompletableFuture<Integer> future = launcher.launch();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the future must settle even though every worker of the launcher's pool is blocked");
            assertInstanceOf(TimeoutException.class, failure.getCause());
            assertTrue(launcher.hasExited(), "the process must be dead even though the launcher's pool is saturated");
        } finally {
            starvedPool.shutdownNow();
        }
    }

    /**
     * Regression test for the invariant a previous contributor established and
     * this feature initially broke on its own new path: the future a caller
     * holds must never complete while the output listeners are still receiving
     * output.
     * <p>
     * The success path enforces this by joining the reader tasks inside the
     * supplier before returning the exit code (see
     * outputIsFullyCapturedWhenExitCodeFutureCompletes, and
     * terrakube-io/terrakube#3294 for the blank plan JSON it caused). The
     * timeout path completes the future from a different thread and skips that
     * supplier entirely, so it has to join the readers itself. Measured before
     * the fix: 398 lines delivered when the future completed and 1216 more
     * afterwards.
     * <p>
     * That matters more on the timeout path than on the success path, because
     * consumers read their output buffer from the failure handler - a plain
     * StringBuilder and ArrayList in Terrakube's case - so a still-running
     * reader means truncated output or a ConcurrentModificationException.
     * <p>
     * The listener is deliberately slow (2ms a line) so the reader is certainly
     * still delivering when the timeout fires, and the whole output fits in the
     * pipe buffer so the writer is finished and only the listener is behind.
     */
    @Test
    void timeoutDoesNotCompleteTheFutureWhileListenersAreStillReceivingOutput() throws Exception {
        final int expectedLines = 2_000;
        AtomicLong delivered = new AtomicLong();
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "seq 1 " + expectedLines + "; sleep 30");
        launcher.setTimeoutSeconds(1);
        launcher.setOutputListener(line -> {
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            delivered.incrementAndGet();
        });
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        long deliveredAtCompletion = delivered.get();

        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertEquals(expectedLines, deliveredAtCompletion,
                "the listener should have received the whole output before the timed-out future completed");
        Thread.sleep(1_000);
        assertEquals(deliveredAtCompletion, delivered.get(),
                "no output may reach the listener after the caller's future has completed");
    }

    /**
     * Regression test for backwards compatibility: the timeout is opt-in, so a
     * launcher left at the default of zero must never kill its process, however
     * long it runs. Consumers that do not set a timeout run terraform applies
     * lasting hours, and a non-zero default would start killing them on a patch
     * upgrade of this library. The process here outlives the forcible-exit wait
     * of 5 seconds, so a default accidentally set to that value fails this test.
     */
    @Test
    void zeroTimeoutNeverTerminatesLongRunningProcess() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "sleep 6; echo survived");
        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {
        });
        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(0, exitCode, "a process with no timeout should be left to finish on its own");
        assertEquals(List.of("survived"), output);
    }

    @Test
    void terminateOnNeverLaunchedProcessReturnsTrue() {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "sleep 30");

        assertTrue(launcher.terminate(5), "a launcher that never started a process has nothing to kill");
        assertTrue(launcher.hasExited());
    }

    /**
     * Regression test for the forcible escalation: a process that ignores
     * SIGTERM must still be killed once the grace period elapses, otherwise a
     * wedged terraform would outlive both the timeout and close() while holding
     * its state lock. Without the destroyForcibly() escalation, terminate()
     * here reports false and the process survives.
     */
    @Test
    void terminateKillsProcessThatIgnoresSigterm() throws Exception {
        // A busy loop rather than a backgrounded sleep the parent waits on: with
        // "sleep 30 & wait" the descendant sweep alone ends the parent, so the
        // assertions below could be satisfied without the forcible kill they exist
        // to guard.
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c",
                "trap '' TERM; echo ready; while true; do sleep 1; done");
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        // launch() returns within a millisecond of fork, well before bash has
        // run its trap builtin, so the signal has to wait for the handshake.
        await(() -> output.contains("ready"), "script should have reported readiness");

        assertTrue(launcher.terminate(1), "a SIGTERM-ignoring process must still be killed");
        assertTrue(launcher.hasExited());
        assertEquals(137, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "the process should have been SIGKILLed after ignoring SIGTERM");
    }

    /**
     * Regression test for the descendant sweep, which is what stops terraform's
     * provider plugins from being orphaned when terraform itself is killed.
     * <p>
     * The child here forks a grandchild that inherits stdout and outlives it,
     * which is the shape of a terraform process holding provider plugins: the
     * grandchild keeps the pipe's write end open, so the reader task never sees
     * EOF and the launch future never completes until the grandchild dies too.
     * <p>
     * The snapshot has to be taken before the parent is signalled: once the
     * parent dies the grandchild is reparented and no longer appears in
     * descendants(). Dropping the snapshot, or taking it after the signal, both
     * leave the grandchild running and fail this test.
     */
    @Test
    void terminateKillsDescendantThatOutlivesItsParent() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "sleep 30 & echo pid=$!; wait");
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        await(() -> output.stream().anyMatch(line -> line.startsWith("pid=")),
                "script should have reported the grandchild pid");
        long grandchildPid = Long.parseLong(output.stream()
                .filter(line -> line.startsWith("pid="))
                .findFirst().orElseThrow()
                .substring("pid=".length()).trim());
        FORKED_PIDS.add(grandchildPid);
        Optional<ProcessHandle> grandchild = ProcessHandle.of(grandchildPid);
        assertTrue(grandchild.isPresent() && grandchild.get().isAlive(),
                "grandchild should be running before terminate()");

        assertTrue(launcher.terminate(ProcessLauncher.GRACE_PERIOD_SECONDS));

        await(() -> !grandchild.get().isAlive(),
                "the grandchild must be killed with its parent, otherwise it is orphaned holding the pipe open");
        future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Regression test for the second descendant snapshot: terraform can start
     * another provider plugin while it is shutting down, so a process forked
     * during the grace period must be swept too. The parent here forks its
     * grandchild from inside its own SIGTERM handler and then refuses to exit,
     * so the grandchild exists in neither the pre-signal snapshot nor - once
     * the parent has been forcibly killed - in descendants(). It is only
     * reachable from the snapshot taken just before destroyForcibly(), while
     * the parent is still alive; dropping that snapshot leaves it orphaned and
     * fails this test.
     */
    @Test
    void terminateKillsDescendantForkedDuringTheGracePeriod() throws Exception {
        String script = "trap 'sleep 30 & echo late=$!' TERM; echo ready; while true; do sleep 1; done";
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", script);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        await(() -> output.contains("ready"), "script should have reported readiness");

        // Short grace period: the trap forks the grandchild within a second, so
        // the parent is still alive and still listing it when it expires.
        assertTrue(launcher.terminate(2), "the SIGTERM-refusing parent must still be killed");

        await(() -> output.stream().anyMatch(line -> line.startsWith("late=")),
                "the SIGTERM handler should have reported the pid it forked");
        long latePid = Long.parseLong(output.stream()
                .filter(line -> line.startsWith("late="))
                .findFirst().orElseThrow()
                .substring("late=".length()).trim());
        FORKED_PIDS.add(latePid);

        await(() -> ProcessHandle.of(latePid).map(handle -> !handle.isAlive()).orElse(true),
                "a descendant forked during the grace period must be swept, pid " + latePid + " is still alive");
        // The swept descendant held the pipe open, so this only completes once
        // the sweep has actually run.
        assertEquals(137, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "the SIGTERM-refusing parent should have been SIGKILLed");
    }

    /**
     * Regression test for the ProcessHandle-versus-Process distinction in
     * terminate(): a terminated process must be able to run its own SIGTERM
     * handler to completion and still have its output delivered to the
     * listener. ProcessImpl.destroy(boolean) on unix closes the child's
     * stdin/stdout/stderr immediately after signalling, so terraform - which
     * prints its interrupt notice from inside its SIGTERM handler and, being
     * Go, dies on an unhandled SIGPIPE on fd 1/2 - gets killed mid-shutdown,
     * before releasing its state lock or reaping its provider plugins.
     * ProcessHandle.destroy() only signals and leaves the streams alone.
     * <p>
     * This test exists to FAIL if anyone replaces handle.destroy() with
     * process.destroy() in terminate(). That is its entire purpose: the bash
     * script below traps SIGTERM, echoes from inside the trap and exits
     * cleanly, which is exactly what the stream-closing variant prevents from
     * ever reaching the listener.
     */
    @Test
    void gracefulTerminateLetsProcessFinishItsOwnCleanup() throws Exception {
        String script = "trap 'echo cleanup-done; exit 0' TERM; echo ready; sleep 30 & wait";
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", script);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {
        });
        CompletableFuture<Integer> future = launcher.launch();

        // Wait until the trap is installed, otherwise the signal can arrive
        // before the script has any handler for it.
        await(() -> output.contains("ready"), "script should have reported readiness");

        assertTrue(launcher.terminate(ProcessLauncher.GRACE_PERIOD_SECONDS),
                "the process should be gone after a graceful terminate");
        assertEquals(0, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "the trapping script should exit cleanly on SIGTERM");
        assertTrue(output.contains("cleanup-done"),
                "output written from the SIGTERM handler must reach the listener, output was " + output);
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
