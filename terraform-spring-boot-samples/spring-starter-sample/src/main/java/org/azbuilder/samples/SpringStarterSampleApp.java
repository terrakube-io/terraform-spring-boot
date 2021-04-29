package org.azbuilder.samples;

import org.azbuilder.terraform.TerraformClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;

@SpringBootApplication
public class SpringStarterSampleApp implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(SpringStarterSampleApp.class, args);
    }

    @Autowired
    private TerraformClient terraform;

    @Override
    public void run(String... args) throws Exception {
        try {
            System.out.println(this.terraform.version().get());
        } finally {
            this.terraform.close();
        }
    }
}
