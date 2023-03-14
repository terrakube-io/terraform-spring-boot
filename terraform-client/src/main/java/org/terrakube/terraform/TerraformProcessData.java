package org.terrakube.terraform;

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
    @Singular Map<String, String> terraformVariables;
    @Singular Map<String, String> terraformEnvironmentVariables;
}
