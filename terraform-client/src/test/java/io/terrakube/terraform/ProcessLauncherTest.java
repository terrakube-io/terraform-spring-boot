package io.terrakube.terraform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisabledOnOs(OS.WINDOWS)
class ProcessLauncherTest {

    private static final ExecutorService POOL = Executors.newWorkStealingPool();

    @AfterAll
    static void shutdownPool() {
        POOL.shutdown();
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
            int exitCode = launcher.launch().get();

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
        int exitCode = launcher.launch().get();

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
        int exitCode = launcher.launch().get();

        assertEquals(3, exitCode);
        assertEquals(List.of("out"), output);
        assertEquals(List.of("err"), error);
    }
}
