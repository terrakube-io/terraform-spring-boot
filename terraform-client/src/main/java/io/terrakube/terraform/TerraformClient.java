package io.terrakube.terraform;

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

    private static final String TERRAFORM_PARAM_VARIABLE_FILE = "-var-file";
    private static final String TERRAFORM_PARAM_AUTO_APPROVED = "-auto-approve";
    private static final String TERRAFORM_PARAM_NO_COLOR = "-no-color";
    private static final String TERRAFORM_PARAM_FORCE = "-force";
    private static final String TERRAFORM_PARAM_JSON = "-json";
    private static final String TERRAFORM_PARAM_BACKEND = "-backend-config=";
    private static final String TERRAFORM_PARAM_OUTPUT_PLAN = "-out=terraformLibrary.tfPlan";

    private static final String TERRAFORM_PARAM_PLAN_DESTROY = "-destroy";
    private static final String TERRAFORM_PARAM_OUTPUT_PLAN_FILE = "terraformLibrary.tfPlan";
    private static final String TERRAFORM_PARAM_DISABLE_USER_INPUT = "-input=false";
    private static final String TERRAFORM_PARAM_DETAIL_EXIT_CODE ="-detailed-exitcode";
    private static final String TERRAFORM_PLAN_REFRESH_FALSE="-refresh=false";
    private static final String TERRAFORM_PLAN_REFRESH_ONLY="-refresh-only";
    private static final String TF_STATE_PULL="pull";
    // Deliberately separate from ProcessLauncher.GRACE_PERIOD_SECONDS: that one
    // answers "how long may terraform take to shut down after a job timeout",
    // this one "how much of the pod's termination grace period may close()
    // spend". They happen to agree today.
    private static final long CLOSE_GRACE_PERIOD_SECONDS = 10;

    private final ExecutorService executor = Executors.newWorkStealingPool();
    private final Set<ProcessLauncher> liveLaunchers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private File workingDirectory;
    private boolean inheritIO;
    private boolean showColor;
    private boolean jsonOutput;
    private boolean redirectErrorStream;
    private String terraformVersion;
    private String backendConfig;
    private String terraformReleasesUrl;
    private String tofuReleasesUrl;

    private String varFileName;

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

    public CompletableFuture<Boolean> show(@NonNull TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkVarFileParam(terraformProcessData);
        checkTerraformVariablesParam(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.show);
    }

    public CompletableFuture<Boolean> show() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.show);
    }

    public CompletableFuture<Boolean> showPlan(@NonNull TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkVarFileParam(terraformProcessData);
        checkTerraformVariablesParam(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.showPlan);
    }

    public CompletableFuture<Boolean> showPlanJson(@NonNull TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkVarFileParam(terraformProcessData);
        checkTerraformVariablesParam(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.showPlanJson);
    }

    public CompletableFuture<Boolean> showPlan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.showPlan);
    }

    public CompletableFuture<Boolean> init(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkVarFileParam(terraformProcessData);
        checkTerraformVariablesParam(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.init);
    }

    public CompletableFuture<Boolean> init() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.init);
    }

    public CompletableFuture<Boolean> plan(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.plan);
    }

    public CompletableFuture<Integer> planDetailExitCode(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        terraformProcessData.setDetailExitCode(true);
        return this.getTerraformLauncher(
                terraformProcessData,
                outputListener,
                errorListener, TerraformCommand.plan).launch();
    }

    public CompletableFuture<Boolean> statePull(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.statePull);
    }

    public CompletableFuture<Boolean> planDestroy(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.planDestroy);
    }

    public CompletableFuture<Integer> planDestroyDetailExitCode(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        terraformProcessData.setDetailExitCode(true);
        return this.getTerraformLauncher(
                terraformProcessData,
                outputListener,
                errorListener, TerraformCommand.planDestroy).launch();
    }

    public CompletableFuture<Boolean> plan() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.plan);
    }

    public CompletableFuture<Boolean> apply(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> apply() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.apply);
    }

    public CompletableFuture<Boolean> destroy(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkBackendConfigFile(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.destroy);
    }

    public CompletableFuture<Boolean> destroy() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.destroy);
    }

    public CompletableFuture<Boolean> output(TerraformProcessData terraformProcessData, @NonNull Consumer<String> outputListener, Consumer<String> errorListener) throws IOException {
        checkBackendConfigFile(terraformProcessData);
        checkVarFileParam(terraformProcessData);
        checkTerraformVariablesParam(terraformProcessData);
        return this.run(
                terraformProcessData,
                outputListener,
                errorListener,
                TerraformCommand.output);
    }

    public CompletableFuture<Boolean> output() throws IOException {
        this.checkRunningParameters();
        return this.run(TerraformCommand.output);
    }

    private CompletableFuture<Boolean> run(TerraformProcessData terraformProcessData, Consumer<String> outputListener, Consumer<String> errorListener, TerraformCommand... commands) throws IOException {
        assert commands.length > 0;
        ProcessLauncher[] launchers = new ProcessLauncher[commands.length];
        for (int i = 0; i < commands.length; i++) {
            launchers[i] = this.getTerraformLauncher(
                    terraformProcessData,
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

    private void checkVarFileParam(TerraformProcessData terraformProcessData) {
        if (terraformProcessData.getVarFileName() != null) {
            throw new IllegalArgumentException("varFile parameter should be null for this terraform command");
        }
    }

    private void checkTerraformVariablesParam(TerraformProcessData terraformProcessData) {
        if (!terraformProcessData.getTerraformVariables().isEmpty()) {
            throw new IllegalArgumentException("terraform variables parameter should be empty for this terraform command");
        }
    }

    private void checkBackendConfigFile(TerraformProcessData terraformProcessData) {
        if (terraformProcessData.getTerraformBackendConfigFileName() != null) {
            throw new IllegalArgumentException("terraform backend config file name should be null for this terraform command");
        }
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
        TerraformProcessData terraformProcessData = TerraformProcessData.builder()
                .terraformVersion(this.terraformVersion)
                .workingDirectory(this.workingDirectory)
                .terraformBackendConfigFileName(this.backendConfig)
                .varFileName(this.varFileName)
                .terraformVariables(this.terraformParameters)
                .terraformEnvironmentVariables(this.environmentVariables)
                .build();

        return getTerraformLauncher(terraformProcessData, this.outputListener, this.errorListener, command);
    }

    private ProcessLauncher getTerraformLauncher(TerraformProcessData terraformProcessData, Consumer<String> outputListener, Consumer<String> errorListener, TerraformCommand command) throws IOException {
        TerraformDownloader terraformDownloader = createTerraformDownloader();
        String terraformPath = terraformProcessData.isTofu() ? terraformDownloader.downloadTofuVersion(terraformProcessData.getTerraformVersion()) : terraformDownloader.downloadTerraformVersion(terraformProcessData.getTerraformVersion());

        if (terraformProcessData.sshFile != null && command.equals(TerraformCommand.init)) {
            return getTerraformInitWithSSH(terraformPath, terraformProcessData, outputListener, errorListener);
        }

        ProcessLauncher launcher = newLauncher(terraformProcessData, terraformPath, command.getLabel());

        launcher.setDirectory(terraformProcessData.getWorkingDirectory());
        launcher.setInheritIO(this.isInheritIO());

        if (terraformProcessData.getTerraformEnvironmentVariables() != null)
            for (Map.Entry<String, String> entry : terraformProcessData.getTerraformEnvironmentVariables().entrySet()) {
                launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        ComparableVersion version = new ComparableVersion(terraformProcessData.getTerraformVersion());

        if (!this.showColor)
            launcher.appendCommands(TERRAFORM_PARAM_NO_COLOR);

        //https://www.terraform.io/docs/internals/machine-readable-ui.html
        if (this.jsonOutput && version.compareTo(new ComparableVersion("0.15.2")) > 0)
            switch (command) {
                case plan:
                case apply:
                case planDestroy:
                case destroy:
                    launcher.appendCommands(TERRAFORM_PARAM_JSON);
                    break;
                default:
                    break;
            }

        switch (command) {
            case init:
                if (terraformProcessData.getTerraformBackendConfigFileName() != null) {
                    launcher.appendCommands(TERRAFORM_PARAM_BACKEND.concat(terraformProcessData.getTerraformBackendConfigFileName()));
                }
                launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);
                break;
            case planDestroy:
            case plan:
                if (!terraformProcessData.isRefresh()){
                    launcher.appendCommands(TERRAFORM_PLAN_REFRESH_FALSE);
                }

                if (terraformProcessData.isRefreshOnly()){
                    launcher.appendCommands(TERRAFORM_PLAN_REFRESH_ONLY);
                }

                if (terraformProcessData.getVarFileName() == null)
                    for (Map.Entry<String, String> entry : terraformProcessData.getTerraformVariables().entrySet()) {
                        launcher.appendCommands(TERRAFORM_PARAM_VARIABLE, entry.getKey().concat("=").concat(entry.getValue()));
                    }
                else {
                    log.info("Using plan with var file parameter");
                    launcher.appendCommands(TERRAFORM_PARAM_VARIABLE_FILE, terraformProcessData.getVarFileName());
                }
                launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN);
                launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);

                if (command.equals(TerraformCommand.planDestroy)) {
                    launcher.appendCommands(TERRAFORM_PARAM_PLAN_DESTROY);
                }

                if (terraformProcessData.isDetailExitCode()) {
                    launcher.appendCommands(TERRAFORM_PARAM_DETAIL_EXIT_CODE);
                }
                break;
            case apply:
                if (terraformProcessData.getVarFileName() == null) {
                    if (terraformProcessData.getTerraformVariables().entrySet().isEmpty()) {
                        launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                        launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);
                        launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                    } else {
                        for (Map.Entry<String, String> entry : terraformProcessData.getTerraformVariables().entrySet()) {
                            launcher.appendCommands(TERRAFORM_PARAM_VARIABLE, entry.getKey().concat("=").concat(entry.getValue()));
                        }
                        launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                        launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);
                    }
                } else {
                    log.info("Using apply with var file parameter");
                    launcher.appendCommands(TERRAFORM_PARAM_VARIABLE_FILE, terraformProcessData.getVarFileName());
                    launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);
                    launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);
                }

                break;
            case destroy:
                //https://www.terraform.io/upgrade-guides/0-15.html#other-minor-command-line-behavior-changes
                if (version.compareTo(new ComparableVersion("0.15.0")) < 0)
                    launcher.appendCommands(TERRAFORM_PARAM_FORCE);
                else
                    launcher.appendCommands(TERRAFORM_PARAM_AUTO_APPROVED);

                if (terraformProcessData.getVarFileName() == null) {
                    for (Map.Entry<String, String> entry : terraformProcessData.getTerraformVariables().entrySet()) {
                        launcher.appendCommands(TERRAFORM_PARAM_VARIABLE, entry.getKey().concat("=").concat(entry.getValue()));
                    }
                } else {
                    log.info("Using Destroy with var file parameter");
                    launcher.appendCommands(TERRAFORM_PARAM_VARIABLE_FILE, terraformProcessData.getVarFileName());
                }

                launcher.appendCommands(TERRAFORM_PARAM_DISABLE_USER_INPUT);
                break;
            case show:
            case output:
                launcher.appendCommands(TERRAFORM_PARAM_JSON);
                break;
            case showPlan:
                launcher.appendCommands(TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                break;
            case showPlanJson:
                launcher.appendCommands(TERRAFORM_PARAM_JSON, TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                break;
            case statePull:
                launcher.appendCommands(TF_STATE_PULL);
                break;
            default:
                break;
        }

        launcher.setOutputListener(outputListener);
        launcher.setErrorListener(errorListener);
        launcher.setRedirectErrorStream(this.redirectErrorStream);
        return launcher;
    }

    /**
     * Creates a launcher carrying the per-job timeout, which registers itself as
     * live once it has actually started a process so that {@link #close()} can
     * reach it.
     */
    ProcessLauncher newLauncher(TerraformProcessData terraformProcessData, String... commands) {
        ProcessLauncher launcher = new ProcessLauncher(this.executor, this::registerLiveLauncher, commands);
        launcher.setTimeoutSeconds(terraformProcessData.getTimeoutSeconds());
        return launcher;
    }

    private void registerLiveLauncher(ProcessLauncher launcher) {
        // Prune on the way in, so the set is bounded by the number of
        // concurrently running commands rather than by the number ever run.
        this.liveLaunchers.removeIf(ProcessLauncher::hasExited);
        this.liveLaunchers.add(launcher);
        // close() iterates a weakly consistent view, so a launcher registering
        // concurrently can be missed and then cleared away. Re-check after the
        // add and refuse: launch() kills the process it just started and
        // rethrows, rather than leaving it running with nothing tracking it.
        if (this.closed) {
            this.liveLaunchers.remove(launcher);
            throw new IllegalStateException("TerraformClient is closed");
        }
    }

    private ProcessLauncher getTerraformInitWithSSH(String terraformPath, TerraformProcessData terraformProcessData, Consumer<String> outputListener, Consumer<String> errorListener) {
        String initSSHCommand = String.format("GIT_SSH_COMMAND='ssh -i %s -o StrictHostKeyChecking=no' %s init", terraformProcessData.getSshFile().getAbsolutePath(), terraformPath);
        ProcessLauncher processLauncher = newLauncher(terraformProcessData, "bash", "-c");
        processLauncher.setInheritIO(this.isInheritIO());
        processLauncher.setDirectory(terraformProcessData.getWorkingDirectory());

        if (terraformProcessData.getTerraformEnvironmentVariables() != null)
            for (Map.Entry<String, String> entry : terraformProcessData.getTerraformEnvironmentVariables().entrySet()) {
                processLauncher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        if (!this.showColor)
            initSSHCommand = initSSHCommand.concat(" " + TERRAFORM_PARAM_NO_COLOR);

        if (terraformProcessData.getTerraformBackendConfigFileName() != null) {
            initSSHCommand = initSSHCommand.concat(" " + TERRAFORM_PARAM_BACKEND.concat(terraformProcessData.getTerraformBackendConfigFileName()));
        }
        initSSHCommand = initSSHCommand.concat(" " + TERRAFORM_PARAM_DISABLE_USER_INPUT);

        log.warn("Running terraform init with command {},", initSSHCommand);
        processLauncher.appendCommands(initSSHCommand);
        processLauncher.setOutputListener(outputListener);
        processLauncher.setErrorListener(errorListener);

        return processLauncher;
    }

    public TerraformDownloader createTerraformDownloader() {
        synchronized (this) {
            String TERRAFORM_RELEASES_URL = (this.terraformReleasesUrl != null && !terraformReleasesUrl.isEmpty()) ? this.terraformReleasesUrl : TerraformDownloader.TERRAFORM_RELEASES_URL;
            String TOFU_RELEASES_URL = (this.tofuReleasesUrl != null && !tofuReleasesUrl.isEmpty()) ? this.tofuReleasesUrl : TerraformDownloader.TOFU_RELEASES_URL;

            log.info("Creating terraform downloader using terraform release URL: {} and tofu release URL: {}", TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL);
            return new TerraformDownloader(TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL);
        }
    }

    @Override
    public void close() throws Exception {
        this.closed = true;
        // The processes have to be killed before the pool is shut down, because
        // shutting the pool down cannot release the reader tasks on its own:
        // they are blocked in BufferedReader.readLine() on the child's pipe, and
        // that read does not respond to Thread.interrupt() (JDK-8169565, open
        // since 2016). Only the child's death closes the write end and produces
        // the EOF that lets the readers return.
        // The terminations themselves are safe on an interrupted closing thread -
        // they run on ProcessLauncher's own threads and clear the flag per task.
        // What is not safe is awaitTermination() below, which throws instead of
        // waiting and would report a shutdown failure that did not happen. Clear
        // the flag for the duration; the caller's interruption is not ours to
        // swallow, so restore it on the way out.
        boolean wasInterrupted = Thread.interrupted();
        try {
            List<ProcessLauncher> orphans = ProcessLauncher.terminateAll(this.liveLaunchers, CLOSE_GRACE_PERIOD_SECONDS);
            this.liveLaunchers.clear();
            if (!orphans.isEmpty()) {
                log.warn("{} terraform process(es) survived termination on close and are now orphaned", orphans.size());
            }
            this.executor.shutdownNow();
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("executor did not terminate");
            }
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

