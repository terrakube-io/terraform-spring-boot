package org.azbuilder.terraform.spring.autoconfigure;

import org.azbuilder.terraform.TerraformClient;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.*;
import org.springframework.context.annotation.*;

import org.azbuilder.terraform.*;

@Configuration
@EnableConfigurationProperties(TerraformProperties.class)
@ConditionalOnMissingBean(TerraformClient.class)
public class TerraformAutoConfiguration {
    private TerraformProperties tfProperties;

    public TerraformAutoConfiguration(TerraformProperties properties) {
        assert properties != null;
        this.tfProperties = properties;
    }

    @Bean
    public TerraformClient terraformClient() {
        return new TerraformClient();
    }
}
