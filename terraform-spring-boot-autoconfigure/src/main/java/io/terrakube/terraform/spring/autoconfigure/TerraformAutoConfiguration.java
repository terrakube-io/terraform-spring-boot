package io.terrakube.terraform.spring.autoconfigure;

import lombok.NonNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import io.terrakube.terraform.TerraformClient;

@AutoConfiguration
@EnableConfigurationProperties(TerraformProperties.class)
@ConditionalOnMissingBean(TerraformClient.class)
public class TerraformAutoConfiguration {

    @Bean
    public TerraformClient terraformClient(@NonNull TerraformProperties tfProperties) {
            java.util.Set<String> inheritedEnv = tfProperties.getInheritedEnvironmentVariables();
            if (inheritedEnv != null && (inheritedEnv.isEmpty() || inheritedEnv.stream().allMatch(String::isBlank))) {
                inheritedEnv = null;
            }

            return TerraformClient.builder()
                    .showColor(tfProperties.isEnableColor())
                    .jsonOutput(tfProperties.isJsonOutput())
                    .terraformReleasesUrl(tfProperties.getTerraformReleasesUrl())
                    .tofuReleasesUrl(tfProperties.getTofuReleasesUrl())
                    .pidIsolation(tfProperties.getPidIsolation())
                    .cleanEnvironment(tfProperties.isCleanEnvironment())
                    .inheritedEnvironmentVariables(inheritedEnv)
                    .build();
    }
}
