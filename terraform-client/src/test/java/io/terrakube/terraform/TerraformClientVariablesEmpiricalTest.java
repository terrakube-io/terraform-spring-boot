package io.terrakube.terraform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Empirical test suite validating Terraform variable parameter handling and Finding H-1:
 * 1. Passing an unquoted string to a `type = any` variable via `--var` fails with "Variables not allowed".
 * 2. Passing a literal list `["a", "b"]` to an untyped variable via `--var` is parsed in
 *    VariableParseLiteral mode (treated as a raw string), causing indexing operations to fail with
 *    "This value does not have any indices".
 * 3. Passing a quoted string `"value"` to a `type = string` variable via `--var` causes quotes to be
 *    preserved literally in the string value ("\"value\"").
 * 4. In contrast, passing all variables via a `.tfvars` file (`-var-file`) uses native HCL grammar and
 *    resolves all types (string, any, list) cleanly without quote pollution or type mismatch errors.
 */
@DisabledOnOs(OS.WINDOWS)
class TerraformClientVariablesEmpiricalTest {

    private static final String TERRAFORM_RELEASES = "{\"name\":\"terraform\",\"versions\":{\"1.16.1\":{\"name\":\"terraform\",\"version\":\"1.16.1\",\"builds\":[]}}}";
    private static final String TOFU_RELEASES = "[]";

    @TempDir
    Path tempWorkingDir;

    private HttpServer mockReleaseServer;
    private TerraformClient client;
    private String terraformBinary;
    private File workingDirFile;

    @BeforeEach
    void setUp() throws IOException {
        terraformBinary = findTerraformBinary();
        assumeTrue(terraformBinary != null, "Terraform binary must be available to run empirical tests");

        mockReleaseServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockReleaseServer.createContext("/terraform.json", exchange -> {
            byte[] bytes = TERRAFORM_RELEASES.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockReleaseServer.createContext("/tofu.json", exchange -> {
            byte[] bytes = TOFU_RELEASES.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockReleaseServer.start();

        int port = mockReleaseServer.getAddress().getPort();
        String tfUrl = "http://127.0.0.1:" + port + "/terraform.json";
        String tofuUrl = "http://127.0.0.1:" + port + "/tofu.json";

        client = new TerraformClient() {
            @Override
            public TerraformDownloader createTerraformDownloader() {
                return new TerraformDownloader(tfUrl, tofuUrl) {
                    @Override
                    public String downloadTerraformVersion(String version) {
                        return terraformBinary;
                    }
                };
            }
        };

        workingDirFile = tempWorkingDir.toFile();
        createMainTf(workingDirFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (mockReleaseServer != null) {
            mockReleaseServer.stop(0);
        }
    }

    /**
     * Finding H-1 Scenario 1: Unquoting string literals breaks `type = any` variables.
     * When PR #3804 strips quotes from HCL string literals, it passes `any_var=from-cli`.
     * Because `any` requires HCL expression parsing, Terraform treats `from-cli` as an
     * invalid identifier/variable reference and fails with "Variables not allowed".
     */
    @Test
    void unquotedAnyVariableFailsWithVariablesNotAllowed() throws Exception {
        Map<String, String> variables = new HashMap<>();
        variables.put("any_var", "from-cli");

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        TerraformProcessData processData = TerraformProcessData.builder()
                .terraformVersion("1.16.1")
                .workingDirectory(workingDirFile)
                .terraformVariables(variables)
                .build();

        boolean success = client.plan(processData, output::add, errors::add).get();

        assertFalse(success, "Plan must fail when type=any is passed an unquoted string identifier via --var");
        boolean hasVariablesNotAllowed = errors.stream().anyMatch(line -> line.contains("Variables not allowed") || line.contains("Variables may not be used here"));
        assertTrue(hasVariablesNotAllowed, "Error output must indicate variables are not allowed: " + errors);
    }

    /**
     * Finding H-1 Scenario 2: Passing an untyped list via `--var` fails during list operations.
     * Terraform CLI parses untyped `--var` arguments using VariableParseLiteral, converting
     * `["a","b"]` into a literal String `"[\"a\",\"b\"]"`. Subsequent index access fails.
     */
    @Test
    void literalUntypedListVariableFailsWithInvalidIndex() throws Exception {
        Map<String, String> variables = new HashMap<>();
        variables.put("untyped_var", "[\"a\",\"b\"]");

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        TerraformProcessData processData = TerraformProcessData.builder()
                .terraformVersion("1.16.1")
                .workingDirectory(workingDirFile)
                .terraformVariables(variables)
                .build();

        boolean success = client.plan(processData, output::add, errors::add).get();

        assertFalse(success, "Plan must fail because untyped variable is parsed as literal String instead of tuple");
        boolean hasInvalidIndex = errors.stream().anyMatch(line -> line.contains("Invalid index") || line.contains("This value does not have any indices"));
        assertTrue(hasInvalidIndex, "Error output must show invalid index due to string coercion: " + errors);
    }

    /**
     * Finding H-1 Scenario 3: Passing literal HCL quotes to `type = string` embeds quotes into string value.
     * When passing `\"quoted-val\"` to a string variable via `--var`, VariableParseLiteral mode
     * preserves the quotes, polluting the value to `"\"quoted-val\""`.
     */
    @Test
    void literalQuotedStringVariableEmbedsQuotesInOutputValue() throws Exception {
        Map<String, String> variables = new HashMap<>();
        variables.put("str_var", "\"quoted-val\"");

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        TerraformProcessData processData = TerraformProcessData.builder()
                .terraformVersion("1.16.1")
                .workingDirectory(workingDirFile)
                .terraformVariables(variables)
                .build();

        boolean success = client.plan(processData, output::add, errors::add).get();

        assertTrue(success, "Plan should succeed syntactically for string variable");
        boolean hasPollutedQuotes = output.stream().anyMatch(line -> line.contains("+ str_out") && line.contains("\"\\\"quoted-val\\\"\""));
        assertTrue(hasPollutedQuotes, "Output must contain literal escaped quotes indicating quote pollution: " + output);
    }

    /**
     * Finding H-1 Resolution: Passing variables via `-var-file` resolves ALL types cleanly.
     * In a `.tfvars` file, HCL grammar natively evaluates strings, any, and collections
     * without CLI parsing mode mismatches or quote pollution.
     */
    @Test
    void varFileResolvesAllVariableTypesCleanly() throws Exception {
        File tfvarsFile = new File(workingDirFile, "terraform.tfvars");
        try (FileWriter writer = new FileWriter(tfvarsFile)) {
            writer.write("any_var = \"from-cli\"\n"
                    + "str_var = \"quoted-val\"\n"
                    + "untyped_var = [\"a\", \"b\"]\n");
        }

        List<String> output = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        TerraformProcessData processData = TerraformProcessData.builder()
                .terraformVersion("1.16.1")
                .workingDirectory(workingDirFile)
                .varFileName("terraform.tfvars")
                .terraformVariables(new HashMap<>())
                .build();

        boolean success = client.plan(processData, output::add, errors::add).get();

        assertTrue(success, "Plan must succeed cleanly when passing variables via -var-file");
        assertTrue(errors.isEmpty(), "There should be no errors when using -var-file: " + errors);

        boolean hasCleanAnyOut = output.stream().anyMatch(line -> line.contains("+ any_out") && line.contains("\"from-cli\""));
        boolean hasCleanStrOut = output.stream().anyMatch(line -> line.contains("+ str_out") && line.contains("\"quoted-val\"") && !line.contains("\\\""));
        boolean hasCleanUntypedOut = output.stream().anyMatch(line -> line.contains("+ untyped_first") && line.contains("\"a\""));

        assertTrue(hasCleanAnyOut, "any_out must resolve to from-cli: " + output);
        assertTrue(hasCleanStrOut, "str_out must resolve cleanly to quoted-val without escaped quotes: " + output);
        assertTrue(hasCleanUntypedOut, "untyped_first must resolve cleanly to indexed element a: " + output);
    }

    private void createMainTf(File directory) throws IOException {
        File mainTf = new File(directory, "main.tf");
        try (FileWriter writer = new FileWriter(mainTf)) {
            writer.write("terraform {\n"
                    + "  required_version = \">= 1.0\"\n"
                    + "}\n\n"
                    + "variable \"any_var\" {\n"
                    + "  type    = any\n"
                    + "  default = \"sample\"\n"
                    + "}\n\n"
                    + "variable \"str_var\" {\n"
                    + "  type    = string\n"
                    + "  default = \"default_str\"\n"
                    + "}\n\n"
                    + "variable \"untyped_var\" {\n"
                    + "  default = [\"default\"]\n"
                    + "}\n\n"
                    + "output \"untyped_first\" {\n"
                    + "  value = var.untyped_var[0]\n"
                    + "}\n\n"
                    + "output \"str_out\" {\n"
                    + "  value = var.str_var\n"
                    + "}\n\n"
                    + "output \"any_out\" {\n"
                    + "  value = var.any_var\n"
                    + "}\n");
        }
    }

    private static String findTerraformBinary() {
        File usrBin = new File("/usr/bin/terraform");
        if (usrBin.exists() && usrBin.canExecute()) {
            return usrBin.getAbsolutePath();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File bin = new File(dir, "terraform");
                if (bin.exists() && bin.canExecute()) {
                    return bin.getAbsolutePath();
                }
            }
        }
        return null;
    }
}

