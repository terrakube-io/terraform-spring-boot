package io.terrakube.terraform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import java.lang.module.ModuleDescriptor.Version;

import org.semver4j.Semver;
import org.semver4j.range.RangeList;
import org.semver4j.range.RangeListFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class TerraformDownloader {

    private static final String TERRAFORM_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/";
    private static final String TOFU_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/tofu/";
    private static final String TERRAFORM_DIRECTORY = "/.terraform-spring-boot/terraform/";

    private static final String TOFU_DIRECTORY = "/.terraform-spring-boot/tofu/";
    public static final String TERRAFORM_RELEASES_URL = "https://releases.hashicorp.com/terraform/index.json";
    public static final String TOFU_RELEASES_URL = "https://api.github.com/repos/opentofu/opentofu/releases";

    private TerraformResponse terraformReleases;
    private List<TofuRelease> tofuReleases;
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
            getTofuReleases(TOFU_RELEASES_URL);
        } catch (IOException ex) {
            log.error(ex.getMessage());
        }
    }

    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl) {
        log.info("Initialize TerraformDownloader using custom URL");

        try {
            createDownloadTempDirectory();
            createDownloadTofuTempDirectory();
            getTerraformReleases(terraformReleasesUrl);
            getTofuReleases(tofuReleasesUrl);
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
        try {
            WebClient webClient = WebClient.builder()
                    .exchangeStrategies(ExchangeStrategies.builder()
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                            .build())
                    .baseUrl(terraformReleasesUrl)
                    .clientConnector(
                            new ReactorClientHttpConnector(
                                    HttpClient.create().proxyWithSystemProperties())
                    )
                    .build();

            this.terraformReleases = webClient.get()
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(TerraformResponse.class)
                    .block();

        } catch (Exception e) {
            log.error("Error fetching terraform releases {}", e.getMessage());
        }

        assert this.terraformReleases != null;
        log.info("Found {} terraform releases", this.terraformReleases.getVersions().size());
    }

    private void getTofuReleases(String tofuReleasesUrl) throws IOException {
        log.info("Downloading tofu releases list");
        String tofuTemp = "";
        try {
            WebClient webClient = WebClient.builder()
                    .exchangeStrategies(ExchangeStrategies.builder()
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(15 * 1024 * 1024))
                            .build())
                    .baseUrl(tofuReleasesUrl)
                    .clientConnector(
                            new ReactorClientHttpConnector(
                                    HttpClient.create().proxyWithSystemProperties())
                    )
                    .build();

            tofuTemp = webClient.get()
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
                    ObjectMapper objectMapper = new ObjectMapper();

            this.tofuReleases = objectMapper.readValue(tofuTemp,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TofuRelease.class));
        } catch (Exception e) {
            log.error("Error fetching terraform releases {}", e.getMessage());
        }
        log.info("Found {} tofu releases", this.tofuReleases.size());
    }

    private String downloadFileOrReturnPathIfAlreadyExists(String fileName, String zipReleaseUrl, String version,
                                                           boolean tofu) throws IOException {
        String downloadPath = tofu ? TOFU_DOWNLOAD_DIRECTORY : TERRAFORM_DOWNLOAD_DIRECTORY;
        String path = tofu ? TOFU_DIRECTORY : TERRAFORM_DIRECTORY;
        String product = tofu ? "tofu" : "terraform";
        File downloadDirectory = tofu ? this.tofuDownloadDirectory : this.terraformDownloadDirectory;

        if (!FileUtils.directoryContains(downloadDirectory, new File(
                this.userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                downloadPath.concat("/").concat(fileName)))))) {

            log.info("Downloading {} from: {}", product, zipReleaseUrl);
            try {
                File zipFile = new File(
                        this.userHomeDirectory.concat(
                                FilenameUtils.separatorsToSystem(
                                        downloadPath.concat(fileName)
                                )));

                WebClient webClient = WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .followRedirect(true)
                                        .proxyWithSystemProperties()
                        ))
                        .defaultHeaders(h -> {
                            h.add("User-Agent", "terraform-downloader");
                            h.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
                        })
                        .build();

                Path filePath = zipFile.toPath();

                webClient.get()
                        .uri(zipReleaseUrl)
                        .retrieve()
                        .onStatus(
                                status -> !status.is2xxSuccessful(),
                                clientResponse -> clientResponse.createException().flatMap(Mono::error)
                        )
                        .bodyToFlux(DataBuffer.class)
                        .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, filePath))
                        .then()
                        .block();

                if (tofu) {
                    return unzipTofuVersion(version, zipFile);
                } else {
                    return unzipTerraformVersion(version, zipFile);
                }

            } catch (IOException exception) {
                throw new IOException("Unable to download ".concat(zipReleaseUrl));
            }
        } else {
            log.info("{} {} already exists", fileName, product);

            return this.userHomeDirectory.concat(
                    FilenameUtils.separatorsToSystem(
                            path.concat(version.concat("/").concat(product))
                    )
            );
        }
    }


    private boolean doSystemAndReleaseMatch(String arch, String os) {
        return arch.equals(this.getArch()) && os.equals(this.getOs());
    }

    public String downloadTerraformVersion(String terraformVersion) throws IOException {
        log.info("Downloading terraform version \" {} \" architecture {} Type {}", terraformVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        try {
            RangeList versionRangeList = RangeListFactory.create(terraformVersion);

            Set<String> allTerraformKeys = terraformReleases.getVersions().keySet();
            terraformVersion = allTerraformKeys.stream()
                    .filter(v -> {
                        try {
                            Semver tempVersion = new Semver(v);
                            return tempVersion.satisfies(versionRangeList);
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparing(Version::parse))
                    .orElseThrow(() -> new IllegalArgumentException("Not valid version format"));
        } catch (Exception e) {
            log.error("Error parsing Terraform version range: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Terraform version range");
        }
        log.info("Terraform version is \" {} \"", terraformVersion);
        TerraformVersion version = terraformReleases.getVersions().get(terraformVersion);
        boolean notFound = true;
        String terraformFilePath = "";
        if (version == null) {
            throw new IllegalArgumentException("Invalid Terraform Version");
        }
        for (TerraformBuild terraformBuild : version.getBuilds()) {
            if (doSystemAndReleaseMatch(terraformBuild.getArch(), terraformBuild.getOs())) {
                String terraformZipReleaseURL = terraformBuild.getUrl();
                String fileName = terraformBuild.getFilename();

                terraformFilePath = downloadFileOrReturnPathIfAlreadyExists(fileName, terraformZipReleaseURL, terraformVersion, false);
                notFound = false;
                break;
            }
        }
        if (notFound) {
            throw new IllegalArgumentException("Invalid Terraform Version");
        }

        return terraformFilePath;
    }

    public String downloadTofuVersion(String tofuVersion) throws IOException {
        log.info("Downloading tofu version {} architecture {} Type {}", tofuVersion, SystemUtils.OS_ARCH,
                SystemUtils.OS_NAME);

        String defaultFileName = "tofu_%s_%s_%s.zip";

        List<TofuRelease> releases = tofuReleases.stream()
                .filter(release -> release.getName().equals("v" + tofuVersion))
                .collect(Collectors.toList());

        if (releases.size() != 1) {
            throw new IllegalArgumentException("Invalid Tofu Version");
        }

        List<TofuAsset> assets = releases.get(0).getAssets().stream().filter(asset -> asset.getName().endsWith(".zip"))
                .collect(Collectors.toList());

        boolean notFound = true;
        String tofuFilePath = "";
        for (TofuAsset asset : assets) {
            String[] parts = asset.getName().split("_");
            String os = parts[2];
            String arch = parts[3].replace(".zip", ""); // we need to remove .zip from the asset name example: tofu_1.6.2_linux_amd64.zip
            if (doSystemAndReleaseMatch(arch, os)) {
                String zipReleaseURL = asset.getBrowser_download_url();
                String fileName = String.format(defaultFileName, tofuVersion, getOs(), arch);
                tofuFilePath = downloadFileOrReturnPathIfAlreadyExists(fileName, zipReleaseURL, tofuVersion, true);
                notFound = false;
                break;
            }
        }
        if (notFound) {
            throw new IllegalArgumentException("Invalid Tofu Version");
        }

        return tofuFilePath;
    }

    public String getOs() {
        if (SystemUtils.IS_OS_LINUX)
            return "linux";
        if (SystemUtils.IS_OS_MAC)
            return "darwin";
        if (SystemUtils.IS_OS_WINDOWS)
            return "windows";
        return "linux";
    }

    private String getArch() {
        if (SystemUtils.OS_ARCH == null) {
            throw new IllegalArgumentException("System architecture not detected");
        }
        if (SystemUtils.OS_ARCH.equals("aarch64")) {
            return "arm64";
        }
        return SystemUtils.OS_ARCH;
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

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TofuRelease {
    private String name;
    private List<TofuAsset> assets;
}

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TofuAsset {
    private String name;
    private String browser_download_url;
}
