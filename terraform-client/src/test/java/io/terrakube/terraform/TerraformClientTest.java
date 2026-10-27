package io.terrakube.terraform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the client-side half of the job timeout: its default, its route into
 * the launcher, and the shutdown that has to terminate live launchers. Uses
 * plain bash subprocesses through the package-private launcher factory - going
 * through plan()/apply() would download a terraform release - and that factory
 * is the single funnel every command path goes through.
 * <p>
 * The multi-launcher test attaches no output listeners and signals child
 * readiness through the filesystem. That was originally forced on it: the
 * client's pool used to be sized to availableProcessors(), so three launchers -
 * nine blocking tasks - saturated a 4-core runner and the third child's
 * readiness line was never delivered. everyConcurrentCommandDeliversItsOutput
 * below is the regression test for that, and the pool now grows to the blocking
 * work, so the constraint is gone. The test keeps both choices anyway: it needs
 * none of the child's output, and a filesystem handshake does not depend on
 * reader scheduling at all, which is the right way round for a test whose
 * subject is close() rather than output capture.
 * <p>
 * The single-launcher tests do keep their listeners, deliberately: dropping them
 * would lose the only coverage of close() running against readers genuinely
 * blocked in readLine() on a live pipe.
 */
@DisabledOnOs(OS.WINDOWS)
class TerraformClientTest {

    @AfterEach
    void reapSubprocesses() {
        // Relies on this JVM being surefire's fork (the default forkCount=1), so
        // its descendants are only what these tests started. At forkCount=0 this
        // would reap Maven's own children instead.
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
    }

    /**
     * The timeout has to default to disabled. Consumers that never set it run
     * terraform applies lasting hours, so a non-zero default would start killing
     * them on a patch upgrade. Asserted on the builder directly because it is
     * the strongest possible guard: no sleep length in any other test can rule
     * out a default of, say, 10.
     */
    @Test
    void timeoutIsDisabledByDefault() {
        assertEquals(0, TerraformProcessData.builder()
                .terraformVersion("1.5.0")
                .workingDirectory(new File("."))
                .build()
                .getTimeoutSeconds());
    }

    private static TerraformProcessData processData(long timeoutSeconds) {
        return TerraformProcessData.builder()
                .terraformVersion("1.5.0")
                .workingDirectory(new File("."))
                .timeoutSeconds(timeoutSeconds)
                .build();
    }

    /**
     * Regression test for the "executor did not terminate" failure on shutdown.
     * close() shuts down an executor whose reader tasks are blocked in
     * readLine() on a live child's pipe, and that read does not respond to
     * Thread.interrupt(), so the child has to be killed first. This asserts
     * both halves: close() terminates the tracked launcher's process, and it
     * does not throw. It also bounds how long close() may take - terminating
     * launchers one after another instead of in parallel still passes the
     * liveness assertions, it just takes a grace period per launcher.
     */
    @Test
    void closeTerminatesLaunchersThatAreStillRunning() throws Exception {
        TerraformClient client = TerraformClient.builder().build();
        ProcessLauncher launcher = client.newLauncher(processData(0), "bash", "-c", "exec sleep 30");
        // Listeners on purpose, despite the note in the class comment: this is
        // the test that has to exercise close() against readers actually blocked
        // in readLine() on a live pipe, which is the whole reason close() kills
        // the children before shutting the pool down. One launcher is three pool
        // tasks, which fits, and the child writes nothing so an unscheduled
        // reader just waits on an empty pipe.
        launcher.setOutputListener(line -> {
        });
        launcher.setErrorListener(line -> {
        });
        launcher.launch();

        long startedAt = System.nanoTime();
        client.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(launcher.hasExited(), "close() should have terminated the still-running launcher");
        assertTrue(elapsedMillis < 5_000,
                "close() should not wait out a grace period for a process that dies on SIGTERM, took " + elapsedMillis + "ms");
    }

    /**
     * Two regressions in one test, both of which need more than one live
     * launcher and a process that refuses SIGTERM.
     * <p>
     * First: close() must terminate launchers in parallel. Terminating them one
     * after another costs a full grace period each, so a handful of live
     * commands overruns a pod's termination grace period and the container is
     * SIGKILLed with none of this having run. Three launchers must therefore
     * cost one grace period, not three.
     * <p>
     * Second: an already-interrupted closing thread - a job cancelled while its
     * finally block calls close() - must still kill everything. terminate()
     * restores the interrupt flag, so left alone it propagates from one
     * launcher to the next and makes the following waitFor() throw immediately
     * instead of returning false, which skips the forcible kill entirely.
     * Measured before the fix: close() returned in 2ms having killed 0 of 3.
     * The flag must also survive close(), since the caller's interruption is
     * not ours to swallow.
     */
    @Test
    void closeTerminatesEveryLauncherInParallelEvenWhenInterrupted(@TempDir Path readinessDir) throws Exception {
        TerraformClient client = TerraformClient.builder().build();
        List<ProcessLauncher> launchers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path marker = readinessDir.resolve("ready-" + i);
            // Readiness through the filesystem, not stdout: reading stdout needs a
            // worker on the very pool these launchers are filling up, so the third
            // child's line would never be delivered on a 4-core runner. touch runs
            // after the trap builtin, so the marker still proves the trap is
            // installed. A busy loop rather than a backgrounded sleep the parent
            // waits on, because the descendant sweep would otherwise end the parent
            // on its own and the hasExited() assertions could pass without the
            // forcible kill.
            ProcessLauncher launcher = client.newLauncher(processData(0), "bash", "-c",
                    "trap '' TERM; touch \"" + marker + "\"; while true; do sleep 1; done");
            launcher.launch();
            await(() -> Files.exists(marker), "launcher " + i + " should have installed its trap");
            launchers.add(launcher);
        }

        Thread.currentThread().interrupt();
        long startedAt = System.nanoTime();
        client.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        // Clear before asserting, so a failure below cannot leave the flag set
        // on a thread JUnit reuses for the next test.
        assertTrue(Thread.interrupted(), "close() must restore the caller's interrupt flag");
        for (int i = 0; i < launchers.size(); i++) {
            assertTrue(launchers.get(i).hasExited(),
                    "launcher " + i + " should have been killed despite ignoring SIGTERM on an interrupted close()");
        }
        // 25s, not 15s: this measured 12.1s under 20-way load against 10.1s idle,
        // and CI runners are slower and smaller. The sequential-loop regression it
        // guards costs a grace period per launcher, i.e. ~30s for three.
        assertTrue(elapsedMillis < 25_000,
                "launchers should cost one grace period between them, not one each, took " + elapsedMillis + "ms");
    }

    /**
     * Regression test for the client's executor being sized for CPU work while
     * every task it runs is blocking I/O.
     * <p>
     * Each running command holds three tasks of that pool blocked for as long as
     * the child lives: two stream readers and the waitFor() supplier. A
     * ForkJoinPool only grows workers for a ManagedBlocker, and neither
     * readLine() nor waitFor() is one, so a pool with parallelism
     * availableProcessors() can never run more than that many of them. N
     * concurrent commands need 3N threads, so starvation begins once
     * 3N - 1 &gt; P - measured on 4 cores, the second concurrent command already
     * has a reader that is never scheduled.
     * <p>
     * The consequence is not slow output, it is a stopped child: an unscheduled
     * reader stops draining the pipe, the pipe buffer fills, and the child
     * blocks in write(2) and makes no further progress. Measured against the
     * unfixed pool, a child froze after 8 loop iterations and had not advanced
     * 20 seconds later, with zero lines delivered. For a terraform apply that is
     * a run wedged mid-flight because something else is running on the same
     * client.
     * <p>
     * The test sizes itself to the machine rather than pinning the core count
     * with a JVM flag: it launches P/3 + 2 commands, which satisfies
     * 3N - 1 &gt; P for every P &gt;= 1, so it starves on any host without the
     * fix and passes on any host with it - while costing a third of what one
     * command per core would on a large machine. Each child deliberately writes
     * more than a pipe buffer to each stream - a quiet child goes dark without
     * ever wedging - and then stays alive, so that the readers of the commands
     * that did get scheduled stay blocked rather than releasing their workers.
     */
    @Test
    void everyConcurrentCommandDeliversItsOutput() throws Exception {
        // seq 1 20000 is 108,894 bytes, 1.66x the 64 KiB pipe buffer.
        final int lines = 20_000;
        // Starvation needs 3N - 1 > P, and P/3 + 2 satisfies it at every P
        // (P=1 -> N=2, P=4 -> 3, P=14 -> 6, P=64 -> 23) without putting one
        // process per core on a large machine.
        final int processors = Runtime.getRuntime().availableProcessors();
        final int commands = processors / 3 + 2;
        TerraformClient client = TerraformClient.builder().build();
        List<AtomicLong> stdout = new ArrayList<>();
        List<AtomicLong> stderr = new ArrayList<>();
        try {
            for (int i = 0; i < commands; i++) {
                AtomicLong out = new AtomicLong();
                AtomicLong err = new AtomicLong();
                stdout.add(out);
                stderr.add(err);
                ProcessLauncher launcher = client.newLauncher(processData(0), "bash", "-c",
                        "seq 1 " + lines + "; seq 1 " + lines + " 1>&2; exec sleep 300");
                launcher.setOutputListener(line -> out.incrementAndGet());
                launcher.setErrorListener(line -> err.incrementAndGet());
                launcher.launch();
            }

            await(() -> stdout.stream().allMatch(c -> c.get() == lines)
                            && stderr.stream().allMatch(c -> c.get() == lines),
                    () -> "every concurrent command must have its output read, but with "
                            + commands + " commands on " + processors + " processors the delivered line counts were stdout="
                            + stdout + " stderr=" + stderr + " of " + lines
                            + " each; a command showing 0 had no reader scheduled, and its child is blocked in write()");
        } finally {
            client.close();
        }
    }

    /**
     * The timeout is configured per job on TerraformProcessData, so it has to
     * reach the launcher the client builds for that job. Without the wiring the
     * launcher runs untimed and this future completes normally.
     */
    @Test
    void timeoutSecondsFromProcessDataIsAppliedToTheLauncher() throws Exception {
        try (TerraformClient client = TerraformClient.builder().build()) {
            ProcessLauncher launcher = client.newLauncher(processData(1), "bash", "-c", "exec sleep 30");
            launcher.setOutputListener(line -> {
            });
            launcher.setErrorListener(line -> {
            });
            CompletableFuture<Integer> future = launcher.launch();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(30, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause(),
                    "the per-job timeout should have expired, got " + failure.getCause());
            assertTrue(launcher.hasExited());
        }
    }

    private static void await(java.util.function.BooleanSupplier condition,
                              java.util.function.Supplier<String> message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /**
     * The message is a supplier because the counts it reports are mutating while
     * the condition is being polled; building it eagerly would report the values
     * from before the wait.
     */
    private static void await(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        await(condition, () -> message);
    }
}
