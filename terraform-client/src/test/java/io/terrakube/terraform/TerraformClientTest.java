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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the client-side half of the job timeout: its default, its route into
 * the launcher, and the shutdown that has to terminate live launchers. Uses
 * plain bash subprocesses through the package-private launcher factory - going
 * through plan()/apply() would download a terraform release - and that factory
 * is the single funnel every command path goes through.
 * <p>
 * The multi-launcher test attaches no output listeners, and that is deliberate
 * rather than lazy. launch() only submits the two stream reader tasks when a
 * listener is set, and those readers block on the child's pipe for as long as it
 * lives, so each live launcher costs three tasks on the client's work-stealing
 * pool - whose parallelism is availableProcessors(). Three concurrent launchers
 * is nine tasks: that saturates a 4-core runner and the last launcher's reader
 * is never scheduled. It needs none of the child's output, so the readers are
 * simply never created, and its readiness is signalled through the filesystem
 * for the same reason.
 * <p>
 * The single-launcher tests do keep their listeners. Three tasks fit, and
 * dropping them there would lose the only coverage of close() running against
 * readers genuinely blocked in readLine() on a live pipe.
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

    @Test
    void securitySettingsDefaultToOffAndDisabled() {
        TerraformProcessData data = TerraformProcessData.builder()
                .terraformVersion("1.5.0")
                .workingDirectory(new File("."))
                .build();
        assertNull(data.getPidIsolation(), "ProcessData pidIsolation should be null by default to inherit client setting");
        assertNull(data.getCleanEnvironment(), "ProcessData cleanEnvironment should be null by default to inherit client setting");

        TerraformClient client = TerraformClient.builder().build();
        ProcessLauncher launcher = client.newLauncher(data, "echo", "test");
        assertFalse(launcher.isPidIsolationActive(), "PID isolation should be inactive by default");
    }

    @Test
    void perCallPidIsolationOverridesClientSetting() {
        TerraformClient client = TerraformClient.builder()
                .pidIsolation(PidIsolation.BEST_EFFORT)
                .build();

        TerraformProcessData overrideOff = TerraformProcessData.builder()
                .terraformVersion("1.5.0")
                .workingDirectory(new File("."))
                .pidIsolation(PidIsolation.OFF)
                .build();

        ProcessLauncher launcher = client.newLauncher(overrideOff, "echo", "test");
        assertFalse(launcher.isPidIsolationActive(), "Per-call OFF must override client-level BEST_EFFORT");
    }

    @Test
    void cleanEnvironmentDoesNotLeakAmbientHostVariables() throws Exception {
        try (TerraformClient client = TerraformClient.builder()
                .cleanEnvironment(true)
                .build()) {
            TerraformProcessData data = TerraformProcessData.builder()
                    .terraformVersion("1.5.0")
                    .workingDirectory(new File("."))
                    .terraformEnvironmentVariable("EXPLICIT_VAR", "explicit_val")
                    .build();

            ProcessLauncher launcher = client.newLauncher(data, "/bin/sh", "-c", "env");
            client.applyEnvironment(launcher, data);

            List<String> output = new ArrayList<>();
            launcher.setOutputListener(output::add);
            int exitCode = launcher.launch().get(30, TimeUnit.SECONDS);
            assertEquals(0, exitCode);

            String combined = String.join("\n", output);
            assertTrue(combined.contains("EXPLICIT_VAR=explicit_val"));
            assertTrue(combined.contains("PATH="), "PATH should be inherited from default allowlist");
        }
    }

    @Test
    void cleanEnvironmentCustomAllowlistAndPerCallOverrides() throws Exception {
        try (TerraformClient client = TerraformClient.builder()
                .cleanEnvironment(true)
                .inheritedEnvironmentVariables(Set.of("CUSTOM_CLIENT_VAR"))
                .build()) {

            // Test 1: Client-level custom allowlist excludes PATH
            TerraformProcessData dataWithCustomAllowlist = TerraformProcessData.builder()
                    .terraformVersion("1.5.0")
                    .workingDirectory(new File("."))
                    .terraformEnvironmentVariable("CALL_VAR", "call_val")
                    .build();

            ProcessLauncher launcher1 = client.newLauncher(dataWithCustomAllowlist, "/bin/sh", "-c", "env");
            client.applyEnvironment(launcher1, dataWithCustomAllowlist);

            List<String> output1 = new ArrayList<>();
            launcher1.setOutputListener(output1::add);
            int exitCode1 = launcher1.launch().get(30, TimeUnit.SECONDS);
            assertEquals(0, exitCode1);
            String combined1 = String.join("\n", output1);
            assertTrue(combined1.contains("CALL_VAR"));
            assertFalse(combined1.contains("PATH="), "PATH should not be inherited when excluded from custom allowlist");
            assertFalse(launcher1.getEnvironment().containsKey("PATH"));

            // Test 2: Per-call cleanEnvironment(false) overrides client-level cleanEnvironment(true)
            TerraformProcessData dataDisableClean = TerraformProcessData.builder()
                    .terraformVersion("1.5.0")
                    .workingDirectory(new File("."))
                    .cleanEnvironment(false)
                    .build();

            ProcessLauncher launcher2 = client.newLauncher(dataDisableClean, "/bin/sh", "-c", "env");
            client.applyEnvironment(launcher2, dataDisableClean);

            List<String> output2 = new ArrayList<>();
            launcher2.setOutputListener(output2::add);
            int exitCode2 = launcher2.launch().get(30, TimeUnit.SECONDS);
            assertEquals(0, exitCode2);
            String combined2 = String.join("\n", output2);
            assertTrue(combined2.contains("PATH="), "PATH must be present when cleanEnvironment is overridden to false");

            // Test 3: Per-call inheritedEnvironmentVariables overrides client allowlist
            TerraformProcessData dataOverrideAllowlist = TerraformProcessData.builder()
                    .terraformVersion("1.5.0")
                    .workingDirectory(new File("."))
                    .inheritedEnvironmentVariables(Set.of("PATH"))
                    .build();

            ProcessLauncher launcher3 = client.newLauncher(dataOverrideAllowlist, "/bin/sh", "-c", "env");
            client.applyEnvironment(launcher3, dataOverrideAllowlist);

            List<String> output3 = new ArrayList<>();
            launcher3.setOutputListener(output3::add);
            int exitCode3 = launcher3.launch().get(30, TimeUnit.SECONDS);
            assertEquals(0, exitCode3);
            String combined3 = String.join("\n", output3);
            assertTrue(combined3.contains("PATH="), "PATH must be present when allowlist is overridden on process data");
        }
    }

    @Test
    void toBuilderPreservesSecurityProperties() throws Exception {
        try (TerraformClient client = TerraformClient.builder()
                .pidIsolation(PidIsolation.BEST_EFFORT)
                .cleanEnvironment(true)
                .inheritedEnvironmentVariables(Set.of("PATH", "HOME"))
                .showColor(false)
                .build();
             TerraformClient derived = client.toBuilder()
                .jsonOutput(true)
                .build()) {
            assertEquals(PidIsolation.BEST_EFFORT, derived.getPidIsolation());
            assertTrue(derived.isCleanEnvironment());
            assertEquals(Set.of("PATH", "HOME"), derived.getInheritedEnvironmentVariables());
            assertTrue(derived.isJsonOutput());
            assertFalse(derived.isShowColor());
        }
    }

    @Test
    void initSetsGitSshCommandEnvironmentVariableWithoutBashWrapper() throws Exception {
        File fakeKey = new File("/home/user/.ssh/id_rsa");
        TerraformProcessData data = TerraformProcessData.builder()
                .terraformVersion("1.5.0")
                .workingDirectory(new File("."))
                .sshFile(fakeKey)
                .build();

        TerraformClient client = new TerraformClient() {
            @Override
            public TerraformDownloader createTerraformDownloader() {
                return new TerraformDownloader(null, null) {
                    @Override
                    public String downloadTerraformVersion(String version) {
                        return "/usr/bin/terraform";
                    }
                };
            }
        };

        ProcessLauncher launcher = client.getTerraformLauncher(data, line -> {}, line -> {}, TerraformCommand.init);
        assertNotNull(launcher);
        assertEquals(List.of("/usr/bin/terraform", "init", "-no-color", "-input=false"), launcher.getCommands());
        assertEquals("ssh -i \"/home/user/.ssh/id_rsa\" -o StrictHostKeyChecking=no",
                launcher.getEnvironment().get("GIT_SSH_COMMAND"));
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
