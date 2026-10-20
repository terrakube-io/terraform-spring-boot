package io.terrakube.terraform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(OS.WINDOWS)
class ProcessLauncherSecurityTest {

    private static final ExecutorService POOL = Executors.newWorkStealingPool();
    private static final long FUTURE_TIMEOUT_SECONDS = 30;

    @AfterAll
    static void shutdownPool() {
        POOL.shutdown();
    }

    @AfterEach
    void cleanup() {
        PidIsolationSupport.resetForTests();
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
    }

    @Test
    void clearEnvironmentRemovesHostVariables() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "sh", "-c", "env");
        launcher.clearEnvironment();
        launcher.setEnvironmentVariable("SAFE_VAR", "safe_value");

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, exitCode);

        String combinedOutput = String.join("\n", output);
        assertTrue(combinedOutput.contains("SAFE_VAR=safe_value"), "SAFE_VAR should be present in process environment");
        assertFalse(combinedOutput.contains("PATH="), "PATH should be absent after clearEnvironment without explicit re-add");
    }

    @Test
    void childCannotReadParentEnvironViaProcfs() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        long jvmPid = ProcessHandle.current().pid();
        ProcessLauncher launcher = new ProcessLauncher(
                POOL,
                "sh", "-c",
                "leak=$(cat /proc/[0-9]*/environ 2>/dev/null | tr '\\0' '\\n' | grep InternalSecret || true); " +
                "if [ -n \"$leak\" ] || [ -d \"/proc/" + jvmPid + "\" ]; then " +
                "  echo \"LEAK_FOUND: host pid visible or secret leaked: $leak\"; " +
                "else " +
                "  echo \"PROCFS_ISOLATION_PASSED: no host secrets or parent jvm visible\"; " +
                "fi"
        );
        launcher.clearEnvironment();
        launcher.setPidIsolation(PidIsolation.REQUIRED);

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, exitCode);

        String combinedOutput = String.join("\n", output);
        assertTrue(launcher.isPidIsolationActive(), "PID isolation should be active");
        assertTrue(combinedOutput.contains("PROCFS_ISOLATION_PASSED"), "Parent process environ must not be accessible via procfs");
        assertFalse(combinedOutput.contains("LEAK_FOUND:"), "Host secret must not be leaked via procfs");
    }

    @Test
    void exitCodePropagatesUnderIsolation() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "exit 3");
        launcher.setPidIsolation(PidIsolation.REQUIRED);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(3, exitCode, "Exit code 3 should propagate cleanly through PID isolation shim");
        assertTrue(launcher.isPidIsolationActive());
    }

    @Test
    void signalDeathIsNeverReportedAsSuccess() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "kill -TERM $$");
        launcher.setPidIsolation(PidIsolation.REQUIRED);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(143, exitCode, "Signal death by SIGTERM must report 143, NEVER 0");
        assertTrue(launcher.isPidIsolationActive());
    }

    @Test
    void terminateReachesHandlerInsideNamespace() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        ProcessLauncher launcher = new ProcessLauncher(POOL, "python3", "-c",
                "import signal, sys, time\n" +
                "signal.signal(signal.SIGTERM, lambda s, f: (print('child got TERM', flush=True), sys.exit(143)))\n" +
                "print('started', flush=True)\n" +
                "time.sleep(30)");
        launcher.setPidIsolation(PidIsolation.REQUIRED);

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);
        launcher.setErrorListener(line -> {});

        CompletableFuture<Integer> future = launcher.launch();

        // Wait briefly until python script starts and prints 'started'
        long startWait = System.nanoTime();
        while (output.isEmpty() && TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startWait) < 5) {
            Thread.sleep(50);
        }

        long startedAt = System.nanoTime();
        assertTrue(launcher.terminate(ProcessLauncher.GRACE_PERIOD_SECONDS), "terminate() should report process dead");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(launcher.hasExited(), "hasExited() should be true");
        assertEquals(143, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "Process should exit with 143 from its SIGTERM handler");
        assertTrue(elapsedMillis < 5_000,
                "Signalled process should shut down quickly, took " + elapsedMillis + "ms");
        assertTrue(output.contains("child got TERM"), "Output should record that SIGTERM handler ran inside the namespace");
    }

    @Test
    void terminateWorksForChildWithoutHandler() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "exec sleep 30");
        launcher.setPidIsolation(PidIsolation.REQUIRED);
        launcher.setOutputListener(line -> {});
        launcher.setErrorListener(line -> {});

        CompletableFuture<Integer> future = launcher.launch();

        long startedAt = System.nanoTime();
        assertTrue(launcher.terminate(ProcessLauncher.GRACE_PERIOD_SECONDS), "terminate() should report process dead");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(launcher.hasExited());
        assertEquals(143, future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue(),
                "Child without handler forwarded SIGTERM should exit with 143 via init shim");
        assertTrue(elapsedMillis < 5_000, "Should terminate well within grace period, took " + elapsedMillis + "ms");
    }

    @Test
    void timeoutUnderIsolationLeavesNoNamespaceProcesses() throws Exception {
        Assumptions.assumeTrue(PidIsolationSupport.isAvailable(),
                "PID isolation not active in this environment - unshare unavailable or insufficient privileges");

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "exec sleep 30");
        launcher.setTimeoutSeconds(1);
        launcher.setPidIsolation(PidIsolation.REQUIRED);
        launcher.setOutputListener(line -> {});
        launcher.setErrorListener(line -> {});

        CompletableFuture<Integer> future = launcher.launch();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(launcher.hasExited());
    }

    @Test
    void requiredModeFailsClosedWhenUnavailable() {
        PidIsolationSupport.overrideForTests(Collections.emptyList());

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "echo should-not-run");
        launcher.setPidIsolation(PidIsolation.REQUIRED);

        assertThrows(PidIsolationUnavailableException.class, launcher::launch);
        assertTrue(launcher.hasExited(), "No process should have been started when fail-closed triggered");
    }

    @Test
    void bestEffortModeRunsUnisolatedWhenUnavailable() throws Exception {
        PidIsolationSupport.overrideForTests(Collections.emptyList());

        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "echo fallback");
        launcher.setPidIsolation(PidIsolation.BEST_EFFORT);

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, exitCode);
        assertFalse(launcher.isPidIsolationActive(), "Should not report isolation active when fallback ran");
        assertTrue(output.contains("fallback"));
    }

    @Test
    void offModeNeverWraps() throws Exception {
        ProcessLauncher launcher = new ProcessLauncher(POOL, "bash", "-c", "echo unisolated");
        launcher.setPidIsolation(PidIsolation.OFF);

        List<String> output = new ArrayList<>();
        launcher.setOutputListener(output::add);

        int exitCode = launcher.launch().get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(0, exitCode);
        assertFalse(launcher.isPidIsolationActive());
        assertTrue(output.contains("unisolated"));
    }
}
