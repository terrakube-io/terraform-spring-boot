package io.terrakube.terraform;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
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

    private static final String TERRAGRUNT_PARAM_NON_INTERACTIVE = "--terragrunt-non-interactive";
    private static final String TERRAGRUNT_PARAM_TFPATH = "--terragrunt-tfpath";

    private final ExecutorService executor = Executors.newWorkStealingPool();

    /**
     * Lazily-initialized, shared {@link TerraformDownloader} instance.
     * Constructed once on first use and reused for all subsequent commands on
     * this client, preventing the 3 HTTP release-manifest fetches that were
     * otherwise issued on every single launcher call (init, plan, apply, …).
     * Guarded by {@code synchronized(this)} via {@link #createTerraformDownloader()}.
     */
    private volatile TerraformDownloader cachedDownloader;

    private File workingDirectory;
    private boolean inheritIO;
    private boolean showColor;
    private boolean jsonOutput;
    private boolean redirectErrorStream;
    private String terraformVersion;
    private String backendConfig;
    private String terraformReleasesUrl;
    private String tofuReleasesUrl;
    private String terragruntReleasesUrl;

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
        syncTerragruntPlanFileBeforeOperation(terraformProcessData);
        return this.getTerraformLauncher(
                terraformProcessData,
                outputListener,
                errorListener, TerraformCommand.plan).launch().thenApply(exitCode -> {
            // With -detailed-exitcode: 0 = no changes, 2 = changes present; both are plan successes.
            // 1 = error — do not sync partial/absent plan and lock files in that case.
            if (exitCode == 0 || exitCode == 2) {
                syncTerragruntPlanFileAfterPlan(terraformProcessData, true);
            }
            return exitCode;
        });
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
        syncTerragruntPlanFileBeforeOperation(terraformProcessData);
        return this.getTerraformLauncher(
                terraformProcessData,
                outputListener,
                errorListener, TerraformCommand.planDestroy).launch().thenApply(exitCode -> {
            // With -detailed-exitcode: 0 = no changes, 2 = changes present; both are plan successes.
            // 1 = error — do not sync partial/absent plan and lock files in that case.
            if (exitCode == 0 || exitCode == 2) {
                syncTerragruntPlanFileAfterPlan(terraformProcessData, true);
            }
            return exitCode;
        });
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
        syncTerragruntPlanFileBeforeOperation(terraformProcessData);
        ProcessLauncher[] launchers = new ProcessLauncher[commands.length];
        for (int i = 0; i < commands.length; i++) {
            launchers[i] = this.getTerraformLauncher(
                    terraformProcessData,
                    outputListener,
                    errorListener, commands[i]);
        }

        // Do NOT sync the lock file to the working directory when the only command is
        // init.  Copying the lock file mid-session changes the working directory
        // content, which causes Terragrunt 1.0.x to compute a different module hash
        // for the subsequent plan call and route it to a new, uninitialized cache
        // directory — leading to a provider checksum mismatch.
        // The lock file flows cache → workDir only after plan/apply/destroy so it is
        // available for source control without disrupting the ongoing session.
        boolean syncLockFile = !(commands.length == 1 && commands[0] == TerraformCommand.init);

        return getLauncherResult(launchers, commands).thenApply(success -> {
            // Only sync plan/lock files when the operation succeeded; a failed init or plan
            // may leave the cache in a partial state and we should not propagate that.
            if (success) {
                syncTerragruntPlanFileAfterPlan(terraformProcessData, syncLockFile);
            }
            return success;
        });
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
            throw new IllegalArgumentException("terraformVariables parameter should be empty for this terraform command");
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
        // Validate terragrunt-specific required field before any download attempt
        // so the caller gets a meaningful error instead of an NPE deep in semver parsing.
        if (terraformProcessData.isTerragrunt() &&
                (terraformProcessData.getTerragruntVersion() == null || terraformProcessData.getTerragruntVersion().isBlank())) {
            throw new IllegalArgumentException("terragruntVersion must not be null or blank when terragrunt=true");
        }

        TerraformDownloader terraformDownloader = createTerraformDownloader();
        String executablePath;
        String enginePath = null;

        if (terraformProcessData.isTerragrunt()) {
            enginePath = terraformProcessData.isTofu() ? terraformDownloader.downloadTofuVersion(terraformProcessData.getTerraformVersion()) : terraformDownloader.downloadTerraformVersion(terraformProcessData.getTerraformVersion());
            executablePath = terraformDownloader.downloadTerragruntVersion(terraformProcessData.getTerragruntVersion());
        } else {
            executablePath = terraformProcessData.isTofu() ? terraformDownloader.downloadTofuVersion(terraformProcessData.getTerraformVersion()) : terraformDownloader.downloadTerraformVersion(terraformProcessData.getTerraformVersion());
        }

        if (terraformProcessData.sshFile != null && command.equals(TerraformCommand.init)) {
            return getTerraformInitWithSSH(executablePath, terraformProcessData, outputListener, errorListener, enginePath);
        }

        ProcessLauncher launcher = new ProcessLauncher(this.executor, executablePath, command.getLabel());

        launcher.setDirectory(terraformProcessData.getWorkingDirectory());
        launcher.setInheritIO(this.isInheritIO());

        if (terraformProcessData.getTerraformEnvironmentVariables() != null)
            for (Map.Entry<String, String> entry : terraformProcessData.getTerraformEnvironmentVariables().entrySet()) {
                launcher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        if (terraformProcessData.isTerragrunt()) {
            launcher.setEnvironmentVariable("TERRAGRUNT_NON_INTERACTIVE", "true");
            launcher.setEnvironmentVariable("TG_NON_INTERACTIVE", "true");
            launcher.setEnvironmentVariable("TERRAGRUNT_FORWARD_TF_STDOUT", "true");
            launcher.setEnvironmentVariable("TG_TF_FORWARD_STDOUT", "true");
            if (enginePath != null) {
                launcher.setEnvironmentVariable("TERRAGRUNT_TFPATH", enginePath);
                launcher.setEnvironmentVariable("TG_TF_PATH", enginePath);
            }
        }

        ComparableVersion version = new ComparableVersion(terraformProcessData.getTerraformVersion());

        if (!this.showColor)
            launcher.appendCommands(TERRAFORM_PARAM_NO_COLOR);

        //https://www.terraform.io/docs/internals/machine-readable-ui.html
        // JSON output is intentionally disabled when running via Terragrunt:
        // Terragrunt 0.50+ wraps Terraform's output in its own log lines, so the
        // combined stdout is not valid Terraform JSON and breaks downstream parsers.
        if (this.jsonOutput && !terraformProcessData.isTerragrunt() && version.compareTo(new ComparableVersion("0.15.2")) > 0)
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

    private ProcessLauncher getTerraformInitWithSSH(String terraformPath, TerraformProcessData terraformProcessData, Consumer<String> outputListener, Consumer<String> errorListener, String enginePath) {
        String initSSHCommand = String.format("GIT_SSH_COMMAND='ssh -i %s -o StrictHostKeyChecking=no' %s init", terraformProcessData.getSshFile().getAbsolutePath(), terraformPath);
        ProcessLauncher processLauncher = new ProcessLauncher(this.executor, "bash", "-c");
        processLauncher.setInheritIO(this.isInheritIO());
        processLauncher.setDirectory(terraformProcessData.getWorkingDirectory());

        if (terraformProcessData.getTerraformEnvironmentVariables() != null)
            for (Map.Entry<String, String> entry : terraformProcessData.getTerraformEnvironmentVariables().entrySet()) {
                processLauncher.setEnvironmentVariable(entry.getKey(), entry.getValue());
            }

        if (terraformProcessData.isTerragrunt()) {
            processLauncher.setEnvironmentVariable("TERRAGRUNT_NON_INTERACTIVE", "true");
            processLauncher.setEnvironmentVariable("TG_NON_INTERACTIVE", "true");
            processLauncher.setEnvironmentVariable("TERRAGRUNT_FORWARD_TF_STDOUT", "true");
            processLauncher.setEnvironmentVariable("TG_TF_FORWARD_STDOUT", "true");
            if (enginePath != null) {
                processLauncher.setEnvironmentVariable("TERRAGRUNT_TFPATH", enginePath);
                processLauncher.setEnvironmentVariable("TG_TF_PATH", enginePath);
            }
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

    private void syncTerragruntPlanFileBeforeOperation(TerraformProcessData terraformProcessData) {
        if (terraformProcessData != null && terraformProcessData.isTerragrunt() && terraformProcessData.getWorkingDirectory() != null) {
            File tgCache = new File(terraformProcessData.getWorkingDirectory(), ".terragrunt-cache");
            if (tgCache.exists() && tgCache.isDirectory()) {
                // Only sync the plan file back into the cache (e.g. for apply after plan).
                // The .terraform.lock.hcl must NEVER be pushed back into the cache: it is
                // authoritative only as output from `terragrunt init` (cache → workDir).
                // Copying a stale lock file from workDir into the cache overwrites the fresh
                // one generated by init and causes checksum mismatches during plan.
                File planInWorkDir = new File(terraformProcessData.getWorkingDirectory(), TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                if (planInWorkDir.exists() && planInWorkDir.isFile()) {
                    copyFileToTerragruntCache(tgCache, planInWorkDir, TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                }
            }
        }
    }

    private void copyFileToTerragruntCache(File currentDir, File sourceFile, String targetFileName) {
        File[] files = currentDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory() && !file.getName().startsWith(".")) {
                    // Skip hidden directories (e.g. .terraform, .git) to avoid writing the
                    // plan file into provider plugin directories.  Terraform computes the h1:
                    // lock-file hash as a dirhash over the provider binary directory; any
                    // extra file placed there (like terraformLibrary.tfPlan) changes that hash
                    // and causes "cached package does not match checksums" on the next plan.
                    try {
                        FileUtils.copyFile(sourceFile, new File(file, targetFileName));
                    } catch (IOException e) {
                        log.warn("Could not copy file {} to terragrunt cache dir {}: {}", targetFileName, file.getAbsolutePath(), e.getMessage());
                    }
                    copyFileToTerragruntCache(file, sourceFile, targetFileName);
                }
            }
        }
    }

    private void syncTerragruntPlanFileAfterPlan(TerraformProcessData terraformProcessData) {
        syncTerragruntPlanFileAfterPlan(terraformProcessData, true);
    }

    /**
     * Copies Terragrunt-managed output files from the cache back to the working directory.
     *
     * @param syncLockFile when {@code true} the {@code .terraform.lock.hcl} is also copied;
     *                     pass {@code false} for init-only runs to avoid changing the working
     *                     directory content mid-session (which would shift Terragrunt’s module
     *                     hash and route the next plan to a different, uninitialized cache dir).
     */
    private void syncTerragruntPlanFileAfterPlan(TerraformProcessData terraformProcessData, boolean syncLockFile) {
        if (terraformProcessData != null && terraformProcessData.isTerragrunt() && terraformProcessData.getWorkingDirectory() != null) {
            File tgCache = new File(terraformProcessData.getWorkingDirectory(), ".terragrunt-cache");
            if (tgCache.exists() && tgCache.isDirectory()) {
                File planInWorkDir = new File(terraformProcessData.getWorkingDirectory(), TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                if (!planInWorkDir.exists()) {
                    File foundPlan = findFileInTerragruntCache(tgCache, TERRAFORM_PARAM_OUTPUT_PLAN_FILE);
                    if (foundPlan != null) {
                        try {
                            FileUtils.copyFile(foundPlan, planInWorkDir);
                            log.info("Copied Terragrunt plan file from {} to {}", foundPlan.getAbsolutePath(), planInWorkDir.getAbsolutePath());
                        } catch (IOException e) {
                            log.error("Failed to copy Terragrunt plan file to working directory: {}", e.getMessage());
                        }
                    }
                }
                if (syncLockFile) {
                    File lockInWorkDir = new File(terraformProcessData.getWorkingDirectory(), ".terraform.lock.hcl");
                    File foundLock = findFileInTerragruntCache(tgCache, ".terraform.lock.hcl");
                    if (foundLock != null) {
                        try {
                            FileUtils.copyFile(foundLock, lockInWorkDir);
                            log.info("Copied Terragrunt lock file from {} to {}", foundLock.getAbsolutePath(), lockInWorkDir.getAbsolutePath());
                        } catch (IOException e) {
                            log.warn("Failed to copy Terragrunt lock file to working directory: {}", e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private File findFileInTerragruntCache(File dir, String fileName) {
        File targetFile = new File(dir, fileName);
        if (targetFile.exists() && targetFile.isFile()) {
            return targetFile;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File found = findFileInTerragruntCache(child, fileName);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    public TerraformDownloader createTerraformDownloader() {
        // Double-checked locking: avoid re-fetching all three release manifests on every
        // command.  The downloader is stateless after construction (all releases are loaded
        // eagerly in the constructor), so it is safe to share across threads.
        if (this.cachedDownloader == null) {
            synchronized (this) {
                if (this.cachedDownloader == null) {
                    String TERRAFORM_RELEASES_URL = (this.terraformReleasesUrl != null && !terraformReleasesUrl.isEmpty()) ? this.terraformReleasesUrl : TerraformDownloader.TERRAFORM_RELEASES_URL;
                    String TOFU_RELEASES_URL = (this.tofuReleasesUrl != null && !tofuReleasesUrl.isEmpty()) ? this.tofuReleasesUrl : TerraformDownloader.TOFU_RELEASES_URL;
                    String TERRAGRUNT_RELEASES_URL = (this.terragruntReleasesUrl != null && !terragruntReleasesUrl.isEmpty()) ? this.terragruntReleasesUrl : TerraformDownloader.TERRAGRUNT_RELEASES_URL;

                    log.info("Creating terraform downloader using terraform release URL: {}, tofu release URL: {}, terragrunt release URL: {}", TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL, TERRAGRUNT_RELEASES_URL);
                    this.cachedDownloader = new TerraformDownloader(TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL, TERRAGRUNT_RELEASES_URL);
                }
            }
        }
        return this.cachedDownloader;
    }

    @Override
    public void close() throws Exception {
        this.executor.shutdownNow();
        if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("executor did not terminate");
        }
    }
}

