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
     * seconds. Zero (the default) disables the timeout entirely, and must stay
     * zero: a non-zero default would start killing the long-running applies of
     * consumers that never asked for a timeout.
     *
     * <p>That makes the <em>behaviour</em> of an upgrade compatible, not the
     * linkage. Adding this field changed the generated public all-args
     * constructor from 11 parameters to 12, so the old signature no longer
     * exists: code compiled against an earlier version that calls the
     * constructor directly fails with {@code NoSuchMethodError} on a drop-in jar
     * upgrade. The builder is unaffected.
     */
    @Builder.Default
    long timeoutSeconds = 0;
    @Singular Map<String, String> terraformVariables;
    @Singular Map<String, String> terraformEnvironmentVariables;
}
