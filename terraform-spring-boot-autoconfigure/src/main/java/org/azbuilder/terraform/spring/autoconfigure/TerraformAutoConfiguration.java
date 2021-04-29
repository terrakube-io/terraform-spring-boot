package org.azbuilder.terraform.spring.autoconfigure;

import lombok.AllArgsConstructor;
import org.azbuilder.terraform.TerraformClient;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;

import java.io.File;

@AllArgsConstructor
@Configuration
@EnableConfigurationProperties(TerraformProperties.class)
@ConditionalOnMissingBean(TerraformClient.class)
public class TerraformAutoConfiguration {
    private TerraformProperties tfProperties;

    @Bean
    public TerraformClient terraformClient(TerraformProperties tfProperties) {
        if (tfProperties.getDirectory() == null) {
            return new TerraformClient();
        }else{
            return new TerraformClient(new File(tfProperties.getDirectory()));
        }
    }
}
