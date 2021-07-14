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

    public CompletableFuture<Boolean> plan(@NonNull String terraformVersion, @NonNull File workingDirectory, String terraformBackendConfigFileName, @NonNull Map<String, String> terraformVariables, @NonNull Map<String, String> terraformEnvironmentVariables, @NonNull Consumer<String> outputListener, @NonNull Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformVersion,
                workingDirectory,
                terraformBackendConfigFileName,
                terraformVariables,
                terraformEnvironmentVariables,
                outputListener,
                errorListener,
                TerraformCommand.init, TerraformCommand.plan);
    }

    public CompletableFuture<Boolean> plan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.plan);
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
                TerraformCommand.init, TerraformCommand.plan, TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> apply() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.apply);
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
                TerraformCommand.init, TerraformCommand.destroy);
    }

    public CompletableFuture<Boolean> destroy() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.destroy);
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

    private void checkRunningParameters() {
        if (this.getWorkingDirectory() == null) {
            throw new IllegalArgumentException("working directory should not be null");
        }
        if (this.terraformVersion == null) {
            throw new IllegalArgumentException("Terraform version should not be null");
        }
    }

    private ProcessLauncher getTerraformLauncher(TerraformCommand command) throws IOException {
        ProcessLauncher launcher = new ProcessLauncher(this.executor, this.terraformDownloader.downloadTerraformVersion(this.terraformVersion), command.name());
        launcher.setDirectory(this.getWorkingDirectory());
        launcher.setInheritIO(this.isInheritIO());

        if (this.environmentVariables != null)
            for (Map.Entry<String, String> entry : this.environmentVariables.entrySet()) {
                launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        ComparableVersion version = new ComparableVersion(this.terraformVersion);
        switch (command) {
            case init:
                if (this.backendConfig != null) {
                    launcher.appendCommands("-backend-config=".concat(this.getBackendConfig()));
                }
                break;
            case plan:
                if (this.terraformParameters != null)
                    for (Map.Entry<String, String> entry : this.terraformParameters.entrySet()) {
                        launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                    }
                break;
            case apply:
                if (this.terraformParameters != null)
                    for (Map.Entry<String, String> entry : this.getTerraformParameters().entrySet()) {
                        launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                    }
                launcher.appendCommands("-auto-approve");
                break;
            case destroy:
                //https://www.terraform.io/upgrade-guides/0-15.html#other-minor-command-line-behavior-changes
                if (version.compareTo(new ComparableVersion("0.15.0")) < 0)
                    launcher.appendCommands("-force");
                else
                    launcher.appendCommands("-auto-approve");
                break;
        }
        if (!this.showColor)
            launcher.appendCommands("-no-color");

        //https://www.terraform.io/docs/internals/machine-readable-ui.html
        if (this.jsonOutput && version.compareTo(new ComparableVersion("0.15.2")) > 0)
            switch(command) {
                case plan:
                case apply:
                case destroy:
                    launcher.appendCommands("-json");
                    break;
            }

        launcher.setOutputListener(this.getOutputListener());
        launcher.setErrorListener(this.getErrorListener());
        return launcher;
    }


    private ProcessLauncher getTerraformLauncher(String terraformVersion, File workingDirectory, String terraformBackendConfigFileName, Map<String, String> terraformVariables, Map<String, String> terraformEnvironmentVariables, Consumer<String> outputListener, Consumer<String> errorListener, TerraformCommand command) throws IOException {
        ProcessLauncher launcher = new ProcessLauncher(this.executor, this.terraformDownloader.downloadTerraformVersion(terraformVersion), command.name());
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
                    launcher.appendCommands("-backend-config=".concat(terraformBackendConfigFileName));
                }
                break;
            case plan:
                for (Map.Entry<String, String> entry : terraformVariables.entrySet()) {
                    launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                }
                break;
            case apply:
                for (Map.Entry<String, String> entry : terraformVariables.entrySet()) {
                    launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                }
                launcher.appendCommands("-auto-approve");
                break;
            case destroy:
                //https://www.terraform.io/upgrade-guides/0-15.html#other-minor-command-line-behavior-changes
                if (version.compareTo(new ComparableVersion("0.15.0")) < 0)
                    launcher.appendCommands("-force");
                else
                    launcher.appendCommands("-auto-approve");
                break;
        }

        if (!this.showColor)
            launcher.appendCommands("-no-color");

        //https://www.terraform.io/docs/internals/machine-readable-ui.html
        if (this.jsonOutput && version.compareTo(new ComparableVersion("0.15.2")) > 0)
        switch(command) {
            case plan:
            case apply:
            case destroy:
                launcher.appendCommands("-json");
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

