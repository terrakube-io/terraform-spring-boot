package io.terrakube.terraform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    void closeTerminatesEveryLauncherInParallelEvenWhenInterrupted() throws Exception {
        TerraformClient client = TerraformClient.builder().build();
        List<ProcessLauncher> launchers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<String> output = Collections.synchronizedList(new ArrayList<>());
            // A busy loop, not a backgrounded sleep the parent waits on: the
            // descendant sweep would otherwise end the parent on its own and the
            // hasExited() assertions could pass without the forcible kill.
            ProcessLauncher launcher = client.newLauncher(processData(0),
                    "bash", "-c", "trap '' TERM; echo ready; while true; do sleep 1; done");
            launcher.setOutputListener(output::add);
            launcher.setErrorListener(line -> {
            });
            launcher.launch();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!output.contains("ready") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(output.contains("ready"), "launcher " + i + " should have installed its trap");
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
}
