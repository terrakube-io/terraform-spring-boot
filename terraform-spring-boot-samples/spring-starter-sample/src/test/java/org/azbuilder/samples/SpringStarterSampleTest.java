package org.azbuilder.samples;

import org.azbuilder.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
        //TerraformDownloader terraformDownloader = new TerraformDownloader();
        //terraformDownloader.downloadTerraformVersion("0.15.0");
    }
}
