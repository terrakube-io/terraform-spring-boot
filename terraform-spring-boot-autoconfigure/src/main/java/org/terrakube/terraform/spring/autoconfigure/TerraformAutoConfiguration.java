package org.terrakube.terraform.spring.autoconfigure;

import lombok.NonNull;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;
import org.terrakube.terraform.TerraformClient;

@Configuration
@EnableConfigurationProperties(TerraformProperties.class)
@ConditionalOnMissingBean(TerraformClient.class)
public class TerraformAutoConfiguration {

    @Bean
    public TerraformClient terraformClient(@NonNull TerraformProperties tfProperties) {

            return TerraformClient.builder()
                    .showColor(tfProperties.isEnableColor())
                    .jsonOutput(tfProperties.isJsonOutput())
                    .terraformReleasesUrl(tfProperties.getTerraformReleasesUrl())
                    .build();
    }
}
