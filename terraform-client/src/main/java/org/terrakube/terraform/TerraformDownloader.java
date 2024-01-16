package org.terrakube.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;

import java.io.*;
import java.net.URL;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class TerraformDownloader {

    private static final String TERRAFORM_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/";
    private static final String TOFU_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/tofu/";
    private static final String TERRAFORM_DIRECTORY = "/.terraform-spring-boot/terraform/";

    private static final String TOFU_DIRECTORY = "/.terraform-spring-boot/tofu/";
    private static final String TEMP_DIRECTORY = "/.terraform-spring-boot/";
    public static final String TERRAFORM_RELEASES_URL = "https://releases.hashicorp.com/terraform/index.json";

    private TerraformResponse terraformReleases;
    private File terraformDownloadDirectory;

    private File tofuDownloadDirectory;
    private File terraformDirectory;
    private String userHomeDirectory;

    public TerraformDownloader() {
        try {
            log.info("Initialize Terraform and Tofu Downloader using default URL");
            createDownloadTempDirectory();
            createDownloadTofuTempDirectory();
            getTerraformReleases(TERRAFORM_RELEASES_URL);
        } catch (IOException ex) {
            log.error(ex.getMessage());
        }
    }

    public TerraformDownloader(String terraformReleasesUrl) {
        log.info("Initialize TerraformDownloader using custom URL");

        try {
            createDownloadTempDirectory();
            createDownloadTofuTempDirectory();
            getTerraformReleases(terraformReleasesUrl);
        } catch (IOException ex) {
            log.error(ex.getMessage());
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

    private void createDownloadTofuTempDirectory() throws IOException {
        this.userHomeDirectory = FileUtils.getUserDirectoryPath();
        log.info("User Home Directory for tofu download: {}", this.userHomeDirectory);

        String tofuDownloadPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TOFU_DOWNLOAD_DIRECTORY
                ));
        this.tofuDownloadDirectory = new File(tofuDownloadPath);
        FileUtils.forceMkdir(this.tofuDownloadDirectory);
        log.info("Validate/Create tofu download temp directory: {}", tofuDownloadPath);

        String tofuVersionPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TOFU_DIRECTORY
                ));
        this.terraformDirectory = new File(tofuVersionPath);
        FileUtils.forceMkdir(this.terraformDirectory);
        log.info("Validate/Create tofu directory: {}", tofuVersionPath);
    }

    private void getTerraformReleases(String terraformReleasesUrl) throws IOException {
        log.info("Downloading terraform releases list");

        ObjectMapper objectMapper = new ObjectMapper();

        String tempPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TEMP_DIRECTORY
                ));

        File tempFile;

        if (SystemUtils.IS_OS_UNIX) {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            tempFile = File.createTempFile("terraform-", "-release", new File(tempPath));  // Compliant
        } else {
            tempFile = File.createTempFile("terraform-", "-release", new File(tempPath));   // Compliant
            if (tempFile.setReadable(true, true)) {
                log.info("File permission Readable applied");
            }
            if (tempFile.setWritable(true, true)) {
                log.info("File permission Writable applied");
            }
            if (tempFile.setExecutable(true, true)) {
                log.info("File permission Executable applied");
            }
        }


        FileUtils.copyURLToFile(new URL(terraformReleasesUrl), tempFile);
        this.terraformReleases = objectMapper.readValue(tempFile, TerraformResponse.class);
        log.info("Deleting Temp {}", tempFile.getAbsolutePath());
        log.info("Found {} terraform releases", this.terraformReleases.getVersions().size());
    }

    public String downloadTerraformVersion(String terraformVersion) throws IOException {
        log.info("Downloading terraform version {} architecture {} Type {}", terraformVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        TerraformVersion version = terraformReleases.getVersions().get(terraformVersion);
        boolean notFound = true;
        String terraformFilePath = "";
        if (version != null) {
            for (TerraformBuild terraformBuild : version.getBuilds()) {
                if (terraformBuild.getArch().equals(SystemUtils.OS_ARCH) && (
                        SystemUtils.IS_OS_WINDOWS && terraformBuild.getOs().equals("windows") ||
                                SystemUtils.IS_OS_LINUX && terraformBuild.getOs().equals("linux")) ||
                        SystemUtils.IS_OS_MAC && terraformBuild.getOs().equals("darwin")
                ) {
                    String terraformZipReleaseURL = terraformBuild.getUrl();
                    String fileName = terraformBuild.getFilename();

                    if (!FileUtils.directoryContains(this.terraformDownloadDirectory, new File(
                            this.userHomeDirectory.concat(
                                    FilenameUtils.separatorsToSystem(
                                            TERRAFORM_DOWNLOAD_DIRECTORY.concat("/").concat(fileName)
                                    ))))) {

                        log.info("Downloading: {}", terraformZipReleaseURL);
                        try {
                            File terraformZipFile = new File(
                                    this.userHomeDirectory.concat(
                                            FilenameUtils.separatorsToSystem(
                                                    TERRAFORM_DOWNLOAD_DIRECTORY.concat(fileName)
                                            )));

                            FileUtils.copyURLToFile(new URL(terraformZipReleaseURL), terraformZipFile);

                            terraformFilePath = unzipTerraformVersion(terraformVersion, terraformZipFile);
                        } catch (IOException exception) {
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


    public String downloadTofuVersion(String tofuVersion) throws IOException {
        log.info("Downloading tofu version {} architecture {} Type {}", tofuVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        // "https://github.com/opentofu/opentofu/releases/download/v1.6.0/tofu_1.6.0_windows_386.zip"
        // "https://github.com/opentofu/opentofu/releases/download/v1.6.0/tofu_1.6.0_linux_amd64.zip"
        // "https://github.com/opentofu/opentofu/releases/download/v1.6.0/tofu_1.6.0_darwin_amd64.zip"
        String defaultDownloadUrl = "https://github.com/opentofu/opentofu/releases/download/v%s/tofu_%s_%s_%s.zip";
        String defaultFileName = "tofu_%s_%s_%s.zip";

        String tofuZipReleaseURL = String.format(defaultDownloadUrl, tofuVersion, tofuVersion, getOs(), SystemUtils.OS_ARCH );
        String tofuFileName = String.format(defaultFileName, tofuVersion, getOs(), SystemUtils.OS_ARCH );
        String tofuFilePath = "";
        if (tofuVersion != null) {
            String fileName = tofuFileName;

            if (!FileUtils.directoryContains(this.tofuDownloadDirectory, new File(
                    this.userHomeDirectory.concat(
                            FilenameUtils.separatorsToSystem(
                                    TOFU_DOWNLOAD_DIRECTORY.concat("/").concat(fileName)
                            ))))) {

                log.info("Downloading Tofu from: {}", tofuZipReleaseURL);
                try {
                    File tofuZipFile = new File(
                            this.userHomeDirectory.concat(
                                    FilenameUtils.separatorsToSystem(
                                            TOFU_DOWNLOAD_DIRECTORY.concat(fileName)
                                    )));

                    FileUtils.copyURLToFile(new URL(tofuZipReleaseURL), tofuZipFile);

                    tofuFilePath = unzipTofuVersion(tofuVersion, tofuZipFile);
                } catch (IOException exception) {
                    throw new IOException("Unable to download tofu ".concat(tofuZipReleaseURL));
                }
            } else {
                log.info("{} tofu already exists", fileName);

                return this.userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                TOFU_DIRECTORY.concat(tofuVersion.concat("/").concat("tofu"))
                        )
                );
            }
        } else {
            throw new IllegalArgumentException("Invalid Tofu Version");
        }

        return tofuFilePath;
    }

    public String getOs(){
        if (SystemUtils.IS_OS_LINUX)
            return "linux";
        if (SystemUtils.IS_OS_MAC)
            return "darwin";
        if (SystemUtils.IS_OS_WINDOWS)
            return "windows";
        return "linux";
    }

    private String unzipTerraformVersion(String terraformVersion, File terraformZipFile) throws IOException {
        createVersionDirectory(terraformVersion, TERRAFORM_DIRECTORY);
        String newFilePath = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(terraformZipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();

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

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }

                    if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
                        File updateAccess = new File(newFilePath);
                        if (updateAccess.setExecutable(true, true))
                            log.info("Terraform setExecutable successful");
                        else
                            log.error("Terraform setExecutable successful");
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return newFilePath;
    }

    private String unzipTofuVersion(String tofuVersion, File tofuZipFile) throws IOException {
        createVersionDirectory(tofuVersion, TOFU_DIRECTORY);
        String newFilePath = null;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tofuZipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();

            byte[] buffer = new byte[1024];
            while (zipEntry != null) {
                newFilePath = this.userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                TOFU_DIRECTORY.concat(tofuVersion.concat("/").concat(zipEntry.getName()))
                        )
                );
                log.info("Unzip Tofu files: {}", newFilePath);
                File newTofuFile = new File(newFilePath);
                if (zipEntry.isDirectory()) {
                    if (!newTofuFile.isDirectory() && !newTofuFile.mkdirs()) {
                        throw new IOException("Failed to create directory for" + newTofuFile);
                    }
                } else {
                    File parent = newTofuFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory for" + parent);
                    }

                    try (FileOutputStream file = new FileOutputStream(newTofuFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            file.write(buffer, 0, len);
                        }
                    }

                    if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
                        File updateAccess = new File(newFilePath);
                        if (updateAccess.setExecutable(true, true))
                            log.info("Tofu setExecutable successful");
                        else
                            log.error("Tofu setExecutable successful");
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
        return this.userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TOFU_DIRECTORY.concat(tofuVersion.concat("/").concat("tofu"))
                ));
    }

    private void createVersionDirectory(String version, String directoryPath) throws IOException {
        File terraformVersionDirectory = new File(
                userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                directoryPath.concat(version)
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
