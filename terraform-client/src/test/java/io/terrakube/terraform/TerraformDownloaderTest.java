package io.terrakube.terraform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @BeforeEach
    void setUp() throws IOException {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/terraform.json", exchange -> {
            respond(exchange, 200, TERRAFORM_RELEASES);
        });
        server.createContext("/tofu.json", exchange -> {
            respond(exchange, 200, TOFU_RELEASES);
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        System.setProperty("user.home", originalUserHome);
    }

    @Test
    void terraformAndTofuVersionResolution() {
        TerraformDownloader downloader = new TerraformDownloader(terraformUrl(), tofuUrl());

        assertEquals("1.5.7", downloader.resolveTerraformVersion(">=1.5.0"));
        assertEquals("1.5.6", downloader.resolveTerraformVersion("1.5.6"));
        assertEquals("1.12.4", downloader.resolveTofuVersion(">=1.0.0"));
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

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        respond(exchange, status, bytes, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes, String contentType) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondRedirectWithLargeHeader(HttpExchange exchange, String location, int headerSizeBytes) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.getResponseHeaders().add("Content-Security-Policy", "connect-src " + "x".repeat(headerSizeBytes));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
}
