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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.regex.Matcher;
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

    // GitHub's release-download 302 redirect includes a Content-Security-Policy header that exceeds
    // Reactor Netty's 8 KiB default (observed ~9.2 KiB CSP / ~10.8 KiB total headers).
    static final int MAX_RESPONSE_HEADER_SIZE = 32 * 1024;

    // Cache duration for the release list, shared by all downloaders per process so consecutive commands no longer re-fetch the list every time.
    // This along with the cache also means we can fall back on the cached list if we hit the rate limit, so it should help fix a bunch of rate limit issues
    private static final Duration RELEASES_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration RELEASES_RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    // GitHub returns 30 releases per page unless asked for more (100 at most), so without paging only the
    // newest 30 tofu releases could be resolved. The page cap is a safety net against a next link that never ends.
    private static final int TOFU_RELEASES_PER_PAGE = 100;
    private static final int TOFU_RELEASES_MAX_PAGES = 20;
    // One link-value of a Link header: the URL, then its parameters up to the next link.
    private static final Pattern LINK_VALUE = Pattern.compile("<([^>]*)>([^<]*)");
    private static final Pattern LINK_REL = Pattern.compile("(?:^|;)\\s*rel\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;,]+))",
            Pattern.CASE_INSENSITIVE);

    private static final ConcurrentMap<String, CachedReleases<?>> RELEASES_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> RELEASES_LOCKS = new ConcurrentHashMap<>();
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Without timeouts a download that never completes blocks the calling thread forever. For the releases
    // list that also means holding RELEASES_LOCKS, so every other thread that needs the list hangs too.
    // The response timeout is the max idle time between reads, the download timeouts cap the whole download.
    // Connecting is already capped by Netty's default 30s connect timeout.
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_RELEASES_DOWNLOAD_TIMEOUT = Duration.ofMinutes(2);
    public static final Duration DEFAULT_BINARY_DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    // One client per process. The Reactor Netty HttpClient is thread safe, so there is no need to build a new
    // one for every download. It lives in a holder so it is only created on the first download, not when the
    // class is loaded. Reactor Netty's default event loops use daemon threads, so it does not keep the JVM alive.
    private static final class HttpClients {
        static final HttpClient CLIENT = createHttpClient();
    }

    // Makes sure we only cache the binaries for exactly specified versions, not for ranges
    private static final Pattern EXACT_VERSION = Pattern.compile("^v?\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.+-]+)?$");

    private final String terraformReleasesUrl;
    private final String tofuReleasesUrl;
    private final Duration responseTimeout;
    private final Duration releasesDownloadTimeout;
    private final Duration binaryDownloadTimeout;
    // null means the shared client, only the tests pass their own.
    private final HttpClient httpClient;
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
    private interface ReleasesFetcher {
        /**
         * @return false when only part of the list could be downloaded, so it is cached only briefly
         */
        boolean download(String releasesUrl, File releasesFile) throws IOException;
    }

    @FunctionalInterface
    private interface ReleasesParser<T> {
        T parse(File releasesFile) throws IOException;
    }

    public TerraformDownloader() {
        this(TERRAFORM_RELEASES_URL, TOFU_RELEASES_URL);
    }

    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl) {
        this(terraformReleasesUrl, tofuReleasesUrl, null, null, null);
    }

    /**
     * @param responseTimeout         max idle time between reads of a response, {@code null} for {@link #DEFAULT_RESPONSE_TIMEOUT}
     * @param releasesDownloadTimeout max time to download a releases list, {@code null} for {@link #DEFAULT_RELEASES_DOWNLOAD_TIMEOUT}
     * @param binaryDownloadTimeout   max time to download a binary archive, {@code null} for {@link #DEFAULT_BINARY_DOWNLOAD_TIMEOUT}
     */
    public TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl, Duration responseTimeout,
                               Duration releasesDownloadTimeout, Duration binaryDownloadTimeout) {
        this(terraformReleasesUrl, tofuReleasesUrl, responseTimeout, releasesDownloadTimeout, binaryDownloadTimeout, null);
    }

    // For the tests: the shared client reads the proxy system properties once, when it is built.
    TerraformDownloader(String terraformReleasesUrl, String tofuReleasesUrl, Duration responseTimeout,
                        Duration releasesDownloadTimeout, Duration binaryDownloadTimeout, HttpClient httpClient) {
        log.info("Initialize TerraformDownloader using custom URL");
        log.debug("Terraform releases URL: {}", terraformReleasesUrl);
        log.debug("Tofu releases URL: {}", tofuReleasesUrl);

        this.terraformReleasesUrl = terraformReleasesUrl;
        this.tofuReleasesUrl = tofuReleasesUrl;
        this.responseTimeout = responseTimeout != null ? responseTimeout : DEFAULT_RESPONSE_TIMEOUT;
        this.releasesDownloadTimeout = releasesDownloadTimeout != null ? releasesDownloadTimeout : DEFAULT_RELEASES_DOWNLOAD_TIMEOUT;
        this.binaryDownloadTimeout = binaryDownloadTimeout != null ? binaryDownloadTimeout : DEFAULT_BINARY_DOWNLOAD_TIMEOUT;
        this.httpClient = httpClient;
        log.debug("Download timeouts: response {}, releases {}, binary {}", this.responseTimeout,
                this.releasesDownloadTimeout, this.binaryDownloadTimeout);

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
        return getCachedReleases("terraform", terraformReleasesUrl, this::downloadReleasesToFile, releasesFile -> {
            TerraformResponse response = OBJECT_MAPPER.readValue(releasesFile, TerraformResponse.class);
            if (response == null || response.getVersions() == null) {
                throw new IOException("Terraform releases response has no versions");
            }
            log.info("Found {} terraform releases", response.getVersions().size());
            return response;
        }, null);
    }

    private List<TofuRelease> getTofuReleases() {
        return getCachedReleases("tofu", tofuReleasesUrl, this::downloadTofuReleasesToFile, releasesFile -> {
            List<TofuRelease> releases = readTofuReleases(releasesFile);
            log.info("Found {} tofu releases", releases.size());
            return releases;
        }, TerraformDownloader::mergeTofuReleases);
    }

    // The partial pages hold the newest releases, the previous list the older ones. A fetched entry wins over a
    // cached one with the same version.
    private static List<TofuRelease> mergeTofuReleases(List<TofuRelease> partial, List<TofuRelease> previous) {
        List<TofuRelease> merged = new ArrayList<>(partial);
        Set<String> versions = new HashSet<>();
        for (TofuRelease release : partial) {
            String version = tofuReleaseVersion(release);
            if (version != null) {
                versions.add(withoutVPrefix(version));
            }
        }
        for (TofuRelease release : previous) {
            String version = tofuReleaseVersion(release);
            if (version != null && versions.add(withoutVPrefix(version))) {
                merged.add(release);
            }
        }
        log.info("Merged {} fetched tofu releases with the previous list into {} releases", partial.size(), merged.size());
        return merged;
    }

    private static List<TofuRelease> readTofuReleases(File releasesFile) throws IOException {
        List<TofuRelease> releases = OBJECT_MAPPER.readValue(releasesFile,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, TofuRelease.class));
        if (releases == null) {
            throw new IOException("Tofu releases response is empty");
        }
        return releases;
    }

    @SuppressWarnings("unchecked")
    /**
     * @param merger combines a partial list (first argument) with the previous list, {@code null} for a list
     *               that is never partial
     */
    private static <T> T getCachedReleases(String product, String releasesUrl, ReleasesFetcher fetcher,
                                           ReleasesParser<T> parser, BinaryOperator<T> merger) {
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
                CachedReleases<T> fetched = fetchReleases(product, releasesUrl, fetcher, parser, merger, cached);
                RELEASES_CACHE.put(key, fetched);
                return fetched.releases();
            } catch (Exception | OutOfMemoryError e) {
                // OutOfMemoryError is caught on purpose: Netty throws io.netty.util.internal.OutOfDirectMemoryError
                // when direct memory runs out. That says nothing about the heap, and failing here (or falling
                // back to the previous list) is better than letting it escape and leaving the job in a broken state.
                if (cached != null) {
                    log.warn("Error fetching {} releases from {}, using the previous list: {}", product, releasesUrl, e.getMessage());
                    RELEASES_CACHE.put(key, new CachedReleases<>(cached.releases(), Instant.now().plus(RELEASES_RETRY_AFTER_FAILURE)));
                    return cached.releases();
                }
                throw new IllegalStateException(String.format("Unable to fetch %s releases from %s", product, releasesUrl), e);
            }
        }
    }

    private static <T> CachedReleases<T> fetchReleases(String product, String releasesUrl, ReleasesFetcher fetcher,
                                                       ReleasesParser<T> parser, BinaryOperator<T> merger,
                                                       CachedReleases<T> previous) throws IOException {
        log.info("Downloading {} releases list from {}", product, releasesUrl);
        Path tempDirectory = Files.createDirectories(
                Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString()));
        try {
            File releasesFile = tempDirectory.resolve(product + "-releases.json").toFile();
            boolean complete = fetcher.download(releasesUrl, releasesFile);
            T releases = parser.parse(releasesFile);
            if (complete) {
                return new CachedReleases<>(releases, Instant.now().plus(RELEASES_CACHE_TTL));
            }
            // A partial list must not drop what the previous list had, and it is refreshed as soon as a
            // failed fetch would be.
            if (previous != null && merger != null) {
                releases = merger.apply(releases, previous.releases());
            }
            return new CachedReleases<>(releases, Instant.now().plus(RELEASES_RETRY_AFTER_FAILURE));
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

    // proxyWithSystemProperties() reads http(s).proxyHost/proxyPort/proxyUser/proxyPassword, http.nonProxyHosts
    // and socksProxyHost once, when the client is built. For the shared client that is the first download.
    static HttpClient createHttpClient() {
        return HttpClient.create()
                .httpResponseDecoder(spec -> spec.maxHeaderSize(MAX_RESPONSE_HEADER_SIZE))
                .followRedirect(true)
                .proxyWithSystemProperties();
    }

    private boolean downloadReleasesToFile(String releasesUrl, File releasesFile) throws IOException {
        downloadToFile(releasesUrl, releasesFile, "releases-downloader", "application/json", releasesDownloadTimeout);
        return true;
    }

    /**
     * Downloads the whole tofu releases list into the file, following the {@code Link: <...>; rel="next"}
     * header page by page. A response without a next link, such as a mirror serving a static file, is the
     * whole list and is kept as downloaded. The releases download timeout caps all pages together, not each
     * page, so paging never holds the RELEASES_LOCKS longer than a single download could before.
     * If a later page fails (e.g. the rate limit is hit halfway), the pages read so far are kept and reported
     * as a partial list; only a failure of the first page fails the download.
     */
    private boolean downloadTofuReleasesToFile(String releasesUrl, File releasesFile) throws IOException {
        Instant deadline = Instant.now().plus(releasesDownloadTimeout);
        String pageUrl = withTofuReleasesPerPage(releasesUrl);
        Set<String> visitedPageUrls = new HashSet<>();
        visitedPageUrls.add(pageUrl);
        List<TofuRelease> releases = new ArrayList<>();
        boolean complete = true;
        for (int page = 1; ; page++) {
            // The URL the page came from in the end, after any redirects.
            String responseUrl = pageUrl;
            String nextPageUrl;
            try {
                Duration remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new IOException(String.format("Download of %s did not complete within %s", releasesUrl,
                            releasesDownloadTimeout));
                }
                FinalResponse response = downloadToFile(pageUrl, releasesFile, "releases-downloader",
                        "application/json", remaining);
                if (response.url != null) {
                    responseUrl = response.url;
                }
                nextPageUrl = nextPageUrl(responseUrl, String.join(",", response.headers.getAll("Link")));
                if (page == 1 && nextPageUrl == null) {
                    return true;
                }
                releases.addAll(readTofuReleases(releasesFile));
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                if (page == 1) {
                    throw e;
                }
                log.warn("Unable to read page {} of the tofu releases from {}, using the {} releases read so far: {}",
                        page, pageUrl, releases.size(), e.getMessage());
                complete = false;
                break;
            }
            if (nextPageUrl == null) {
                break;
            }
            if (!visitedPageUrls.add(nextPageUrl)) {
                log.warn("Stopped reading tofu releases from {}: page {} links back to {}", releasesUrl, page, nextPageUrl);
                break;
            }
            if (responseUrl.regionMatches(true, 0, "https:", 0, 6) && nextPageUrl.regionMatches(true, 0, "http:", 0, 5)) {
                log.warn("Stopped reading tofu releases from {}: page {} links from https to {}", releasesUrl, page, nextPageUrl);
                break;
            }
            if (page == TOFU_RELEASES_MAX_PAGES) {
                log.warn("Stopped reading tofu releases from {} after {} pages, older releases are not listed",
                        releasesUrl, TOFU_RELEASES_MAX_PAGES);
                break;
            }
            pageUrl = nextPageUrl;
        }
        OBJECT_MAPPER.writeValue(releasesFile, releases);
        return complete;
    }

    /**
     * Asks GitHub for its largest page, unless the URL already chose a page size. Any other URL is requested
     * exactly as configured: a pre-signed S3/GCS URL signs its query string and would be rejected otherwise.
     */
    private static String withTofuReleasesPerPage(String releasesUrl) {
        URI uri;
        try {
            uri = new URI(releasesUrl);
        } catch (URISyntaxException e) {
            // Netty may still accept it, so it is used as it is.
            return releasesUrl;
        }
        String path = uri.getRawPath();
        boolean gitHub = "api.github.com".equalsIgnoreCase(uri.getHost()) || (path != null && path.startsWith("/api/v3/"));
        String query = uri.getRawQuery();
        if (!gitHub || (query != null && Arrays.stream(query.split("&"))
                .anyMatch(p -> p.equals("per_page") || p.startsWith("per_page=")))) {
            return releasesUrl;
        }
        int fragmentStart = releasesUrl.indexOf('#');
        String withoutFragment = fragmentStart < 0 ? releasesUrl : releasesUrl.substring(0, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : releasesUrl.substring(fragmentStart);
        String separator = query == null ? "?" : query.isEmpty() ? "" : "&";
        return withoutFragment + separator + "per_page=" + TOFU_RELEASES_PER_PAGE + fragment;
    }

    // Finds the link whose rel has the "next" token. A relative link is resolved against the page it came from.
    private static String nextPageUrl(String pageUrl, String linkHeader) {
        Matcher link = LINK_VALUE.matcher(linkHeader);
        while (link.find()) {
            Matcher rel = LINK_REL.matcher(link.group(2));
            if (rel.find()) {
                String relValue = rel.group(1) != null ? rel.group(1) : rel.group(2);
                if (Arrays.stream(relValue.trim().split("\\s+")).anyMatch("next"::equalsIgnoreCase)) {
                    try {
                        return new URI(pageUrl).resolve(link.group(1).trim()).toString();
                    } catch (URISyntaxException | IllegalArgumentException e) {
                        log.warn("Ignoring next page link {} from {}: {}", link.group(1), pageUrl, e.getMessage());
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static final class FinalResponse {
        private final String url;
        private final HttpHeaders headers;

        FinalResponse(String url, HttpHeaders headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * Streams the response body to the file. Netty's response timeout cuts a response that sends no data for
     * {@code responseTimeout}, and the whole download is cancelled after {@code downloadTimeout}, so a stuck
     * server can never block the caller (or the RELEASES_LOCKS it holds). Cancelling closes the connection.
     *
     * @return the URL and headers of the final response, after any redirects
     */
    private FinalResponse downloadToFile(String url, File file, String userAgent, String accept, Duration downloadTimeout)
            throws IOException {
        // Derived per call so the shared client can serve downloaders with different settings.
        HttpClient client = (httpClient != null ? httpClient : HttpClients.CLIENT)
                .responseTimeout(responseTimeout)
                .headers(h -> h.set(HttpHeaderNames.USER_AGENT, userAgent).set(HttpHeaderNames.ACCEPT, accept));

        AtomicReference<FinalResponse> finalResponse = new AtomicReference<>();
        Mono<Void> download = client.get()
                .uri(url)
                .response((response, body) -> {
                    int status = response.status().code();
                    // An error page is not a releases list or an archive, so it is never written to the file.
                    if (status < 200 || status >= 300) {
                        return Mono.<Void>error(new IOException(String.format("GET %s failed with HTTP status %d", url, status)));
                    }
                    finalResponse.set(new FinalResponse(response.resourceUrl(), response.responseHeaders()));
                    return Mono.using(
                            () -> FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING),
                            // Each ByteBuf is written within onNext; Reactor Netty releases it once onNext returns.
                            channel -> body.handle((buffer, sink) -> {
                                try {
                                    while (buffer.isReadable()) {
                                        buffer.readBytes(channel, buffer.readableBytes());
                                    }
                                } catch (IOException e) {
                                    sink.error(e);
                                }
                            }).then(),
                            TerraformDownloader::closeQuietly);
                })
                .then()
                // Cancelling on timeout disposes the request, which closes the connection.
                .timeout(downloadTimeout, Mono.error(() -> new IOException(
                        String.format("Download of %s did not complete within %s", url, downloadTimeout))));

        try {
            download.block();
            return finalResponse.get();
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            if (cause instanceof InterruptedException) {
                // block() cancels the download when interrupted.
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while downloading " + url);
            }
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof ReadTimeoutException) {
                throw new IOException(String.format("No data received from %s for %s", url, responseTimeout), cause);
            }
            throw new IOException("Unable to download " + url, cause);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.warn("Unable to close {}: {}", channel, e.getMessage());
        }
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
        try {
            downloadToFile(zipReleaseUrl, zipFile, "terraform-downloader", "application/octet-stream, */*",
                    binaryDownloadTimeout);
        } catch (IOException | RuntimeException | OutOfMemoryError exception) {
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
        String trimmed = version.trim();
        File binaryFile = new File(getTerraformBinaryPath(trimmed, tofu));
        if (isUsableBinary(binaryFile)) {
            log.info("{} {} already installed at {}", tofu ? "tofu" : "terraform", version, binaryFile);
            return Optional.of(binaryFile.getAbsolutePath());
        }
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            String stripped = trimmed.substring(1);
            File strippedBinaryFile = new File(getTerraformBinaryPath(stripped, tofu));
            if (isUsableBinary(strippedBinaryFile)) {
                log.info("{} {} already installed at {}", tofu ? "tofu" : "terraform", stripped, strippedBinaryFile);
                return Optional.of(strippedBinaryFile.getAbsolutePath());
            }
        } else {
            File vPrefixedBinaryFile = new File(getTerraformBinaryPath("v" + trimmed, tofu));
            if (isUsableBinary(vPrefixedBinaryFile)) {
                log.info("{} {} already installed at {}", tofu ? "tofu" : "terraform", "v" + trimmed, vPrefixedBinaryFile);
                return Optional.of(vPrefixedBinaryFile.getAbsolutePath());
            }
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
        Set<String> allTofuKeys = getTofuReleases().stream()
                .filter(release -> !release.isDraft())
                .map(TerraformDownloader::tofuReleaseVersion)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        try {
            RangeList versionRangeList = RangeListFactory.create(tofuVersion);

            return allTofuKeys.stream()
                    .filter(v -> {
                        try {
                            Semver tempVersion = new Semver(withoutVPrefix(v));
                            return tempVersion.satisfies(versionRangeList);
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparing((String v) -> new Semver(withoutVPrefix(v))))
                    .orElseThrow(() -> new IllegalArgumentException("Not valid tofu version format"));
        } catch (Exception e) {
            log.error("Error parsing tofu version range: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid tofu version range");
        }
    }

    // The tag is the version a release is published as, its name is free text that can differ. The resolved
    // version stays the raw tag (e.g. "v1.13.9"), so the install directory is the same as it has always been.
    // A mirror that only serves names keeps working.
    private static String tofuReleaseVersion(TofuRelease release) {
        String tag = release.getTag_name();
        return tag != null && !tag.isBlank() ? tag.trim() : release.getName();
    }

    private static String withoutVPrefix(String version) {
        String trimmed = version.trim();
        return trimmed.startsWith("v") || trimmed.startsWith("V") ? trimmed.substring(1) : trimmed;
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
        String finalTofuVersion = withoutVPrefix(tofuVersion);
        // The first match, not exactly one: a release published while the pages were read shows up on two pages.
        TofuRelease release = getTofuReleases().stream()
                .filter(r -> !r.isDraft())
                .filter(r -> {
                    String version = tofuReleaseVersion(r);
                    return version != null && withoutVPrefix(version).equals(finalTofuVersion);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid Tofu Version"));

        List<TofuAsset> assets = release.getAssets().stream().filter(asset -> asset.getName().endsWith(".zip"))
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
    private String tag_name;
    private boolean draft;
    private List<TofuAsset> assets;
}

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
class TofuAsset {
    private String name;
    private String browser_download_url;
}
