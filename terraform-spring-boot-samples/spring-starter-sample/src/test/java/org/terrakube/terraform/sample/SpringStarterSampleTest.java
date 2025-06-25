package io.terrakube.terraform.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.terrakube.terraform.TerraformClient;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class SpringStarterSampleTest {

    @Autowired
    TerraformClient terraformClient;

    @Test
    void contextLoads() throws IOException, ExecutionException, InterruptedException {
        assertNotNull(terraformClient,"TerraformClient is null");
    }
}
