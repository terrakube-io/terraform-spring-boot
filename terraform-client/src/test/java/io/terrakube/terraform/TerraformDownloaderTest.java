package io.terrakube.terraform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the new process-wide release cache and "binary exists" check
 * Runs a local HTTP server that counts requests, and user.home points at a temp directory so the
 * downloader's install directories don't use the actual home dir.
 */
class TerraformDownloaderTest {

    private static final String TERRAFORM_RELEASES = "{\"name\":\"terraform\",\"versions\":{"
            + "\"1.5.6\":{\"name\":\"terraform\",\"version\":\"1.5.6\",\"builds\":[]},"
            + "\"1.5.7\":{\"name\":\"terraform\",\"version\":\"1.5.7\",\"builds\":[]}"
            + "}}";

    private static final String TOFU_RELEASES = "[{\"name\":\"1.11.0\",\"assets\":[]},{\"name\":\"1.12.4\",\"assets\":[]}]";

    @TempDir
    Path home;

    private String originalUserHome;
    private HttpServer server;
    private final AtomicInteger terraformRequests = new AtomicInteger();
    private final AtomicInteger tofuRequests = new AtomicInteger();
    private volatile int tofuStatus = 200;
    private volatile boolean tofuHangs = false;
    private final CountDownLatch releaseHangingRequests = new CountDownLatch(1);
    private ExecutorService serverExecutor;

    @BeforeEach
    void setUp() throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        TerraformDownloader.clearReleasesCache();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/terraform.json", exchange -> {
            terraformRequests.incrementAndGet();
            respond(exchange, 200, TERRAFORM_RELEASES);
        });
        server.createContext("/tofu.json", exchange -> {
            tofuRequests.incrementAndGet();
            if (tofuHangs) {
                // Never answer, like a fetch whose response never completes.
                try {
                    releaseHangingRequests.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            respond(exchange, tofuStatus, tofuStatus == 200 ? TOFU_RELEASES : "{}");
        });
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
    }

    @AfterEach
    void tearDown() {
        releaseHangingRequests.countDown();
        server.stop(0);
        serverExecutor.shutdownNow();
        TerraformDownloader.clearReleasesCache();
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void releasesListIsFetchedOnceAndOnlyForTheToolInUse() {
        new TerraformDownloader(terraformUrl(), tofuUrl()).resolveTofuVersion(">=1.0.0");
        String resolved = new TerraformDownloader(terraformUrl(), tofuUrl()).resolveTofuVersion(">=1.0.0");

        assertEquals("1.12.4", resolved);
        assertEquals(1, tofuRequests.get());
        assertEquals(0, terraformRequests.get());
    }

    @Test
    void terraformVersionIsResolvedFromTheCachedList() {
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());

        assertEquals("1.5.7", downloader.resolveTerraformVersion(">=1.5.0"));
        assertEquals("1.5.6", downloader.resolveTerraformVersion("1.5.6"));
        assertEquals(1, terraformRequests.get());
    }

    @Test
    void previousListIsUsedWhenARefreshFails() {
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());
        downloader.resolveTofuVersion(">=1.0.0");

        TerraformDownloader.expireReleasesCache();
        tofuStatus = 500;

        assertEquals("1.12.4", downloader.resolveTofuVersion(">=1.0.0"));
        // The failed refresh pushes the next attempt back, so this call does not hit the endpoint.
        assertEquals("1.12.4", downloader.resolveTofuVersion(">=1.0.0"));
        assertEquals(2, tofuRequests.get());
    }

    @Test
    void failingFetchWithoutAPreviousListIsReported() {
        tofuStatus = 500;
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());

        assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0"));
    }

    @Test
    void hangingFetchWithoutAPreviousListFailsInsteadOfBlockingForever() {
        tofuHangs = true;
        TerraformDownloader downloader = downloaderWithShortReleasesTimeout();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
    }

    @Test
    void previousListIsUsedWhenARefreshHangs() {
        TerraformDownloader downloader = downloaderWithShortReleasesTimeout();
        downloader.resolveTofuVersion(">=1.0.0");

        TerraformDownloader.expireReleasesCache();
        tofuHangs = true;

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertEquals("1.12.4", downloader.resolveTofuVersion(">=1.0.0")));
        assertEquals(2, tofuRequests.get());
    }

    @Test
    void hangingResponseIsCutByTheResponseTimeout() {
        tofuHangs = true;
        // Only the response timeout is short, so this proves it is applied to the request.
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl(),
                Duration.ofMillis(500), Duration.ofMinutes(5), null);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
    }

    @Test
    void bodyThatStallsAfterTheFirstBytesIsCutByTheResponseTimeout() {
        server.createContext("/stalling.json", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("[{\"name\":".getBytes(StandardCharsets.UTF_8));
            out.flush();
            awaitRelease();
            exchange.close();
        });
        // Headers arrive at once, so this proves the response timeout also applies between reads of the body.
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), url("/stalling.json"),
                Duration.ofMillis(500), Duration.ofMinutes(5), null);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
    }

    @Test
    void bodyThatKeepsTricklingIsCutByTheDownloadTimeout() throws InterruptedException {
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        server.createContext("/trickling.json", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            try {
                // One byte every 100ms is never idle for long, only the cap on the whole download can stop it.
                while (!releaseHangingRequests.await(100, TimeUnit.MILLISECONDS)) {
                    out.write(' ');
                    out.flush();
                }
            } catch (IOException e) {
                clientDisconnected.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), url("/trickling.json"),
                Duration.ofMinutes(5), Duration.ofSeconds(1), null);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
        // The download is aborted, not just abandoned: the connection gets closed.
        assertTrue(clientDisconnected.await(10, TimeUnit.SECONDS), "Connection should be closed after the timeout");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void binaryDownloadThatStallsFailsAndRemovesThePartialArchive() {
        server.createContext("/stalling.zip", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(new byte[]{'P', 'K', 3, 4});
            out.flush();
            awaitRelease();
            exchange.close();
        });
        String tofuReleasesUrl = tofuReleasesWithAsset("1.13.0", url("/stalling.zip"));
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuReleasesUrl,
                Duration.ofMillis(500), null, Duration.ofMinutes(5));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(IOException.class, () -> downloader.downloadTofuVersion("1.13.0")));
        File zipFile = home.resolve(".terraform-spring-boot/download/tofu/" + tofuAssetName("1.13.0")).toFile();
        assertFalse(zipFile.exists(), "Partial archive should be deleted");
    }

    @Test
    void interruptStopsAHangingDownloadAndKeepsTheInterruptFlag() throws InterruptedException {
        tofuHangs = true;
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl(),
                Duration.ofMinutes(5), Duration.ofMinutes(5), null);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> interruptFlag = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                downloader.resolveTofuVersion(">=1.0.0");
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptFlag.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        while (tofuRequests.get() == 0 && caller.isAlive()) {
            Thread.sleep(10);
        }
        caller.interrupt();
        caller.join(10_000);

        assertFalse(caller.isAlive(), "Interrupted download should return");
        assertTrue(thrown.get() instanceof IllegalStateException, "Expected IllegalStateException but got " + thrown.get());
        assertTrue(interruptFlag.get(), "Interrupt flag should be kept");
    }

    @Test
    void redirectIsFollowedWithTheDownloaderHeaders() {
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        server.createContext("/moved.json", exchange -> {
            exchange.getResponseHeaders().add("Location", url("/target.json"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target.json", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            respond(exchange, 200, TOFU_RELEASES);
        });

        String resolved = new TerraformDownloader(terraformUrl(), url("/moved.json")).resolveTofuVersion(">=1.0.0");

        assertEquals("1.12.4", resolved);
        assertEquals("releases-downloader", userAgent.get());
        assertEquals("application/json", accept.get());
    }

    @Test
    void nonSuccessStatusIsReportedWithTheStatusAndUrl() {
        tofuStatus = 404;
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0"));
        String message = e.getCause().getMessage();
        assertTrue(message.contains("404") && message.contains(tofuUrl()), message);
    }

    @Test
    void plainHttpDownloadGoesThroughTheProxyWithTheProxyUserProperties() throws Exception {
        try (ConnectProxyStub proxy = new ConnectProxyStub(TOFU_RELEASES)) {
            withProxyProperties(httpProxyProperties(proxy.port()), () -> {
                // Not served by the test server: only the proxy can answer this request.
                String target = "http://downloads.example.invalid/tofu.json";
                // The shared client may already exist with other proxy settings, so build one now.
                TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), target, null, null, null,
                        TerraformDownloader.createHttpClient());

                assertEquals("1.12.4", downloader.resolveTofuVersion(">=1.0.0"));
            });

            assertEquals(List.of("CONNECT downloads.example.invalid:80 HTTP/1.1"), proxy.requestLines);
            // Netty sends the credentials with the first CONNECT, without waiting for a 407.
            assertEquals(List.of(PROXY_AUTHORIZATION), proxy.authorizations);
        }
    }

    @Test
    void httpsDownloadUsesTheHttpProxyPropertiesAndSendsBasicAuthOnConnect() throws Exception {
        try (ConnectProxyStub proxy = new ConnectProxyStub(null)) {
            withProxyProperties(httpProxyProperties(proxy.port()), () -> {
                TerraformDownloader downloader = new TerraformDownloader(terraformUrl(),
                        "https://downloads.example.invalid/tofu.json", null, null, null,
                        TerraformDownloader.createHttpClient());

                // The stub closes the tunnel, so the download fails. Only the CONNECT matters here.
                assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                        assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
            });

            // Only http.proxy* is set, so this proves the fallback to the http proxy for https targets.
            // Reactor Netty retries once when the connection closes before a response, hence possibly two CONNECTs.
            assertFalse(proxy.requestLines.isEmpty(), "The CONNECT should reach the proxy");
            for (int i = 0; i < proxy.requestLines.size(); i++) {
                assertEquals("CONNECT downloads.example.invalid:443 HTTP/1.1", proxy.requestLines.get(i));
                assertEquals(PROXY_AUTHORIZATION, proxy.authorizations.get(i));
            }
        }
    }

    @Test
    void httpsDownloadPrefersTheHttpsProxyPropertiesOverTheHttpOnes() throws Exception {
        try (ConnectProxyStub httpProxy = new ConnectProxyStub(null);
             ConnectProxyStub httpsProxy = new ConnectProxyStub(null)) {
            Map<String, String> properties = new HashMap<>();
            properties.put("http.proxyHost", "127.0.0.1");
            properties.put("http.proxyPort", String.valueOf(httpProxy.port()));
            properties.put("http.proxyUser", "http-user");
            properties.put("http.proxyPassword", "http-secret");
            properties.put("https.proxyHost", "127.0.0.1");
            properties.put("https.proxyPort", String.valueOf(httpsProxy.port()));
            properties.put("https.proxyUser", "proxy-user");
            properties.put("https.proxyPassword", "proxy-secret");
            withProxyProperties(properties, () -> {
                TerraformDownloader downloader = new TerraformDownloader(terraformUrl(),
                        "https://downloads.example.invalid/tofu.json", null, null, null,
                        TerraformDownloader.createHttpClient());

                // The stub closes the tunnel, so the download fails. Only the CONNECT matters here.
                assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                        assertThrows(IllegalStateException.class, () -> downloader.resolveTofuVersion(">=1.0.0")));
            });

            assertTrue(httpProxy.requestLines.isEmpty(), "The http proxy should not be used for https: " + httpProxy.requestLines);
            assertFalse(httpsProxy.requestLines.isEmpty(), "The CONNECT should reach the https proxy");
            String httpCredentials = "Basic "
                    + Base64.getEncoder().encodeToString("http-user:http-secret".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < httpsProxy.requestLines.size(); i++) {
                assertEquals("CONNECT downloads.example.invalid:443 HTTP/1.1", httpsProxy.requestLines.get(i));
                assertEquals(PROXY_AUTHORIZATION, httpsProxy.authorizations.get(i));
                assertFalse(httpCredentials.equals(httpsProxy.authorizations.get(i)), "http.* credentials must not be sent");
            }
        }
    }

    private static final String PROXY_AUTHORIZATION = "Basic "
            + Base64.getEncoder().encodeToString("proxy-user:proxy-secret".getBytes(StandardCharsets.UTF_8));

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // http.proxy* pointing at the stub with proxy-user:proxy-secret, https.proxy* left unset.
    private static Map<String, String> httpProxyProperties(int proxyPort) {
        Map<String, String> properties = new HashMap<>();
        properties.put("http.proxyHost", "127.0.0.1");
        properties.put("http.proxyPort", String.valueOf(proxyPort));
        properties.put("http.proxyUser", "proxy-user");
        properties.put("http.proxyPassword", "proxy-secret");
        return properties;
    }

    // Clears every http(s).proxy* property, sets the given ones while the action runs, then restores them all.
    private static void withProxyProperties(Map<String, String> properties, ThrowingRunnable action) throws Exception {
        String[] keys = {"http.proxyHost", "http.proxyPort", "http.proxyUser", "http.proxyPassword",
                "https.proxyHost", "https.proxyPort", "https.proxyUser", "https.proxyPassword"};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = System.getProperty(keys[i]);
            System.clearProperty(keys[i]);
        }
        try {
            properties.forEach(System::setProperty);
            action.run();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    /**
     * A minimal HTTP proxy. It records every request line and Proxy-Authorization header, answers 407 unless
     * the request is a CONNECT with the expected credentials, and otherwise opens the tunnel. With a body it
     * then answers the tunnelled request itself, without one it closes the tunnel.
     */
    private static final class ConnectProxyStub implements AutoCloseable {
        final List<String> requestLines = new CopyOnWriteArrayList<>();
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        private final ServerSocket serverSocket;
        private final String tunnelledBody;

        ConnectProxyStub(String tunnelledBody) throws IOException {
            this.tunnelledBody = tunnelledBody;
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "connect-proxy-stub");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(10_000);
                    handle(socket);
                } catch (IOException e) {
                    // Closed by close(), or the client went away.
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            String authorization = readHeaders(in).get("proxy-authorization");
            requestLines.add(requestLine);
            authorizations.add(String.valueOf(authorization));
            if (!requestLine.startsWith("CONNECT ") || !PROXY_AUTHORIZATION.equals(authorization)) {
                write(out, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            write(out, "HTTP/1.1 200 Connection established\r\n\r\n");
            if (tunnelledBody == null || in.readLine() == null) {
                return;
            }
            readHeaders(in);
            byte[] body = tunnelledBody.getBytes(StandardCharsets.UTF_8);
            write(out, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n");
            out.write(body);
            out.flush();
        }

        private static Map<String, String> readHeaders(BufferedReader in) throws IOException {
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                }
            }
            return headers;
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    private TerraformDownloader downloaderWithShortReleasesTimeout() {
        return new TerraformDownloader(terraformUrl(), tofuUrl(), null, Duration.ofMillis(500), null);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void installedBinaryIsUsedWithoutFetchingReleasesOrDownloading() throws IOException {
        File binary = home.resolve(".terraform-spring-boot/tofu/v1.12.4/tofu").toFile();
        Files.createDirectories(binary.getParentFile().toPath());
        Files.writeString(binary.toPath(), "#!/bin/sh\n");
        binary.setExecutable(true, true);

        String path = new TerraformDownloader(terraformUrl(), tofuUrl()).downloadTofuVersion("v1.12.4");

        assertEquals(binary.getAbsolutePath(), path);
        assertEquals(0, tofuRequests.get());
        assertEquals(0, terraformRequests.get());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void installedBinaryWithVPrefixIsFoundWhenRequestedWithoutPrefix() throws IOException {
        File binary = home.resolve(".terraform-spring-boot/tofu/v1.12.4/tofu").toFile();
        Files.createDirectories(binary.getParentFile().toPath());
        Files.writeString(binary.toPath(), "#!/bin/sh\n");
        binary.setExecutable(true, true);

        String path = new TerraformDownloader(terraformUrl(), tofuUrl()).downloadTofuVersion("1.12.4");

        assertEquals(binary.getAbsolutePath(), path);
        assertEquals(0, tofuRequests.get());
        assertEquals(0, terraformRequests.get());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void installedBinaryWithoutVPrefixIsFoundWhenRequestedWithPrefix() throws IOException {
        File binary = home.resolve(".terraform-spring-boot/tofu/1.12.4/tofu").toFile();
        Files.createDirectories(binary.getParentFile().toPath());
        Files.writeString(binary.toPath(), "#!/bin/sh\n");
        binary.setExecutable(true, true);

        String path = new TerraformDownloader(terraformUrl(), tofuUrl()).downloadTofuVersion("v1.12.4");

        assertEquals(binary.getAbsolutePath(), path);
        assertEquals(0, tofuRequests.get());
        assertEquals(0, terraformRequests.get());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void downloadTofuVersionFollowsRedirectWithHeadersAbove8KiB() throws IOException {
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());
        String os = downloader.getOs();
        String arch = "aarch64".equals(SystemUtils.OS_ARCH) ? "arm64" : SystemUtils.OS_ARCH;

        byte[] zipBytes = createZipWithEntry("tofu", "#!/bin/sh\necho tofu\n");

        server.createContext("/tofu-asset.zip", exchange -> {
            respond(exchange, 200, zipBytes, "application/octet-stream");
        });

        server.createContext("/tofu-redirect.zip", exchange -> {
            // Observed GitHub release-download redirect header size is ~10,790 bytes total,
            // with a Content-Security-Policy header value of ~9,217 bytes.
            respondRedirectWithLargeHeader(exchange, "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu-asset.zip", 10_000);
        });

        String assetUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu-redirect.zip";
        String customTofuReleases = "[{\"name\":\"1.13.0\",\"assets\":[{\"name\":\"tofu_1.13.0_" + os + "_" + arch + ".zip\",\"browser_download_url\":\"" + assetUrl + "\"}]}]";

        server.createContext("/tofu-custom.json", exchange -> {
            respond(exchange, 200, customTofuReleases);
        });

        String customTofuUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu-custom.json";
        String downloadedBinaryPath = new TerraformDownloader(terraformUrl(), customTofuUrl).downloadTofuVersion("1.13.0");

        File binary = new File(downloadedBinaryPath);
        assertTrue(binary.exists(), "Downloaded binary file should exist");
        assertTrue(binary.canExecute(), "Downloaded binary file should be executable");
    }

    @Test
    void releaseIndexFetchFollowsRedirectWithHeadersAbove8KiB() {
        server.createContext("/tofu-redirect.json", exchange -> {
            respondRedirectWithLargeHeader(exchange, tofuUrl(), 10_000);
        });

        String redirectUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu-redirect.json";
        String resolved = new TerraformDownloader(terraformUrl(), redirectUrl).resolveTofuVersion(">=1.0.0");

        assertEquals("1.12.4", resolved);
    }

    private void awaitRelease() {
        try {
            releaseHangingRequests.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String tofuAssetName(String version) {
        String os = new TerraformDownloader(terraformUrl(), tofuUrl()).getOs();
        String arch = "aarch64".equals(SystemUtils.OS_ARCH) ? "arm64" : SystemUtils.OS_ARCH;
        return "tofu_" + version + "_" + os + "_" + arch + ".zip";
    }

    // Serves a releases list with one release whose asset for this platform is at assetUrl, returns its URL.
    private String tofuReleasesWithAsset(String version, String assetUrl) {
        String releases = "[{\"name\":\"" + version + "\",\"assets\":[{\"name\":\"" + tofuAssetName(version)
                + "\",\"browser_download_url\":\"" + assetUrl + "\"}]}]";
        server.createContext("/tofu-" + version + ".json", exchange -> respond(exchange, 200, releases));
        return url("/tofu-" + version + ".json");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private String terraformUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/terraform.json";
    }

    private String tofuUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu.json";
    }

    private static byte[] createZipWithEntry(String entryName, String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        respond(exchange, status, bytes, "application/json");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondRedirectWithLargeHeader(com.sun.net.httpserver.HttpExchange exchange, String location, int headerSizeBytes) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.getResponseHeaders().add("Content-Security-Policy", "connect-src " + "x".repeat(headerSizeBytes));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
