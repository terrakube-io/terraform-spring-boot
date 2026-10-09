package io.terrakube.terraform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            respond(exchange, tofuStatus, tofuStatus == 200 ? TOFU_RELEASES : "{}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
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

    private String terraformUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/terraform.json";
    }

    private String tofuUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/tofu.json";
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
