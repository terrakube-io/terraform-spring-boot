package io.terrakube.terraform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerragruntDownloaderTest {

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/tf.json", exchange -> {
            byte[] response = "{\"name\":\"terraform\",\"versions\":{}}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.createContext("/tofu.json", exchange -> {
            byte[] response = "[]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.createContext("/tg.json", exchange -> {
            byte[] response = "[]".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TerraformDownloader createMockDownloader() {
        return new TerraformDownloader(baseUrl + "/tf.json", baseUrl + "/tofu.json", baseUrl + "/tg.json");
    }

    @Test
    void testResolveTerragruntVersion() throws Exception {
        TerraformDownloader downloader = createMockDownloader();

        List<TerragruntRelease> sampleReleases = new ArrayList<>();
        TerragruntRelease r1 = new TerragruntRelease();
        r1.setTag_name("v0.67.0");
        r1.setName("v0.67.0");
        sampleReleases.add(r1);

        TerragruntRelease r2 = new TerragruntRelease();
        r2.setTag_name("v0.68.0");
        r2.setName("v0.68.0");
        sampleReleases.add(r2);

        TerragruntRelease r3 = new TerragruntRelease();
        r3.setTag_name("v0.68.5");
        r3.setName("v0.68.5");
        sampleReleases.add(r3);

        TerragruntRelease r4 = new TerragruntRelease();
        r4.setTag_name("v0.74.0");
        r4.setName("v0.74.0");
        sampleReleases.add(r4);

        Field field = TerraformDownloader.class.getDeclaredField("terragruntReleases");
        field.setAccessible(true);
        field.set(downloader, sampleReleases);

        String resolvedTilde = downloader.resolveTerragruntVersion("~>0.68.0");
        assertEquals("0.68.5", resolvedTilde);

        String resolvedExact = downloader.resolveTerragruntVersion("0.74.0");
        assertEquals("0.74.0", resolvedExact);

        String resolvedExactWithV = downloader.resolveTerragruntVersion("v0.67.0");
        assertEquals("0.67.0", resolvedExactWithV);
    }

    @Test
    void testGetTerragruntBinaryPath() throws Exception {
        TerraformDownloader downloader = createMockDownloader();
        String path = downloader.getTerragruntBinaryPath("0.68.5");
        assertNotNull(path);
        assertTrue(path.contains(".terraform-spring-boot"));
        assertTrue(path.contains("terragrunt"));
        assertTrue(path.contains("0.68.5"));
    }

    @Test
    void testTerraformProcessDataWithTerragrunt() {
        TerraformProcessData processData = TerraformProcessData.builder()
                .terraformVersion("1.9.5")
                .workingDirectory(new File("/tmp/workspace"))
                .tofu(false)
                .terragrunt(true)
                .terragruntVersion("0.68.5")
                .build();

        assertTrue(processData.isTerragrunt());
        assertFalse(processData.isTofu());
        assertEquals("0.68.5", processData.getTerragruntVersion());
        assertEquals("1.9.5", processData.getTerraformVersion());
    }
}
