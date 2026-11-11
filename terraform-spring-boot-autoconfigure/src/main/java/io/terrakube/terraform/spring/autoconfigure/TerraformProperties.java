package io.terrakube.terraform.spring.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@Getter
@Setter
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:application-${spring.profiles.active}.properties", ignoreResourceNotFound = true)
@ConfigurationProperties(prefix = "io.terrakube.terraform.flags")
public class TerraformProperties {

    private boolean enableColor;
    private boolean jsonOutput;
    private String terraformReleasesUrl;
    private String tofuReleasesUrl;
    // Same defaults as TerraformDownloader.DEFAULT_*. Not referenced directly so that loading these
    // properties does not load TerraformDownloader, which needs webflux on the classpath.
    // Max idle time between reads while downloading the releases list or a binary, e.g. 60s.
    private Duration downloadResponseTimeout = Duration.ofSeconds(60);
    // Max time to download the terraform/tofu releases list, e.g. 2m.
    private Duration releasesDownloadTimeout = Duration.ofMinutes(2);
    // Max time to download a terraform/tofu binary archive, e.g. 10m.
    private Duration binaryDownloadTimeout = Duration.ofMinutes(10);
}
