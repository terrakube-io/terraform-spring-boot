package io.terrakube.terraform;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public final class ProcessLauncher {
    private static final long STREAM_DRAIN_TIMEOUT_SECONDS = 60;

    private Process process;
    private ProcessBuilder builder;
    private Consumer<String> outputListener, errorListener;
    private boolean inheritIO;
    private ExecutorService executor;

    ProcessLauncher(ExecutorService executor, String... commands) {
        assert executor != null;
        this.executor = executor;
        this.process = null;
        this.builder = new ProcessBuilder(commands);
    }

	void setOutputListener(Consumer<String> listener) {
        assert this.process == null;
		this.outputListener = listener;
    }

	void setErrorListener(Consumer<String> listener) {
        assert this.process == null;
		this.errorListener = listener;
    }
    
	void setInheritIO(boolean inheritIO) {
        assert this.process == null;
		this.inheritIO = inheritIO;
    }
    
    void setDirectory(File directory) {
        assert this.process == null;
        this.builder.directory(directory);
    }
    
    void setRedirectErrorStream(boolean redirectErrorStream) {
        assert this.process == null;
        this.builder.redirectErrorStream(redirectErrorStream);
    }

    void appendCommands(String... commands) {
        Stream<String> filteredCommands = Arrays.stream(commands).filter(c -> c != null && c.length() > 0);
        this.builder.command().addAll(filteredCommands.collect(Collectors.toList()));
    }

    void setEnvironmentVariable(String name, String value) {
        assert name != null && name.length() > 0;
        Map<String, String> env = this.builder.environment();
        value = (value != null ? env.put(name, value) : env.remove(name));
    }

    void setOrAppendEnvironmentVariable(String name, String value, String delimiter) {
        assert name != null && name.length() > 0;
        if (value != null && value.length() > 0) {
            String current = System.getenv(name);
            String target = (current == null || current.length() == 0 ? value : String.join(delimiter, current, value));
            this.setEnvironmentVariable(name, target);
        }
    }

    CompletableFuture<Integer> launch() {
        assert this.process == null;
        if (this.inheritIO) {
            this.builder.inheritIO();
        }
        try {
            this.process = this.builder.start();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        Future<Boolean> outputDrained = null;
        Future<Boolean> errorDrained = null;
        if (!this.inheritIO) {
            if (this.outputListener != null) {
                outputDrained = this.executor.submit(() -> this.readProcessStream(this.process.getInputStream(), this.outputListener));
            }
            if (this.errorListener != null) {
                errorDrained = this.executor.submit(() -> this.readProcessStream(this.process.getErrorStream(), this.errorListener));
            }
        }
        final Future<Boolean> outputDone = outputDrained;
        final Future<Boolean> errorDone = errorDrained;
        return CompletableFuture.supplyAsync(() -> {
            try {
                int exitCode = this.process.waitFor();
                // Wait for the reader tasks to finish draining the process
                // output before completing, so callers never observe the exit
                // code while the listeners have received only part (or none)
                // of the output. The readers were submitted before this task
                // and read until EOF, so after process exit they only have
                // the remaining pipe buffer left to deliver; the timeout is a
                // safety net that turns a wedged reader into a visible
                // failure instead of silently missing output.
                awaitStreamDrained(outputDone, "output");
                awaitStreamDrained(errorDone, "error");
                return exitCode;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }, this.executor);
    }

    private void awaitStreamDrained(Future<Boolean> drained, String streamName) throws InterruptedException {
        if (drained == null) {
            return;
        }
        try {
            if (!drained.get(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new RuntimeException(String.format("Failed to capture process %s stream", streamName));
            }
        } catch (ExecutionException | TimeoutException ex) {
            throw new RuntimeException(String.format("Failed to capture process %s stream", streamName), ex);
        }
    }

    private boolean readProcessStream(InputStream stream, Consumer<String> listener) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.accept(line);
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
