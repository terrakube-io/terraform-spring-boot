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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
public class TerraformDownloader {

    private static final String TERRAFORM_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/";
    private static final String TOFU_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/tofu/";
    private static final String TERRAGRUNT_DOWNLOAD_DIRECTORY = "/.terraform-spring-boot/download/terragrunt/";
    private static final String TERRAFORM_DIRECTORY = "/.terraform-spring-boot/terraform/";
    private static final String TOFU_DIRECTORY = "/.terraform-spring-boot/tofu/";
    private static final String TERRAGRUNT_DIRECTORY = "/.terraform-spring-boot/terragrunt/";
    public static final String TERRAFORM_RELEASES_URL = "https://releases.hashicorp.com/terraform/index.json";
    public static final String TOFU_RELEASES_URL = "https://api.github.com/repos/opentofu/opentofu/releases";
    public static final String TERRAGRUNT_RELEASES_URL = "https://api.github.com/repos/gruntwork-io/terragrunt/releases";

    private TerraformResponse terraformReleases;
    private List<TofuRelease> tofuReleases;
    private List<TerragruntRelease> terragruntReleases;
    private File terraformDownloadDirectory;
    private File tofuDownloadDirectory;
    private File terragruntDownloadDirectory;
    private File terraformDirectory;
    private File tofuDirectory;
    private File terragruntDirectory;
    private String userHomeDirectory;
    private ObjectMapper objectMapper = new ObjectMapper();

    public TerraformDownloader() {
        this(TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL, TERRAGRUNT_RELEASES_URL);
    }

    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl) {
        this(terraformReleasesUrl, tofuReleasesUrl, TERRAGRUNT_RELEASES_URL);
    }

    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl, String terragruntReleasesUrl) {
        log.info("Initialize TerraformDownloader");

        try {
            createDownloadTempDirectory();
            createDownloadTofuTempDirectory();
            createDownloadTerragruntTempDirectory();
            getTerraformReleases(terraformReleasesUrl != null ? terraformReleasesUrl : TERRAFORM_RELEASES_URL);
            getTofuReleases(tofuReleasesUrl != null ? tofuReleasesUrl : TOFU_RELEASES_URL);
            getTerragruntReleases(terragruntReleasesUrl != null ? terragruntReleasesUrl : TERRAGRUNT_RELEASES_URL);
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
        this.tofuDirectory = new File(tofuVersionPath);
        FileUtils.forceMkdir(this.tofuDirectory);
        log.info("Validate/Create tofu directory: {}", tofuVersionPath);
    }

    private void createDownloadTerragruntTempDirectory() throws IOException {
        this.userHomeDirectory = FileUtils.getUserDirectoryPath();
        log.info("User Home Directory for terragrunt download: {}", this.userHomeDirectory);

        String terragruntDownloadPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TERRAGRUNT_DOWNLOAD_DIRECTORY
                ));
        this.terragruntDownloadDirectory = new File(terragruntDownloadPath);
        FileUtils.forceMkdir(this.terragruntDownloadDirectory);
        log.info("Validate/Create terragrunt download temp directory: {}", terragruntDownloadPath);

        String terragruntVersionPath = userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TERRAGRUNT_DIRECTORY
                ));
        this.terragruntDirectory = new File(terragruntVersionPath);
        FileUtils.forceMkdir(this.terragruntDirectory);
        log.info("Validate/Create terragrunt directory: {}", terragruntVersionPath);
    }

    private void getTerraformReleases(String terraformReleasesUrl) throws IOException {
        log.info("Downloading terraform releases list");
        try {
            Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
            String tmpdir = Files.createDirectories(path).toFile().getAbsolutePath() + "/terraform-releases.json";
            log.info("Downloading terraform releases to {}", tmpdir);
            File terraformReleasesFile = new File(tmpdir);
            downloadReleasesToFile(terraformReleasesUrl, terraformReleasesFile);
            log.info("Downloaded terraform releases completed");
            this.terraformReleases = objectMapper.readValue(FileUtils.readFileToString(new File(tmpdir), "UTF-8"), TerraformResponse.class);
            log.info("Parsing terraform releases completed");
            Files.deleteIfExists(terraformReleasesFile.toPath());
            log.info("Deleting temporary files completed");
        } catch (Exception e) {
            log.error("Error fetching terraform releases {}", e.getMessage());
        }

        assert this.terraformReleases != null;
        log.info("Found {} terraform releases", this.terraformReleases.getVersions().size());
    }

    private void getTofuReleases(String tofuReleasesUrl) throws IOException {
        log.info("Downloading tofu releases list");

        Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
        String tmpdir = Files.createDirectories(path).toFile().getAbsolutePath() + "/tofu-releases.json";
        log.info("Downloading tofu releases to {}", tmpdir);
        File tofuReleasesFile = new File(tmpdir);

        try {
            downloadReleasesToFile(tofuReleasesUrl, tofuReleasesFile);
            log.info("Downloaded tofu releases completed");
            this.tofuReleases = objectMapper.readValue(FileUtils.readFileToString(new File(tmpdir), "UTF-8"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TofuRelease.class));

            log.info("Parsing tofu releases completed");
            Files.deleteIfExists(tofuReleasesFile.toPath());
            log.info("Deleting temporary tofu files completed");

        } catch (Exception e) {
            log.error("Error fetching tofu releases {}", e.getMessage());
        }
        log.info("Found {} tofu releases", this.tofuReleases != null ? this.tofuReleases.size() : 0);
    }

    private void getTerragruntReleases(String terragruntReleasesUrl) throws IOException {
        log.info("Downloading terragrunt releases list");

        Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
        String tmpdir = Files.createDirectories(path).toFile().getAbsolutePath() + "/terragrunt-releases.json";
        log.info("Downloading terragrunt releases to {}", tmpdir);
        File terragruntReleasesFile = new File(tmpdir);

        try {
            downloadReleasesToFile(terragruntReleasesUrl, terragruntReleasesFile);
            log.info("Downloaded terragrunt releases completed");
            this.terragruntReleases = objectMapper.readValue(FileUtils.readFileToString(new File(tmpdir), "UTF-8"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TerragruntRelease.class));

            log.info("Parsing terragrunt releases completed");
            Files.deleteIfExists(terragruntReleasesFile.toPath());
            log.info("Deleting temporary terragrunt files completed");

        } catch (Exception e) {
            log.error("Error fetching terragrunt releases {}", e.getMessage());
        }
        log.info("Found {} terragrunt releases", this.terragruntReleases != null ? this.terragruntReleases.size() : 0);
    }

    private static void downloadReleasesToFile(String releasesUrl, File releasesFile) {
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .followRedirect(true)
                                .proxyWithSystemProperties()
                ))
                .defaultHeaders(h -> {
                    h.add("User-Agent", "releases-downloader");
                    h.setAccept(List.of(MediaType.APPLICATION_JSON));
                })
                .build();

        webClient.get()
                .uri(releasesUrl)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.createException().flatMap(Mono::error)
                )
                .bodyToFlux(DataBuffer.class)
                .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, releasesFile.toPath() ))
                .then()
                .block();
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

    /**
     * Resolve a Terraform version constraint (e.g. {@code "~>1.5"}, {@code ">=1.4 <2.0"}) to a
     * concrete version string (e.g. "1.5.7") without downloading anything.
     */
    public String resolveTerraformVersion(String terraformVersion) {
        try {
            RangeList versionRangeList = RangeListFactory.create(terraformVersion);

            Set<String> allTerraformKeys = terraformReleases.getVersions().keySet();
            return allTerraformKeys.stream()
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
    }

    /**
     * Return the expected local filesystem path where a Terraform or Tofu binary
     * should reside after being downloaded/unzipped.
     *
     * @param resolvedVersion a concrete version string (e.g. "1.5.7")
     * @param tofu            true for OpenTofu, false for Terraform
     * @return absolute path to the binary executable
     */
    public String getTerraformBinaryPath(String resolvedVersion, boolean tofu) {
        String product = tofu ? "tofu" : "terraform";
        String directory = tofu ? TOFU_DIRECTORY : TERRAFORM_DIRECTORY;
        return this.userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        directory.concat(resolvedVersion.concat("/").concat(product))
                )
        );
    }

    public String downloadTerraformVersion(String terraformVersion) throws IOException {
        log.info("Downloading terraform version \" {} \" architecture {} Type {}", terraformVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        terraformVersion = resolveTerraformVersion(terraformVersion);
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

    /**
     * Resolve a Tofu version constraint to a concrete version string without
     * downloading anything.
     */
    public String resolveTofuVersion(String tofuVersion) {
        Set<String> allTofuKeys = tofuReleases.stream().map(TofuRelease::getName).collect(Collectors.toSet());
        try {
            RangeList versionRangeList = RangeListFactory.create(tofuVersion);

            return allTofuKeys.stream()
                    .filter(v -> {
                        try {
                            Semver tempVersion = new Semver(v);
                            return tempVersion.satisfies(versionRangeList);
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparing(Semver::new))
                    .orElseThrow(() -> new IllegalArgumentException("Not valid tofu version format"));
        } catch (Exception e) {
            log.error("Error parsing tofu version range: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid tofu version range");
        }
    }

    public String downloadTofuVersion(String tofuVersion) throws IOException {
        log.info("Downloading tofu version {} architecture {} Type {}", tofuVersion, SystemUtils.OS_ARCH,
                SystemUtils.OS_NAME);

        String defaultFileName = "tofu_%s_%s_%s.zip";

        //Extracting only the relase name, for example: 1.8.0
        Set<String> allTofuKeys = tofuReleases.stream().map(TofuRelease::getName).collect(Collectors.toSet());
        log.info("All tofu releases: {}", allTofuKeys);

        tofuVersion = resolveTofuVersion(tofuVersion);

        log.info("Tofu version is \" {} \"", tofuVersion);
        String finalTofuVersion = tofuVersion;
        List<TofuRelease> releases = tofuReleases.stream()
                .filter(release -> release.getName().equals(finalTofuVersion))
                .toList();

        if (releases.size() != 1) {
            throw new IllegalArgumentException("Invalid Tofu Version");
        }

        List<TofuAsset> assets = releases.get(0).getAssets().stream().filter(asset -> asset.getName().endsWith(".zip"))
                .toList();

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

    /**
     * Resolve a Terragrunt version constraint (e.g. {@code "~>0.68"}, {@code ">=0.50 <1.0"}) to a
     * concrete version string (e.g. "0.68.0") without downloading anything.
     */
    public String resolveTerragruntVersion(String terragruntVersion) {
        if (terragruntReleases == null) {
            throw new IllegalArgumentException("Terragrunt releases list is not initialized");
        }
        Set<String> allTerragruntKeys = terragruntReleases.stream()
                .map(r -> r.getTag_name() != null ? r.getTag_name() : r.getName())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        try {
            String cleanRange = terragruntVersion.startsWith("v") ? terragruntVersion.substring(1) : terragruntVersion;
            RangeList versionRangeList = RangeListFactory.create(cleanRange);

            return allTerragruntKeys.stream()
                    .filter(v -> {
                        try {
                            String semverStr = v.startsWith("v") ? v.substring(1) : v;
                            Semver tempVersion = new Semver(semverStr);
                            return tempVersion.satisfies(versionRangeList);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparing(v -> new Semver(v.startsWith("v") ? v.substring(1) : v)))
                    .map(v -> v.startsWith("v") ? v.substring(1) : v)
                    .orElseThrow(() -> new IllegalArgumentException("Not valid terragrunt version format"));
        } catch (Exception e) {
            log.error("Error parsing terragrunt version range: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid terragrunt version range");
        }
    }

    /**
     * Return the expected local filesystem path where a Terragrunt binary
     * should reside after being downloaded.
     *
     * @param resolvedVersion a concrete version string (e.g. "0.68.0")
     * @return absolute path to the binary executable
     */
    public String getTerragruntBinaryPath(String resolvedVersion) {
        String binaryName = SystemUtils.IS_OS_WINDOWS ? "terragrunt.exe" : "terragrunt";
        return this.userHomeDirectory.concat(
                FilenameUtils.separatorsToSystem(
                        TERRAGRUNT_DIRECTORY.concat(resolvedVersion.concat("/").concat(binaryName))
                )
        );
    }

    public String downloadTerragruntVersion(String terragruntVersion) throws IOException {
        log.info("Downloading terragrunt version {} architecture {} Type {}", terragruntVersion, SystemUtils.OS_ARCH,
                SystemUtils.OS_NAME);

        terragruntVersion = resolveTerragruntVersion(terragruntVersion);
        log.info("Terragrunt version is \" {} \"", terragruntVersion);

        String finalVersion = terragruntVersion;
        List<TerragruntRelease> releases = terragruntReleases.stream()
                .filter(release -> {
                    String tag = release.getTag_name() != null ? release.getTag_name() : release.getName();
                    String clean = (tag != null && tag.startsWith("v")) ? tag.substring(1) : tag;
                    return finalVersion.equals(clean);
                })
                .toList();

        if (releases.size() != 1) {
            throw new IllegalArgumentException("Invalid Terragrunt Version");
        }

        List<TerragruntAsset> assets = releases.get(0).getAssets();
        boolean notFound = true;
        String terragruntFilePath = "";

        String expectedOs = getOs();
        String expectedArch = getArch();

        for (TerragruntAsset asset : assets) {
            String assetName = asset.getName();
            if (isTerragruntAssetMatch(assetName, expectedOs, expectedArch)) {
                String downloadUrl = asset.getBrowser_download_url();
                terragruntFilePath = downloadTerragruntBinaryFile(assetName, downloadUrl, terragruntVersion);
                notFound = false;
                break;
            }
        }

        if (notFound) {
            throw new IllegalArgumentException("Invalid Terragrunt Version for current OS/Arch");
        }

        return terragruntFilePath;
    }

    private boolean isTerragruntAssetMatch(String assetName, String os, String arch) {
        if (!assetName.startsWith("terragrunt_")) {
            return false;
        }
        // Exclude checksum/signature/metadata files — list extended to cover
        // .pem and .json files present in newer Terragrunt GitHub releases.
        if (assetName.endsWith(".sha256") || assetName.endsWith(".sig")
                || assetName.endsWith(".txt") || assetName.endsWith(".pem")
                || assetName.endsWith(".json") || assetName.contains("SHA256")) {
            return false;
        }
        String nameWithoutExt = assetName.endsWith(".exe") ? assetName.substring(0, assetName.length() - 4) : assetName;
        String[] parts = nameWithoutExt.split("_");
        if (parts.length >= 3) {
            String assetOs = parts[1];
            String assetArch = parts[2];
            return doSystemAndReleaseMatch(assetArch, assetOs);
        }
        return false;
    }

    private String downloadTerragruntBinaryFile(String fileName, String downloadUrl, String version) throws IOException {
        String binaryPath = getTerragruntBinaryPath(version);
        File binaryFile = new File(binaryPath);

        if (binaryFile.exists()) {
            log.info("Terragrunt {} already exists at {}", version, binaryPath);
            return binaryPath;
        }

        File downloadDir = this.terragruntDownloadDirectory;
        File downloadedFile = new File(downloadDir, fileName);

        if (!downloadedFile.exists()) {
            log.info("Downloading Terragrunt from: {}", downloadUrl);
            try {
                WebClient webClient = WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .followRedirect(true)
                                        .proxyWithSystemProperties()
                        ))
                        .defaultHeaders(h -> {
                            h.add("User-Agent", "terragrunt-downloader");
                            h.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));
                        })
                        .build();

                Path filePath = downloadedFile.toPath();

                webClient.get()
                        .uri(downloadUrl)
                        .retrieve()
                        .onStatus(
                                status -> !status.is2xxSuccessful(),
                                clientResponse -> clientResponse.createException().flatMap(Mono::error)
                        )
                        .bodyToFlux(DataBuffer.class)
                        .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, filePath))
                        .then()
                        .block();
            } catch (Exception exception) {
                throw new IOException("Unable to download ".concat(downloadUrl), exception);
            }
        }

        createVersionDirectory(version, TERRAGRUNT_DIRECTORY);
        FileUtils.copyFile(downloadedFile, binaryFile);

        if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
            if (binaryFile.setExecutable(true, true)) {
                log.info("Terragrunt setExecutable successful");
            } else {
                log.error("Terragrunt setExecutable failed");
            }
        }

        return binaryPath;
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
        // Normalize JVM architecture names to the conventions used by Terraform,
        // OpenTofu, and Terragrunt release asset filenames.
        if (SystemUtils.OS_ARCH.equals("aarch64")) {
            return "arm64";
        }
        // Some JVMs (notably OpenJDK on certain Linux distributions) report "x86_64"
        // where release assets use "amd64".
        if (SystemUtils.OS_ARCH.equals("x86_64")) {
            return "amd64";
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
                    if (!newTofuFile.isDirectory() && !newFileDirectory(newTofuFile)) {
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

    private boolean newFileDirectory(File file) {
        return file.mkdirs();
    }

    private void createVersionDirectory(String version, String directoryPath) throws IOException {
        File versionDirectory = new File(
                userHomeDirectory.concat(
                        FilenameUtils.separatorsToSystem(
                                directoryPath.concat(version)
                        )));
        FileUtils.forceMkdir(versionDirectory);
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
    private String tag_name;
    private List<TofuAsset> assets;
}

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TofuAsset {
    private String name;
    private String browser_download_url;
}

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TerragruntRelease {
    private String name;
    private String tag_name;
    private List<TerragruntAsset> assets;
}

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TerragruntAsset {
    private String name;
    private String browser_download_url;
}
