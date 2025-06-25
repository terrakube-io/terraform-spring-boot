package io.terrakube.terraform.sample;

import io.terrakube.terraform.TerraformClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;

@SpringBootApplication
public class SpringStarterSampleApp {
    public static void main(String[] args) {
        SpringApplication.run(SpringStarterSampleApp.class, args);
    }

    @Autowired
    private TerraformClient terraform;
}
