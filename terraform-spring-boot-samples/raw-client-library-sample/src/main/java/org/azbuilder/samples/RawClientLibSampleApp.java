package org.azbuilder.samples;

import java.io.*;
import java.util.*;

import org.azbuilder.terraform.TerraformClient;
import org.azbuilder.terraform.TerraformDownloader;

public final class RawClientLibSampleApp {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Working folder path argument missing");
            System.exit(1);
        }

        TerraformDownloader terraformDownloader = new TerraformDownloader();
        System.out.println(terraformDownloader.downloadTerraformVersion("0.15.0"));
        System.out.println(terraformDownloader.downloadTerraformVersion("0.14.9"));

        TerraformClient terraformClient = new TerraformClient();

        terraformClient.setTerraformVersion("0.15.0");
        System.out.println(terraformClient.version().get());

        terraformClient.setTerraformVersion("0.14.9");
        System.out.println(terraformClient.version().get());
        terraformClient.setTerraformVersion("0.14.7");
        System.out.println(terraformClient.version().get());

        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("ARM_SUBSCRIPTION_ID","<Azure Subscription ID>");
        environmentVariables.put("ARM_CLIENT_ID","<Azure Client ID>");
        environmentVariables.put("ARM_CLIENT_SECRET","<Azure Client Secret>");
        environmentVariables.put("ARM_TENANT_ID","<Azure Tenant ID>");

        HashMap<String, String> terraformParameters = new HashMap<>();

        try (TerraformClient client = TerraformClient.builder().terraformParameters(terraformParameters).environmentVariables(environmentVariables).build()) {

            System.out.println(client.version().get());
            client.setOutputListener(System.out::println);
            client.setErrorListener(System.err::println);

            client.setWorkingDirectory(new File(args[0]));

            Scanner input = new Scanner(System.in);
            System.out.print("Enter 'Y' to plan: ");
            if (!input.next().equalsIgnoreCase("Y")) {
                return;
            }
            System.out.println(client.plan().get());
            System.out.print("Enter 'Y' to apply: ");
            if (!input.next().equalsIgnoreCase("Y")) {
                return;
            }
            System.out.println(client.apply().get());
            System.out.print("Enter 'Y' to destroy: ");
            if (!input.next().equalsIgnoreCase("Y")) {
                return;
            }
            System.out.println(client.destroy().get());
        }
    }
}
