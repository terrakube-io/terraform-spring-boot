package org.azbuilder.terraform;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

@Builder
@AllArgsConstructor(access = AccessLevel.PROTECTED)
@NoArgsConstructor
@Getter
@Setter
@Slf4j
public class TerraformClient implements AutoCloseable {

    private static final String TERRAFORM_PARAM_VARIABLE = "--var";
    private static final String TERRAFORM_PARAM_AUTO_APPROVED = "-auto-approve";
    private static final String TERRAFORM_PARAM_NO_COLOR = "-no-color";
    private static final String TERRAFORM_PARAM_FORCE = "-force";
    private static final String TERRAFORM_PARAM_JSON = "-json";
    private static final String TERRAFORM_PARAM_BACKEND = "-backend-config=";
    private static final String TERRAFORM_PARAM_OUTPUT_PLAN = "-out=terraformLibrary.tfPlan";
    private static final String TERRAFORM_PARAM_OUTPUT_PLAN_FILE = "terraformLibrary.tfPlan";

    private final ExecutorService executor = Executors.newWorkStealingPool();
    private final TerraformDownloader terraformDownloader = new TerraformDownloader();

    private File workingDirectory;
    private boolean inheritIO;
    private boolean showColor;
    private boolean jsonOutput;
    private String terraformVersion;
    private String backendConfig;

    @Singular
    private Map<String, String> environmentVariables;
    @Singular
    private Map<String, String> terraformParameters;
    private Consumer<String> outputListener;
    private Consumer<String> errorListener;

    public CompletableFuture<String> version() throws IOException {
        ProcessLauncher launcher = this.getTerraformLauncher(TerraformCommand.version);
        StringBuilder version = new StringBuilder();
        Consumer<String> outputListener = this.getOutputListener();
        launcher.setOutputListener(m -> {
            version.append(version.length() == 0 ? m : "");
            if (outputListener != null) {
                outputListener.accept(m);
            }
        });
        return launcher.launch().thenApply((c) -> c == 0 ? version.toString() : null);
    }

    public CompletableFuture<Boolean> show(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                new HashMap(),
                new HashMap(),
                outputListener,
                errorListener,
                TerraformCommand.show);
    }

    public CompletableFuture<Boolean> show() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.show);
    }

    public CompletableFuture<Boolean> showPlan(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                new HashMap(),
                new HashMap(),
                outputListener,
                errorListener,
                TerraformCommand.showPlan);
    }

    public CompletableFuture<Boolean> showPlan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.showPlan);
    }

    public CompletableFuture<Boolean> init(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                new HashMap<>(),
                new HashMap<>(),
                outputListener,
                errorListener,
                TerraformCommand.init);
    }

    public CompletableFuture<Boolean> init() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init);
    }

    public CompletableFuture<Boolean> plan(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Map<String, String> terraformVariables, @NonNull Map<String, String> terraformEnvironmentVariables, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                terraformVariables,
                terraformEnvironmentVariables,
                outputListener,
                errorListener,
                TerraformCommand.plan);
    }

    public CompletableFuture<Boolean> plan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.plan);
    }

    public CompletableFuture<Boolean> apply(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Map<String, String> terraformVariables, @NonNull Map<String, String> terraformEnvironmentVariables, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                terraformVariables,
                terraformEnvironmentVariables,
                outputListener,
                errorListener,
                TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> apply() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> destroy(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Map<String, String> terraformEnvironmentVariables, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                new HashMap<>(),
                terraformEnvironmentVariables,
                outputListener,
                errorListener,
                TerraformCommand.destroy);
    }

    public CompletableFuture<Boolean> destroy() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.destroy);
    }

    public CompletableFuture<Boolean> output(@NonNull String terraformVersion, @NonNull File workingDirectory, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                null,
                new HashMap<>(),
                new HashMap<>(),
                outputListener,
                errorListener,
                TerraformCommand.output);
    }

    public CompletableFuture<Boolean> output() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.output);
    }

    private CompletableFuture<Boolean> run(String terraformVersion, File workingDirectory, String terraformBackendConfigFileName, Map<String, String> terraformVariables, Map<String, String> terraformEnvironmentVariables, Consumer<String> outputListener, Consumer<String> errorListener, TerraformCommand... commands) throws IOException {
        assert commands.length > 0;
        ProcessLauncher[] launchers = new ProcessLauncher[commands.length];
        for (int i = 0; i < commands.length; i++) {
            launchers[i] = this.getTerraformLauncher(
                    terraformVersion,
                    workingDirectory,
                    terraformBackendConfigFileName,
                    terraformVariables,
                    terraformEnvironmentVariables,
                    outputListener,
                    errorListener, commands[i]);
        }

        return getLauncherResult(launchers, commands);
    }

    private CompletableFuture<Boolean> getLauncherResult(ProcessLauncher[] launchers, TerraformCommand[] commands) {
        CompletableFuture<Integer> result = launchers[0].launch().thenApply(c -> c == 0 ? 1 : -1);
        for (int i = 1; i < commands.length; i++) {
            result = result.thenCompose(index -> {
                if (index > 0) {
                    return launchers[index].launch().thenApply(c -> c == 0 ? index + 1 : -1);
                }
                return CompletableFuture.completedFuture(-1);
            });
        }
        return result.thenApply(i -> i > 0);
    }


    private CompletableFuture<Boolean> run(TerraformCommand... commands) throws IOException {
        assert commands.length > 0;
        ProcessLauncher[] launchers = new ProcessLauncher[commands.length];
        for (int i = 0; i < commands.length; i++) {
            launchers[i] = this.getTerraformLauncher(commands[i]);
        }

        return getLauncherResult(launchers, commands);
    }

    private void checkRunningParameters() {
        if (this.getWorkingDirectory() == null) {
            throw new IllegalArgumentException("working directory should not be null");
        }
        if (this.terraformVersion == null) {
            throw new IllegalArgumentException("Terraform version should not be null");
        }
    }

    private ProcessLauncher getTerraformLauncher(TerraformCommand command) throws IOException {
        return getTerraformLauncher(this.terraformVersion, this.workingDirectory, this.backendConfig, this.terraformParameters, this.environmentVariables, this.outputListener, this.errorListener, command);
    }


    private ProcessLauncher getTerraformLauncher(String terraformVersion, File workingDirectory, String terraformBackendConfigFileName, Map<String, String> terraformVariables, Map<String, String> terraformEnvironmentVariables, Consumer<String> outputListener, Consumer<String> errorListener, TerraformCommand command) throws IOException {
        ProcessLauncher launcher = new ProcessLauncher(this.executor, this.terraformDownloader.downloadTerraformVersion(terraformVersion), command.getLabel());
        launcher.setDirectory(workingDirectory);
        launcher.setInheritIO(this.isInheritIO());

        if (terraformEnvironmentVariables != null)
            for (Map.Entry<String, String> entry : terraformEnvironmentVariables.entrySet()) {
                launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        ComparableVersion version = new ComparableVersion(terraformVersion);
        switch (command) {
            case init:
                if (terraformBackendConfigFileName != null) {
                    launcher.appendCommands(TERRAFORM_PARAM_BACKEND.concat(terraformBackendConfigFileName));
                }
                break;
            case plan:
                for (Map.Entry<String, String> entry : terraformVariables.entrySet()) {
                    launcher.appendCommands(TERRAFORM_PARAM_VARIABLE, entry.getKey().concat("=").concat(entry.getValue()));
                }
                launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN);
                break;
            case apply:
                if (terraformVariables.entrySet().isEmpty()) {
                    launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                    launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                } else {
                    for (Map.Entry<String, String> entry : terraformVariables.entrySet()) {
                        launcher.appendCommands(TERRAFORM_PARAM_VARIABLE, entry.getKey().concat("=").concat(entry.getValue()));
                    }
                    launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                }
                break;
            case destroy:
                //https://www.terraform.io/upgrade-guides/0-15.html#other-minor-command-line-behavior-changes
                if (version.compareTo(new ComparableVersion("0.15.0")) < 0)
                    launcher.appendCommands(TERRAFORM_PARAM_FORCE);
                else
                    launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                break;
            case show:
            case output:
                launcher.appendCommands(TERRAFORM_PARAM_JSON);
                break;
            case showPlan:
                launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                break;
            default:
                break;
        }

        if (!this.showColor)
            launcher.appendCommands(TERRAFORM_PARAM_NO_COLOR);

        //https://www.terraform.io/docs/internals/machine-readable-ui.html
        if (this.jsonOutput && version.compareTo(new ComparableVersion("0.15.2")) > 0)
            switch (command) {
                case plan:
                case apply:
                case destroy:
                    launcher.appendCommands(TERRAFORM_PARAM_JSON);
                    break;
                default:
                    break;
            }

        launcher.setOutputListener(outputListener);
        launcher.setErrorListener(errorListener);
        return launcher;
    }

    @Override
    public void close() throws Exception {
        this.executor.shutdownNow();
        if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("executor did not terminate");
        }
    }
}

