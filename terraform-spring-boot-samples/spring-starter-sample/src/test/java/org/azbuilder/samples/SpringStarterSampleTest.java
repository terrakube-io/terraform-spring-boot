package org.azbuilder.samples;

import org.azbuilder.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

@SpringBootTest
public class SpringStarterSampleTest {

    @Autowired
    TerraformClient terraformClient;

    @Test
    void contextLoads() throws IOException, ExecutionException, InterruptedException {
        Assert.notNull(terraformClient,"TerraformClient is null");
    }
}
