package org.azbuilder.terraform;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class TerraformClient implements AutoCloseable {
    private static final String TERRAFORM_COMMAND = "terraform";

    private final ExecutorService executor = Executors.newWorkStealingPool();
    private File workingDirectory;
    private boolean inheritIO;
    private HashMap<String, String> environmentVariables;
    private Consumer<String> outputListener, errorListener;

    public TerraformClient() {
        this(new HashMap<>());
    }

    public TerraformClient(HashMap<String, String> environmentVariables) {
        assert environmentVariables != null;
        this.environmentVariables = environmentVariables;
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
        ProcessLauncher launcher = new ProcessLauncher(this.executor, TERRAFORM_COMMAND, command.name());
        launcher.setDirectory(this.getWorkingDirectory());
        launcher.setInheritIO(this.isInheritIO());

        for (Map.Entry<String, String> entry : this.environmentVariables.entrySet()) {
            launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
        }
        switch (command){
            case apply:
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
}
