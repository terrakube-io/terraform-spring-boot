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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
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

    // Cache duration for the release list, shared by all downloaders per process so consecutive commands no longer re-fetch the list every time.
    // This along with the cache also means we can fall back on the cached list if we hit the rate limit, so it should help fix a bunch of rate limit issues
    private static final Duration RELEASES_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration RELEASES_RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private static final ConcurrentMap<String, CachedReleases<?>> RELEASES_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> RELEASES_LOCKS = new ConcurrentHashMap<>();
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Makes sure we only cache the binaries for exactly specified versions, not for ranges
    private static final Pattern EXACT_VERSION = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.+-]+)?$");

    private final String terraformReleasesUrl;
    private final String tofuReleasesUrl;
    private File terraformDownloadDirectory;

    private File tofuDownloadDirectory;
    private File terraformDirectory;
    private String userHomeDirectory;

    private static final class CachedReleases<T> {
        private final T releases;
        private final Instant expiresAt;

        CachedReleases(T releases, Instant expiresAt) {
            this.releases = releases;
            this.expiresAt = expiresAt;
        }

        T releases() {
            return releases;
        }

        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    @FunctionalInterface
    private interface ReleasesParser<T> {
        T parse(File releasesFile) throws IOException;
    }

    public TerraformDownloader() {
        this(TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL);
    }

    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl) {
        log.info("Initialize TerraformDownloader using custom URL");
        log.debug("Terraform releases URL: {}", terraformReleasesUrl);
        log.debug("Tofu releases URL: {}", tofuReleasesUrl);

        this.terraformReleasesUrl = terraformReleasesUrl;
        this.tofuReleasesUrl = tofuReleasesUrl;

        try {
            createDownloadTempDirectory();
            createDownloadTofuTempDirectory();
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

    private TerraformResponse getTerraformReleases() {
        return getCachedReleases("terraform", terraformReleasesUrl, releasesFile -> {
            TerraformResponse response = OBJECT_MAPPER.readValue(releasesFile, TerraformResponse.class);
            if (response == null || response.getVersions() == null) {
                throw new IOException("Terraform releases response has no versions");
            }
            log.info("Found {} terraform releases", response.getVersions().size());
            return response;
        });
    }

    private List<TofuRelease> getTofuReleases() {
        return getCachedReleases("tofu", tofuReleasesUrl, releasesFile -> {
            List<TofuRelease> releases = OBJECT_MAPPER.readValue(releasesFile,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, TofuRelease.class));
            if (releases == null) {
                throw new IOException("Tofu releases response is empty");
            }
            log.info("Found {} tofu releases", releases.size());
            return releases;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T getCachedReleases(String product, String releasesUrl, ReleasesParser<T> parser) {
        String key = product + "|" + releasesUrl;
        CachedReleases<T> cached = (CachedReleases<T>) RELEASES_CACHE.get(key);
        if (cached != null && cached.isFresh()) {
            return cached.releases();
        }

        synchronized (RELEASES_LOCKS.computeIfAbsent(key, k -> new Object())) {
            // Another thread may have refreshed the list while this one was waiting.
            // This should fix a potential upstream race condition in terrakube-controller where the key might expire between checking the key and retrieving the key
            cached = (CachedReleases<T>) RELEASES_CACHE.get(key);
            if (cached != null && cached.isFresh()) {
                return cached.releases();
            }

            try {
                T releases = fetchReleases(product, releasesUrl, parser);
                RELEASES_CACHE.put(key, new CachedReleases<>(releases, Instant.now().plus(RELEASES_CACHE_TTL)));
                return releases;
            } catch (Exception e) {
                if (cached != null) {
                    log.warn("Error fetching {} releases from {}, using the previous list: {}", product, releasesUrl, e.getMessage());
                    RELEASES_CACHE.put(key, new CachedReleases<>(cached.releases(), Instant.now().plus(RELEASES_RETRY_AFTER_FAILURE)));
                    return cached.releases();
                }
                throw new IllegalStateException(String.format("Unable to fetch %s releases from %s", product, releasesUrl), e);
            }
        }
    }

    private static <T> T fetchReleases(String product, String releasesUrl, ReleasesParser<T> parser) throws IOException {
        log.info("Downloading {} releases list from {}", product, releasesUrl);
        Path tempDirectory = Files.createDirectories(
                Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString()));
        try {
            File releasesFile = tempDirectory.resolve(product + "-releases.json").toFile();
            downloadReleasesToFile(releasesUrl, releasesFile);
            return parser.parse(releasesFile);
        } finally {
            FileUtils.deleteQuietly(tempDirectory.toFile());
        }
    }

    // For the tests
    static void clearReleasesCache() {
        RELEASES_CACHE.clear();
    }

    static void expireReleasesCache() {
        RELEASES_CACHE.replaceAll((key, cached) -> new CachedReleases<Object>(cached.releases(), Instant.EPOCH));
    }
    // End tests

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
        String product = tofu ? "tofu" : "terraform";
        File downloadDirectory = tofu ? this.tofuDownloadDirectory : this.terraformDownloadDirectory;
        File binaryFile = new File(getTerraformBinaryPath(version, tofu));

        // Check if the binary is present instead of the zip file
        if (isUsableBinary(binaryFile)) {
            log.info("{} {} already installed at {}", product, version, binaryFile);
            return binaryFile.getAbsolutePath();
        }

        File zipFile = new File(downloadDirectory, fileName);
        if (zipFile.isFile() && zipFile.length() > 0) {
            log.info("{} {} binary missing, extracting existing archive {}", product, version, zipFile);
            try {
                return unzipVersion(version, zipFile, tofu);
            } catch (IOException e) {
                log.warn("Existing {} archive {} is not usable, downloading it again: {}", product, zipFile, e.getMessage());
                FileUtils.deleteQuietly(zipFile);
            }
        }

        log.info("Downloading {} from: {}", product, zipReleaseUrl);
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

        try {
            webClient.get()
                    .uri(zipReleaseUrl)
                    .retrieve()
                    .onStatus(
                            status -> !status.is2xxSuccessful(),
                            clientResponse -> clientResponse.createException().flatMap(Mono::error)
                    )
                    .bodyToFlux(DataBuffer.class)
                    .as(dataBufferFlux -> DataBufferUtils.write(dataBufferFlux, zipFile.toPath()))
                    .then()
                    .block();
        } catch (RuntimeException exception) {
            // A partial archive would otherwise be picked up as an existing archive next time.
            FileUtils.deleteQuietly(zipFile);
            throw new IOException("Unable to download ".concat(zipReleaseUrl), exception);
        }

        try {
            return unzipVersion(version, zipFile, tofu);
        } catch (IOException exception) {
            FileUtils.deleteQuietly(zipFile);
            throw new IOException("Unable to extract ".concat(zipReleaseUrl), exception);
        }
    }

    private String unzipVersion(String version, File zipFile, boolean tofu) throws IOException {
        if (tofu) {
            unzipTofuVersion(version, zipFile);
        } else {
            unzipTerraformVersion(version, zipFile);
        }

        File binaryFile = new File(getTerraformBinaryPath(version, tofu));
        if (!isUsableBinary(binaryFile)) {
            throw new IOException(String.format("Archive %s did not contain a usable %s binary", zipFile, tofu ? "tofu" : "terraform"));
        }
        return binaryFile.getAbsolutePath();
    }

    private Optional<String> findInstalledBinary(String version, boolean tofu) {
        if (version == null || !EXACT_VERSION.matcher(version.trim()).matches()) {
            return Optional.empty();
        }
        File binaryFile = new File(getTerraformBinaryPath(version.trim(), tofu));
        if (isUsableBinary(binaryFile)) {
            log.info("{} {} already installed at {}", tofu ? "tofu" : "terraform", version, binaryFile);
            return Optional.of(binaryFile.getAbsolutePath());
        }
        return Optional.empty();
    }

    private static boolean isUsableBinary(File binaryFile) {
        if (!binaryFile.isFile() || binaryFile.length() == 0) {
            return false;
        }
        if (!binaryFile.canExecute() && !binaryFile.setExecutable(true, true)) {
            return false;
        }
        return binaryFile.canExecute();
    }

    private boolean doSystemAndReleaseMatch(String arch, String os) {
        return arch.equals(this.getArch()) && os.equals(this.getOs());
    }

    /**
     * Resolve a Terraform version constraint (e.g. {@code "~>1.5"}, {@code ">=1.4 <2.0"}) to a
     * concrete version string (e.g. "1.5.7") without downloading anything.
     */
    public String resolveTerraformVersion(String terraformVersion) {
        Set<String> allTerraformKeys = getTerraformReleases().getVersions().keySet();
        try {
            RangeList versionRangeList = RangeListFactory.create(terraformVersion);

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
        Optional<String> installedBinary = findInstalledBinary(terraformVersion, false);
        if (installedBinary.isPresent()) {
            return installedBinary.get();
        }

        log.info("Downloading terraform version \" {} \" architecture {} Type {}", terraformVersion, SystemUtils.OS_ARCH, SystemUtils.OS_NAME);
        terraformVersion = resolveTerraformVersion(terraformVersion);
        log.info("Terraform version is \" {} \"", terraformVersion);
        TerraformVersion version = getTerraformReleases().getVersions().get(terraformVersion);
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
        Set<String> allTofuKeys = getTofuReleases().stream().map(TofuRelease::getName).collect(Collectors.toSet());
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
        Optional<String> installedBinary = findInstalledBinary(tofuVersion, true);
        if (installedBinary.isPresent()) {
            return installedBinary.get();
        }

        log.info("Downloading tofu version {} architecture {} Type {}", tofuVersion, SystemUtils.OS_ARCH,
                SystemUtils.OS_NAME);

        String defaultFileName = "tofu_%s_%s_%s.zip";

        tofuVersion = resolveTofuVersion(tofuVersion);

        log.info("Tofu version is \" {} \"", tofuVersion);
        String finalTofuVersion = tofuVersion;
        List<TofuRelease> releases = getTofuReleases().stream()
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
