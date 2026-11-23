package io.terrakube.terraform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the public command methods end to end without a terraform release:
 * the client's downloader is replaced by one that hands back {@code echo} as
 * the binary, so each command prints the argument list it was given instead of
 * running terraform. That keeps the whole path - client settings, process data,
 * command line, launch - under test, rather than only the package-private
 * command builder.
 * <p>
 * The real TerraformDownloader constructor still runs, and it creates its
 * directories under {@code user.home}, so that is pointed at a temporary
 * directory for the duration of the class.
 */
@DisabledOnOs(OS.WINDOWS)
class TerraformClientApiTest {

    private static final Consumer<String> IGNORE = line -> {
    };

    @TempDir
    static Path userHome;
    private static String originalUserHome;

    @BeforeAll
    static void redirectUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", userHome.toString());
    }

    @AfterAll
    static void restoreUserHome() {
        System.setProperty("user.home", originalUserHome);
    }

    private static TerraformClient echoClient(AtomicInteger downloads) {
        return fakeBinaryClient("echo", downloads);
    }

    private static TerraformClient fakeBinaryClient(String binary, AtomicInteger downloads) {
        // The public no-arg constructor on purpose: it is the one that leaves the
        // @Singular maps null, which is what made every no-arg command fail.
        return new TerraformClient() {
            @Override
            public TerraformDownloader createTerraformDownloader() {
                return new TerraformDownloader() {
                    @Override
                    public String downloadTerraformVersion(String terraformVersion) {
                        downloads.incrementAndGet();
                        return binary;
                    }

                    @Override
                    public String downloadTofuVersion(String tofuVersion) {
                        downloads.incrementAndGet();
                        return binary;
                    }
                };
            }
        };
    }

    private static String script(Path directory, String name, String body) throws IOException {
        Path script = directory.resolve(name);
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        assertTrue(script.toFile().setExecutable(true));
        return script.toString();
    }

    private static TerraformProcessData processData(File workingDirectory) {
        return TerraformProcessData.builder()
                .terraformVersion("1.5.7")
                .workingDirectory(workingDirectory)
                .build();
    }

    @Test
    void noArgClientConfiguredWithSettersRunsCommands(@TempDir File workingDirectory) throws Exception {
        List<String> output = new CopyOnWriteArrayList<>();
        try (TerraformClient client = echoClient(new AtomicInteger())) {
            assertNull(client.getTerraformParameters(), "precondition: the no-arg constructor leaves the maps null");
            assertNull(client.getEnvironmentVariables(), "precondition: the no-arg constructor leaves the maps null");
            client.setWorkingDirectory(workingDirectory);
            client.setTerraformVersion("1.5.7");
            client.setOutputListener(output::add);

            assertEquals("version -no-color", client.version().get(30, TimeUnit.SECONDS));

            output.clear();
            assertTrue(client.plan().get(30, TimeUnit.SECONDS));
            assertEquals(List.of("plan -no-color -out=terraformLibrary.tfPlan -input=false"), output);
        }
        assertTrue(Files.isDirectory(userHome.resolve(".terraform-spring-boot")),
                "the downloader should have created its directories under the redirected user.home");
    }

    @Test
    void planDetailExitCodeLeavesTheCallersProcessDataUnchanged(@TempDir File workingDirectory, @TempDir Path bin) throws Exception {
        // Settings the copy has to carry over, each observable from the outside:
        // refreshOnly on the command line, the environment variable through the
        // fake binary's output, the timeout by a binary that outlives it.
        TerraformProcessData data = TerraformProcessData.builder()
                .terraformVersion("1.5.7")
                .workingDirectory(workingDirectory)
                .refreshOnly(true)
                .terraformEnvironmentVariable("TK_TEST_VAR", "from-process-data")
                .timeoutSeconds(2)
                .build();
        String printEnvAndArgs = script(bin, "print.sh", "echo \"$TK_TEST_VAR\" \"$@\"");
        String hang = script(bin, "hang.sh", "exec sleep 30");

        try (TerraformClient client = fakeBinaryClient(printEnvAndArgs, new AtomicInteger())) {
            List<String> output = new CopyOnWriteArrayList<>();
            assertEquals(0, client.planDetailExitCode(data, output::add, IGNORE).get(30, TimeUnit.SECONDS));
            assertTrue(output.get(0).startsWith("from-process-data plan "), output.toString());
            assertTrue(output.get(0).contains("-refresh-only"), output.toString());
            assertTrue(output.get(0).contains("-detailed-exitcode"), output.toString());

            output.clear();
            assertEquals(0, client.planDestroyDetailExitCode(data, output::add, IGNORE).get(30, TimeUnit.SECONDS));
            assertTrue(output.get(0).startsWith("from-process-data plan "), output.toString());
            assertTrue(output.get(0).contains("-refresh-only"), output.toString());
            assertTrue(output.get(0).contains("-detailed-exitcode"), output.toString());
        }

        try (TerraformClient client = fakeBinaryClient(hang, new AtomicInteger())) {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> client.planDetailExitCode(data, IGNORE, IGNORE).get(30, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause(),
                    "the copy should keep the per-job timeout, got " + failure.getCause());
        }

        assertFalse(data.isDetailExitCode(), "the caller's process data must not be modified");

        try (TerraformClient client = echoClient(new AtomicInteger())) {
            // The reuse that used to break: this plan picked up -detailed-exitcode
            // and would have reported exit code 2 ("changes present") as failure.
            List<String> output = new CopyOnWriteArrayList<>();
            assertTrue(client.plan(data, output::add, IGNORE).get(30, TimeUnit.SECONDS));
            assertFalse(output.get(0).contains("-detailed-exitcode"), output.toString());
        }
    }

    @Test
    void nullMapsInProcessDataAreTreatedAsEmpty(@TempDir File workingDirectory) throws Exception {
        TerraformProcessData data = processData(workingDirectory);
        data.setTerraformVariables(null);
        data.setTerraformEnvironmentVariables(null);

        try (TerraformClient builderClient = TerraformClient.builder().build()) {
            for (TerraformCommand command : TerraformCommand.values()) {
                builderClient.createCommandLauncher(data, "/dummy/terraform", command);
            }
            assertTrue(builderClient.createCommandLauncher(data, "/dummy/terraform", TerraformCommand.apply)
                    .getCommands().contains("terraformLibrary.tfPlan"), "no variables still means applying the saved plan");
        }

        // The detailed-exit-code variants copy the process data first.
        try (TerraformClient client = echoClient(new AtomicInteger())) {
            assertEquals(0, client.planDetailExitCode(data, IGNORE, IGNORE).get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void refreshFalseTogetherWithRefreshOnlyIsRejectedBeforeDownloading(@TempDir File workingDirectory) throws Exception {
        TerraformProcessData data = TerraformProcessData.builder()
                .terraformVersion("1.5.7")
                .workingDirectory(workingDirectory)
                .refresh(false)
                .refreshOnly(true)
                .build();

        AtomicInteger downloads = new AtomicInteger();
        try (TerraformClient client = echoClient(downloads)) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.createCommandLauncher(data, "/dummy/terraform", TerraformCommand.plan));
            assertThrows(IllegalArgumentException.class,
                    () -> client.createCommandLauncher(data, "/dummy/terraform", TerraformCommand.planDestroy));
            assertThrows(IllegalArgumentException.class, () -> client.plan(data, IGNORE, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> client.planDestroy(data, IGNORE, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> client.planDetailExitCode(data, IGNORE, IGNORE));
            assertThrows(IllegalArgumentException.class, () -> client.planDestroyDetailExitCode(data, IGNORE, IGNORE));
        }
        assertEquals(0, downloads.get(), "contradictory flags must be rejected before any binary is fetched");
    }

    // Prints, one per line: GIT_SSH_COMMAND as the process sees it; how sh
    // splits it into words, which is what git does with it, and the third word,
    // which is the key path; then each argument, so argument boundaries show.
    private static String printInitScript(Path bin) throws IOException {
        return script(bin, "print-init.sh", String.join("\n",
                "printf 'GIT_SSH_COMMAND=%s\\n' \"${GIT_SSH_COMMAND-<unset>}\"",
                "(eval \"set -- $GIT_SSH_COMMAND\"; printf 'words=%s key=%s\\n' \"$#\" \"$3\")",
                "printf 'arg=%s\\n' \"$@\"",
                "echo to-stderr >&2"));
    }

    private static List<String> initOutput(TerraformClient client, TerraformProcessData data) throws Exception {
        List<String> output = new CopyOnWriteArrayList<>();
        assertTrue(client.init(data, output::add, IGNORE).get(30, TimeUnit.SECONDS));
        return output;
    }

    private static List<String> args(List<String> output) {
        return output.stream().filter(line -> line.startsWith("arg=")).collect(Collectors.toList());
    }

    private static String line(List<String> output, String prefix) {
        return output.stream().filter(line -> line.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + prefix + " line in " + output));
    }

    @Test
    void shellQuoteKeepsEveryCharacterLiteral() throws Exception {
        assertEquals("'it'\\''s'", TerraformClient.shellQuote("it's"));
        List<String> values = List.of("", "plain", "with space", "it's", "''", "$HOME", "${PATH}",
                "`id`", "$(id)", "a;b", "a && b", "back\\slash", "new\nline", "a 'b' $c `d` ;e");
        for (String value : values) {
            // The way git uses it: the shell evaluates the quoted text.
            ProcessBuilder shell = new ProcessBuilder("sh", "-c", "eval \"set -- $Q\"; printf '%s:%s' \"$#\" \"$1\"");
            shell.environment().put("Q", TerraformClient.shellQuote(value));
            Process process = shell.start();
            String result = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor());
            assertEquals("1:" + value, result, "shell-quoted " + value);
        }
    }

    @Test
    void sshInitRunsTheBinaryDirectlyWithTheSameArgumentsAsInit(@TempDir File workingDirectory, @TempDir Path bin) throws Exception {
        String binary = printInitScript(bin);
        File sshFile = new File(workingDirectory, "id_rsa");
        String backendConfig = "backend dir/a;b.hcl";
        for (boolean tofu : new boolean[]{false, true}) {
            TerraformProcessData plain = TerraformProcessData.builder()
                    .terraformVersion("1.5.7")
                    .workingDirectory(workingDirectory)
                    .terraformBackendConfigFileName(backendConfig)
                    .tofu(tofu)
                    .build();
            TerraformProcessData withSsh = plain.toBuilder().sshFile(sshFile).build();

            try (TerraformClient client = fakeBinaryClient(binary, new AtomicInteger())) {
                // The binary itself, not bash -c with a command string.
                List<String> sshCommands = client.createCommandLauncher(withSsh, binary, TerraformCommand.init).getCommands();
                assertEquals(binary, sshCommands.get(0));
                assertEquals(client.createCommandLauncher(plain, binary, TerraformCommand.init).getCommands(), sshCommands);

                List<String> plainOutput = initOutput(client, plain);
                List<String> sshOutput = initOutput(client, withSsh);

                // The backend config file name arrives as one literal argument:
                // through bash, the space split it and the ; ended the command.
                List<String> expectedArgs = List.of("arg=init", "arg=-no-color",
                        "arg=-backend-config=" + backendConfig, "arg=-input=false");
                assertEquals(expectedArgs, args(plainOutput), "tofu=" + tofu);
                assertEquals(expectedArgs, args(sshOutput), "tofu=" + tofu);

                String inherited = System.getenv("GIT_SSH_COMMAND");
                assertEquals("GIT_SSH_COMMAND=" + (inherited == null ? "<unset>" : inherited),
                        line(plainOutput, "GIT_SSH_COMMAND="), "init without an sshFile must not set it");
                assertEquals("GIT_SSH_COMMAND=ssh -i '" + sshFile.getAbsolutePath() + "' -o StrictHostKeyChecking=no",
                        line(sshOutput, "GIT_SSH_COMMAND="));
            }
        }
    }

    @Test
    void sshKeyPathReachesGitsShellIntact(@TempDir File workingDirectory, @TempDir Path bin) throws Exception {
        File sshFile = new File(workingDirectory, "my keys/it's $HOME `id`; echo injected/id_rsa");
        TerraformProcessData data = processData(workingDirectory).toBuilder().sshFile(sshFile).build();

        try (TerraformClient client = fakeBinaryClient(printInitScript(bin), new AtomicInteger())) {
            List<String> output = initOutput(client, data);
            assertEquals("words=5 key=" + sshFile.getAbsolutePath(), line(output, "words="));
        }
    }

    @Test
    void sshFileTakesPrecedenceOverExplicitGitSshCommand(@TempDir File workingDirectory, @TempDir Path bin) throws Exception {
        File sshFile = new File(workingDirectory, "id_rsa");
        String expected = "GIT_SSH_COMMAND=ssh -i '" + sshFile.getAbsolutePath() + "' -o StrictHostKeyChecking=no";
        String binary = printInitScript(bin);
        // Callers pass user-controlled variables in this map, so a global value,
        // even an empty one, must not drop the key and fail every SSH fetch.
        for (String callerValue : List.of("ssh -F /custom/config", "")) {
            TerraformProcessData data = processData(workingDirectory).toBuilder()
                    .sshFile(sshFile)
                    .terraformEnvironmentVariable("GIT_SSH_COMMAND", callerValue)
                    .build();

            try (TerraformClient client = fakeBinaryClient(binary, new AtomicInteger())) {
                assertEquals(expected, line(initOutput(client, data), "GIT_SSH_COMMAND="), "caller value '" + callerValue + "'");

                // Only init is affected: other commands get the caller's value as is.
                List<String> output = new CopyOnWriteArrayList<>();
                assertTrue(client.plan(data, output::add, IGNORE).get(30, TimeUnit.SECONDS));
                assertEquals("GIT_SSH_COMMAND=" + callerValue, line(output, "GIT_SSH_COMMAND="));
            }
        }
    }

    @Test
    void sshInitHonoursRedirectErrorStream(@TempDir File workingDirectory, @TempDir Path bin) throws Exception {
        TerraformProcessData data = processData(workingDirectory).toBuilder()
                .sshFile(new File(workingDirectory, "id_rsa"))
                .build();

        try (TerraformClient client = fakeBinaryClient(printInitScript(bin), new AtomicInteger())) {
            client.setRedirectErrorStream(true);
            List<String> output = new CopyOnWriteArrayList<>();
            List<String> errors = new CopyOnWriteArrayList<>();
            assertTrue(client.init(data, output::add, errors::add).get(30, TimeUnit.SECONDS));
            assertTrue(output.contains("to-stderr"), output.toString());
            assertEquals(List.of(), errors);
        }
    }

    @Test
    void nullProcessDataIsRejectedWithAClearMessage() throws Exception {
        try (TerraformClient client = TerraformClient.builder().build()) {
            List<Executable> calls = List.of(
                    () -> client.init(null, IGNORE, IGNORE),
                    () -> client.plan(null, IGNORE, IGNORE),
                    () -> client.planDetailExitCode(null, IGNORE, IGNORE),
                    () -> client.planDestroy(null, IGNORE, IGNORE),
                    () -> client.planDestroyDetailExitCode(null, IGNORE, IGNORE),
                    () -> client.apply(null, IGNORE, IGNORE),
                    () -> client.destroy(null, IGNORE, IGNORE),
                    () -> client.output(null, IGNORE, IGNORE),
                    () -> client.statePull(null, IGNORE, IGNORE));
            for (Executable call : calls) {
                NullPointerException failure = assertThrows(NullPointerException.class, call);
                // Exact, because the JDK's helpful NPE message for a dereference
                // further down also names the parameter and would pass a contains().
                assertEquals("terraformProcessData is marked non-null but is null", failure.getMessage());
            }
        }
    }
}
