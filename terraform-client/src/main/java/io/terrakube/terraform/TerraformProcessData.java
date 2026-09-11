package io.terrakube.terraform;

import lombok.*;

import java.io.File;
import java.util.Map;

@AllArgsConstructor
@Getter
@Setter
@Builder
public class TerraformProcessData {
    @NonNull String terraformVersion;
    @NonNull File workingDirectory;
    String terraformBackendConfigFileName;
    String varFileName;
    File sshFile;
    @Builder.Default
    boolean refresh = true;
    @Builder.Default
    boolean refreshOnly = false;
    @Builder.Default
    boolean tofu = false;
    @Builder.Default
    boolean detailExitCode = false;
    /**
     * Maximum wall-clock time the terraform process is allowed to run, in
     * seconds. Zero (the default) disables the timeout entirely; it must stay
     * zero so that a patch upgrade of this library never starts killing the
     * long-running applies of consumers that do not opt in.
     */
    @Builder.Default
    long timeoutSeconds = 0;
    @Singular Map<String, String> terraformVariables;
    @Singular Map<String, String> terraformEnvironmentVariables;
}
