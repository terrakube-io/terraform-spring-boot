package org.azbuilder.samples;

import java.io.*;
import java.util.*;

import org.azbuilder.terraform.TerraformClient;

public final class RawClientLibSampleApp {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Working folder path argument missing");
            System.exit(1);
        }
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("ARM_SUBSCRIPTION_ID","<Azure Subscription ID>");
        environmentVariables.put("ARM_CLIENT_ID","<Azure Client ID>");
        environmentVariables.put("ARM_CLIENT_SECRET","<Azure Client Secret>");
        environmentVariables.put("ARM_TENANT_ID","<Azure Tenant ID>");

        HashMap<String, String> terraformParameters = new HashMap<>();
        try (TerraformClient client = new TerraformClient(terraformParameters, environmentVariables)) {
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
