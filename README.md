
# Terraform Spring Boot

## Introduction

This repository is for Spring Boot Starters of Terraform client.

## How to use it

There are two ways you could use this library. One way is to directly use the `TerraformClient` class which wraps the `terraform` executable on your local machine; and the other way is to integrate it into a Spring boot application using annotations.

### Client library

Simply add the following dependency to your project's `pom.xml` will enable you to use the `TerraformClient` class.

```xml
<dependency>
    <groupId>org.azbuilder.terraform</groupId>
    <artifactId>terraform-client</artifactId>
    <version>0.0.1</version>
</dependency>
```

And now you are able to provision terraform resources in your Java application. Make sure you have already put a terraform file `storage.tf` under `/some/local/path/` folder; and then use the Java code snippet below to invoke `terraform` executable operate on the resources defined in `storage.tf`. In this example, we also assume that you are provisioning Azure specific resource, which means you need to set some Azure related credentials.

```java
HashMap<String, String> options = new HashMap<>();
options.put("ARM_SUBSCRIPTION_ID","<Azure Subscription ID>");
options.put("ARM_CLIENT_ID","<Azure Client ID>");
options.put("ARM_CLIENT_SECRET","<Azure Client Secret>");
options.put("ARM_TENANT_ID","<Azure Tenant ID>");

try (TerraformClient client = new TerraformClient(options)) {
    client.setOutputListener(System.out::println);
    client.setErrorListener(System.err::println);

    client.setTerraformVersion("0.15.0");
    
    client.setWorkingDirectory("/some/local/path/");
    client.plan().get();
    client.apply().get();
}
```

### Terraform Client Downloader library

The client can download different terraform versions. These will be saved inside (UserHomeDirectory)/.terraform-spring-boot. 

Example /home/user/.terraform-spring-boot

```java
TerraformDownloader terraformDownloader = new TerraformDownloader();

System.out.println(terraformDownloader.downloadTerraformVersion("0.15.0"));
System.out.println(terraformDownloader.downloadTerraformVersion("0.14.9"));

TerraformClient terraformClient = new TerraformClient();

System.out.println(terraformClient.version().get());
terraformClient.setTerraformVersion("0.15.0");

System.out.println(terraformClient.version().get());
terraformClient.setTerraformVersion("0.14.9");

System.out.println(terraformClient.version().get());
terraformClient.setTerraformVersion("0.14.7");

System.out.println(terraformClient.version().get());
```

### Spring boot

Let's still use the terraform file `storage.tf` under `/some/local/path/` folder to provision Azure resources in this example. Rather than create the `TerraformClient` by ourselves, we let the spring boot framework to wire it for us. First add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.azbuilder.terraform</groupId>
    <artifactId>terraform-spring-boot-starter</artifactId>
    <version>0.0.1</version>
</dependency>
```

And now let's also introduce the Azure credentials in `application.properties` or `application-${spring.profiles.active}.properties`:

```
org.azbuilder.terraform.directory="some path"
```

The final step is to let the Spring framework wire up everything in your spring boot application:

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

            this.terraform.setWorkingDirectory("/some/local/path/");
            this.terraform.plan().get();
            this.terraform.apply().get();
        } finally {
            this.terraform.close();
        }
    }
}
```