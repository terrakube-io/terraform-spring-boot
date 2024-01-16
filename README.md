# Terraform Spring Boot
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_terraform-spring-boot&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=AzBuilder_terraform-spring-boot)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_terraform-spring-boot&metric=security_rating)](https://sonarcloud.io/dashboard?id=AzBuilder_terraform-spring-boot)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_terraform-spring-boot&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=AzBuilder_terraform-spring-boot)

## Introduction

This repository is for Spring Boot Starters of Terraform client.

## How to use it

There are two ways you could use this library. One way is to directly use the `TerraformClient.builder` class which wraps `terraform` and download any require terraform version to you local machine; and the other way is to integrate it into a Spring boot application using annotations.

### Build library

To build the library locally use the following command.

```
mvn install -Dgpg.skip
```

### Client library

Simply add the following dependency to your project's `pom.xml` will enable you to use the `TerraformClient` class.

```xml
<dependency>
    <groupId>org.terrakube.terraform</groupId>
    <artifactId>terraform-client</artifactId>
    <version>0.10.1</version>
</dependency>
```

And now you are able to provision terraform resources in your Java application. Make sure you have already put a terraform file `storage.tf` under `/some/local/path/` folder; and then use the Java code snippet below to invoke `terraform` executable operate on the resources defined in `storage.tf`. In this example, we also assume that you are provisioning Azure specific resource, which means you need to set some Azure related credentials using environments variables.

```java
public class Main {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        HashMap<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("ARM_SUBSCRIPTION_ID", "<Azure Subscription ID>");
        environmentVariables.put("ARM_CLIENT_ID", "<Azure Client ID>");
        environmentVariables.put("ARM_CLIENT_SECRET", "<Azure Client Secret>");
        environmentVariables.put("ARM_TENANT_ID", "<Azure Tenant ID>");

        try {
            TerraformClient client = TerraformClient
                    .builder()
                    .environmentVariables(environmentVariables)
                    .terraformVersion("0.15.1")
                    .errorListener(System.err::println)
                    .outputListener(System.out::println)
                    .build();

            client.setWorkingDirectory(new File("/some/local/path/"));
            client.plan().get();
            client.apply().get();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Terraform Client Downloader library

The `TerraformDownloader` class can download multiple terraform versions. These will be saved inside (UserHomeDirectory)/.terraform-spring-boot. 
You can also define terraform version using `TerraformClient.builder`. That particular version will be downloaded automatically when using the client. 

Example /home/user/.terraform-spring-boot

```java
public class Main {
    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        TerraformDownloader terraformDownloader = new TerraformDownloader();
        terraformDownloader.downloadTerraformVersion("0.15.0"));

        TerraformClient client = TerraformClient.builder()
            .environmentVariables(environmentVariables)
            .terraformVersion("0.15.1")
            .errorListener(System.err::println)
            .outputListener(System.out::println)
            .build();
    }
}
```

### Spring boot

Let's still use the terraform file `storage.tf` under `/some/local/path/` folder to provision Azure resources in this example. Rather than create the `TerraformClient` by ourselves, we let the spring boot framework to wire it for us. First add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.terrakube.terraform</groupId>
    <artifactId>terraform-spring-boot-starter</artifactId>
    <version>0.9.0</version>
</dependency>
```

You can also enable or disable terraform output colors using the `application.properties` or `application-${spring.profiles.active}.properties`:

```
org.terrakube.terraform.flags.enableColor=true
```

You can also enable or disable terraform output in json format using the `application.properties` or `application-${spring.profiles.active}.properties`:

```
org.terrakube.terraform.flags.jsonOutput=true
```

> This feature is only supported in terraform >= 0.15.3

The final step is to let the Spring framework wire up everything in your spring boot application:

Example 1: `Not thread safe`
```java
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
            this.terraform.setOutputListener(System.out::println);
            this.terraform.setErrorListener(System.err::println);

            this.terraform.setTerraformVersion("0.15.0");
            this.terraform.setWorkingDirectory("/some/local/path/");
            this.terraform.plan().get();
            this.terraform.apply().get();
        } finally {
            this.terraform.close();
        }
    }
}
```

Example 2: `Thread safe`
```java
@SpringBootApplication
public class SpringStarterSampleApp implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(SpringStarterSampleApp.class, args);
    }

    @Autowired
    private TerraformClient terraform;

    @Override
    public void run(String... args) throws Exception {
        TextStringBuilder terraformOutput = new TextStringBuilder();
        TextStringBuilder terraformErrorOutput = new TextStringBuilder();
        try {
            Consumer<String> output = responseOutput -> terraformOutput.appendln(responseOutput);
            Consumer<String> errorOutput = responseError -> terraformErrorOutput.appendln(responseError);

            String terraformVersion="0.15.1";
            File workingDirectory = new File("/some/path");
            String backendStateFile = "";

            HashMap<String, String> terraformParameters = new HashMap<>();
            terraformParameters.put("tag_name","Hello World!");

            HashMap<String, String> environmentVariables = new HashMap<>();
            environmentVariables.put("ARM_SUBSCRIPTION_ID","<Azure Subscription ID>");
            environmentVariables.put("ARM_CLIENT_ID","<Azure Client ID>");
            environmentVariables.put("ARM_CLIENT_SECRET","<Azure Client Secret>");
            environmentVariables.put("ARM_TENANT_ID","<Azure Tenant ID>");

            TerraformProcessData terraformProcessData = TerraformProcessData.builder()
                    .terraformVersion(terraformVersion)
                    .workingDirectory(workingDirectory)
                    .terraformVariables(terraformParameters)
                    .terraformEnvironmentVariables(environmentVariables)
                    .build();

            boolean execution = terraformClient.plan(
                    terraformProcessData,
                    output,
                    errorOutput).get();

        } catch (IOException | ExecutionException | InterruptedException exception) {
            exception.printStackTrace();
        }
    }
}
```

### OpenTofu Support

When using with opentofu you need to use the terraformProcessData like the following:

```java
        TerraformProcessData terraformProcessData = TerraformProcessData.builder()
                .terraformVersion("1.6.0")
                .workingDirectory(new File("/some/terraform/path"))
                .tofu(true)
                .build();
```

### Custom Terraform Releases URL

You can customize the URL from where you download your terraform binary.

Use terraform releases url field in the builder:

```java
package org.terrakube.terraform;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;

public final class Main {

    public static void main(String[] args) {
        String terraformVersion = "1.3.9";
        TerraformClient terraformClient = TerraformClient
                .builder()
                .environmentVariables(new HashMap<>())
                .terraformParameters(new HashMap<>())
                .terraformVersion(terraformVersion)
                .jsonOutput(true)
                .showColor(false)
                .errorListener(System.err::println)
                .outputListener(System.out::println)
                .terraformReleasesUrl("https://eov1ys4sxa1bfy9.m.pipedream.net/")
                .build();
    }
}
```

> This can be usefull when you would like to use some custom terraform build or if you have some network restrictions.

Your endpoint should expose with the following data:
```json
{
   "name":"terraform",
   "versions":{
      "1.3.9":{
         "builds":[
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_darwin_amd64.zip",
               "name":"terraform",
               "os":"darwin",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_darwin_amd64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"arm64",
               "filename":"terraform_1.3.9_darwin_arm64.zip",
               "name":"terraform",
               "os":"darwin",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_darwin_arm64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"386",
               "filename":"terraform_1.3.9_freebsd_386.zip",
               "name":"terraform",
               "os":"freebsd",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_freebsd_386.zip",
               "version":"1.3.9"
            },
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_freebsd_amd64.zip",
               "name":"terraform",
               "os":"freebsd",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_freebsd_amd64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"arm",
               "filename":"terraform_1.3.9_freebsd_arm.zip",
               "name":"terraform",
               "os":"freebsd",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_freebsd_arm.zip",
               "version":"1.3.9"
            },
            {
               "arch":"386",
               "filename":"terraform_1.3.9_linux_386.zip",
               "name":"terraform",
               "os":"linux",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_linux_386.zip",
               "version":"1.3.9"
            },
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_linux_amd64.zip",
               "name":"terraform",
               "os":"linux",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_linux_amd64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"arm",
               "filename":"terraform_1.3.9_linux_arm.zip",
               "name":"terraform",
               "os":"linux",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_linux_arm.zip",
               "version":"1.3.9"
            },
            {
               "arch":"arm64",
               "filename":"terraform_1.3.9_linux_arm64.zip",
               "name":"terraform",
               "os":"linux",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_linux_arm64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"386",
               "filename":"terraform_1.3.9_openbsd_386.zip",
               "name":"terraform",
               "os":"openbsd",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_openbsd_386.zip",
               "version":"1.3.9"
            },
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_openbsd_amd64.zip",
               "name":"terraform",
               "os":"openbsd",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_openbsd_amd64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_solaris_amd64.zip",
               "name":"terraform",
               "os":"solaris",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_solaris_amd64.zip",
               "version":"1.3.9"
            },
            {
               "arch":"386",
               "filename":"terraform_1.3.9_windows_386.zip",
               "name":"terraform",
               "os":"windows",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_windows_386.zip",
               "version":"1.3.9"
            },
            {
               "arch":"amd64",
               "filename":"terraform_1.3.9_windows_amd64.zip",
               "name":"terraform",
               "os":"windows",
               "url":"https://releases.hashicorp.com/terraform/1.3.9/terraform_1.3.9_windows_amd64.zip",
               "version":"1.3.9"
            }
         ],
         "name":"terraform",
         "shasums":"terraform_1.3.9_SHA256SUMS",
         "shasums_signature":"terraform_1.3.9_SHA256SUMS.sig",
         "shasums_signatures":[
            "terraform_1.3.9_SHA256SUMS.72D7468F.sig",
            "terraform_1.3.9_SHA256SUMS.sig"
         ],
         "version":"1.3.9"
      }
   }
}
```