package org.azbuilder.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class TerraformDownloader {

    private static final String TERRAFORM_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/";
    private static final String TERRAFORM_DIRECTORY = "/.terraform-spring-boot/terraform/";
    private static final String TERRAFORM_RELEASES_URL = "https://releases.hashicorp.com/terraform/index.json";

    private TerraformResponse terraformReleases;
    private File terraformDownloadDirectory;
    private File terraformDirectory;
    private String userHomeDirectory;
    private OkHttpClient httpClient;

    public TerraformDownloader() {
        log.info("Initialize TerraformDownloader");
        this.httpClient = new OkHttpClient();

        try {
            createDownloadTempDirectory();
            getTerraformReleases();
        } catch (IOException ex) {
            throw new RuntimeException("Error creating TerraformDownloader");
        }
    }

    private void createDownloadTempDirectory() throws IOException {
        this.userHomeDirectory = FileUtils.getUserDirectoryPath();
        log.info("User Home Directory: {}", this.userHomeDirectory);

        String downloadPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TERRAFORM_DOWNLOAD_DIRECTORY
                ));
        this.terraformDownloadDirectory = new File(downloadPath);
        FileUtils.forceMkdir(this.terraformDownloadDirectory);
        log.info("Validate/Create download temp directory: {}", downloadPath);

        String terrafomVersionPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TERRAFORM_DIRECTORY
                ));
        this.terraformDirectory = new File(terrafomVersionPath);
        FileUtils.forceMkdir(this.terraformDirectory);
        log.info("Validate/Create terraform directory: {}", terrafomVersionPath);
    }

    private void getTerraformReleases() throws IOException {
        log.info("Downloading terraform releases list");
        Request request = new Request.Builder()
                .url(TERRAFORM_RELEASES_URL)
                .build();

        //ResponseBody response = client.newCall(request).execute().body();
        ObjectMapper objectMapper = new ObjectMapper();
        ResponseBody responseBody = httpClient.newCall(request).execute().body();
        this.terraformReleases = objectMapper.readValue(responseBody.string(), TerraformResponse.class);

        log.info("Found {} terraform releases", this.terraformReleases.getVersions().size());
    }

    public String downloadTerraformVersion(String terraformVersion) throws IOException {
        log.info("Downloading terraform version {} architecture {} Type {}", terraformVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        TerraformVersion version = terraformReleases.getVersions().get(terraformVersion);
        boolean notFound = true;
        String terraformFilePath = "";
        if (version != null) {
            for (TerraformBuild terraformBuild : version.getBuilds()) {
                if (terraformBuild.getArch().equals(SystemUtils.OS_ARCH) && (SystemUtils.IS_OS_WINDOWS && terraformBuild.getOs().equals("windows") || SystemUtils.IS_OS_LINUX && terraformBuild.getOs().equals("linux"))) {
                    String terraformZipReleaseURL = terraformBuild.getUrl();
                    String fileName = terraformBuild.getFilename();

                    if (!FileUtils.directoryContains(this.terraformDownloadDirectory, new File(
                            this.userHomeDirectory.concat(
                                    FilenameUtils.separatorsToSystem(
                                            TERRAFORM_DOWNLOAD_DIRECTORY.concat("/").concat(fileName)
                                    ))))) {

                        Request request = new Request.Builder()
                                .url(terraformZipReleaseURL)
                                .build();

                        log.info("Downloading: {}", terraformZipReleaseURL);
                        Response responseTerraformFile = httpClient.newCall(request).execute();
                        if (responseTerraformFile.isSuccessful()) {
                            InputStream initialStream = responseTerraformFile.body().byteStream();

                            File terraformZipFile = new File(
                                    this.userHomeDirectory.concat(
                                            FilenameUtils.separatorsToSystem(
                                                    TERRAFORM_DOWNLOAD_DIRECTORY.concat(fileName)
                                            )));

                            FileUtils.copyInputStreamToFile(initialStream, terraformZipFile);

                            terraformFilePath = unzipTerraformVersion(terraformVersion, terraformZipFile);
                        } else {
                            throw new IOException("Unable to download ".concat(terraformZipReleaseURL));
                        }
                    } else {
                        log.info("{} already exists", fileName);

                        return this.userHomeDirectory.concat(
                                FilenameUtils.separatorsToSystem(
                                        TERRAFORM_DIRECTORY.concat(terraformVersion.concat("/").concat("terraform"))
                                )
                        );
                    }
                    notFound = false;
                    break;
                }
            }
            if (notFound) {
                throw new IllegalArgumentException("Invalid Terraform Version");
            }
        } else {
            throw new IllegalArgumentException("Invalid Terraform Version");
        }

        return terraformFilePath;
    }

    private String unzipTerraformVersion(String terraformVersion, File terraformZipFile) throws IOException {
        createTerraformVersionDirectory(terraformVersion);
        ZipInputStream zis = new ZipInputStream(new FileInputStream(terraformZipFile));
        ZipEntry zipEntry = zis.getNextEntry();
        String newFilePath = null;
        byte[] buffer = new byte[1024];
        while (zipEntry != null) {
            newFilePath = this.userHomeDirectory.concat(
                    FilenameUtils.separatorsToSystem(
                            TERRAFORM_DIRECTORY.concat(terraformVersion.concat("/").concat(zipEntry.getName()))
                    )
            );
            log.info("Unzip: {}", newFilePath);
            File newFile = new File(newFilePath);
            if (zipEntry.isDirectory()) {
                if (!newFile.isDirectory() && !newFile.mkdirs()) {
                    throw new IOException("Failed to create directory " + newFile);
                }
            } else {
                File parent = newFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory " + parent);
                }

                // write file content
                //newFile.setExecutable(true);
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();

                if(SystemUtils.IS_OS_LINUX){
                    File updateAcces = new File(newFilePath);
                    updateAcces.setExecutable(true,true);
                }
            }
            zipEntry = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();

        return newFilePath;
    }

    private void createTerraformVersionDirectory(String terraformVersion) throws IOException {
        File terraformVersionDirectory = new File(
                userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                TERRAFORM_DIRECTORY.concat(terraformVersion)
                        )));
        FileUtils.forceMkdir(terraformVersionDirectory);
    }

}

@Getter
@Setter
class TerraformBuild {
    private String name;
    private String version;
    private String os;
    private String arch;
    private String filename;
    private String url;
}

@Getter
@Setter
class TerraformResponse {
    private String name;
    private HashMap<String, TerraformVersion> versions;
}

@Getter
@Setter
class TerraformVersion {
    private String name;
    private String version;
    private String shasums;
    private String shasums_signature;
    private List<String> shasums_signatures;
    private List<TerraformBuild> builds;
}
