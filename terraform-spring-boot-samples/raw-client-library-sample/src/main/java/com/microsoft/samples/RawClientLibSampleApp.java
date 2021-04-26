package com.microsoft.samples;

import java.io.*;
import java.util.*;

import org.azbuilder.terraform.TerraformClient;

public final class RawClientLibSampleApp {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Working folder path argument missing");
            System.exit(1);
        }
        HashMap<String, String> options = new HashMap<>();
        options.put("ARM_SUBSCRIPTION_ID","<Azure Subscription ID>");
        options.put("ARM_CLIENT_ID","<Azure Client ID>");
        options.put("ARM_CLIENT_SECRET","<Azure Client Secret>");
        options.put("ARM_TENANT_ID","<Azure Tenant ID>");
        try (TerraformClient client = new TerraformClient(options)) {
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
