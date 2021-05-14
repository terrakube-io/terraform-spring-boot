package org.azbuilder.terraform;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

@Slf4j
public class TerraformClient implements AutoCloseable {
    private static final String TERRAFORM_COMMAND = "terraform";

    private final ExecutorService executor = Executors.newWorkStealingPool();
    private File workingDirectory;
    private boolean inheritIO;
    private String terraformVersion;
    private String backendConfig;
    private TerraformDownloader terraformDownloader;
    private HashMap<String, String> environmentVariables;
    private HashMap<String, String> terraformParameters;
    private Consumer<String> outputListener, errorListener;

    public TerraformClient() {
        this(null, null, new HashMap<>(), new HashMap<>());
    }

    public TerraformClient(File workingDirectory) {
        this(null, workingDirectory, new HashMap<>(), new HashMap<>());
    }

    public TerraformClient(String terraformVersion, File workingDirectory, HashMap<String, String> terraformParameters, HashMap<String, String> environmentVariables) {
        assert environmentVariables != null;
        assert environmentVariables != null;
        assert terraformParameters != null;
        this.setEnvironmentVariables(environmentVariables);
        this.setTerraformParameters(terraformParameters);
        this.workingDirectory = workingDirectory;
        this.terraformDownloader = new TerraformDownloader();
        this.terraformVersion = terraformVersion;
    }

    public TerraformClient(HashMap<String, String> terraformParameters, HashMap<String, String> environmentVariables) {
        assert environmentVariables != null;
        this.setEnvironmentVariables(environmentVariables);
        this.setTerraformParameters(terraformParameters);
        this.workingDirectory = workingDirectory;
    }

    public Consumer<String> getOutputListener() {
        return this.outputListener;
    }

    public void setOutputListener(Consumer<String> listener) {
        this.outputListener = listener;
    }

    public Consumer<String> getErrorListener() {
        return this.errorListener;
    }

    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    public File getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(File workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public void setWorkingDirectory(Path folderPath) {
        this.setWorkingDirectory(folderPath.toFile());
    }

    public boolean isInheritIO() {
        return this.inheritIO;
    }

    public void setInheritIO(boolean inheritIO) {
        this.inheritIO = inheritIO;
    }

    public void setTerraformVersion(String terraformVersion) {
        this.terraformVersion = terraformVersion;
    }

    private String getTerraformVersion(String terraformVersion) throws IOException {
        return this.terraformDownloader.downloadTerraformVersion(terraformVersion);
    }

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

    public CompletableFuture<Boolean> plan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.plan);
    }

    public CompletableFuture<Boolean> apply() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> destroy() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init, TerraformCommand.destroy);
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
    }

    private ProcessLauncher getTerraformLauncher(TerraformCommand command) throws IOException {
        ProcessLauncher launcher = new ProcessLauncher(this.executor, (this.terraformVersion == null) ? TERRAFORM_COMMAND : getTerraformVersion(this.terraformVersion), command.name());
        launcher.setDirectory(this.getWorkingDirectory());
        launcher.setInheritIO(this.isInheritIO());

        for (Map.Entry<String, String> entry : this.getEnvironmentVariables().entrySet()) {
            launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
        }

        switch (command) {
            case init:
                if(getBackendConfig() !=null){
                    launcher.appendCommands("-backend-config=".concat(this.getBackendConfig()));
                }
                break;
            case plan:
                for (Map.Entry<String, String> entry : this.getTerraformParameters().entrySet()) {
                    launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                }
                break;
            case apply:
                for (Map.Entry<String, String> entry : this.getTerraformParameters().entrySet()) {
                    launcher.appendCommands("--var", entry.getKey().concat("=").concat(entry.getValue()));
                }
                launcher.appendCommands("-auto-approve");
                break;
            case destroy:
                launcher.appendCommands("-force");
                break;
        }
        launcher.setOutputListener(this.getOutputListener());
        launcher.setErrorListener(this.getErrorListener());
        return launcher;
    }

    @Override
    public void close() throws Exception {
        this.executor.shutdownNow();
        if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("executor did not terminate");
        }
    }

    public HashMap<String, String> getEnvironmentVariables() {
        return environmentVariables;
    }

    public void setEnvironmentVariables(HashMap<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public HashMap<String, String> getTerraformParameters() {
        return terraformParameters;
    }

    public void setTerraformParameters(HashMap<String, String> terraformParameters) {
        this.terraformParameters = terraformParameters;
    }

    public String getBackendConfig() {
        return backendConfig;
    }

    public void setBackendConfig(String backendConfig) {
        this.backendConfig = backendConfig;
    }
}

