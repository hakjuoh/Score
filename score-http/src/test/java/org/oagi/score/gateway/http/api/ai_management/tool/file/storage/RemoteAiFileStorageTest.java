package org.oagi.score.gateway.http.api.ai_management.tool.file.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.GoogleDriveAiFileStorage;
import org.oagi.score.gateway.http.api.ai_management.tool.file.storage.S3AiFileStorage;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteAiFileStorageTest {

    @Test
    void googleDriveStoresLoadsAndIdempotentlyDeletesByOpaqueFileId() throws Exception {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload/drive/v3/files", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            stored.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, "{\"id\":\"file_1\"}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/drive/v3/files/file_1", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "file".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, deletes.getAndIncrement() == 0 ? 204 : 404, new byte[0]);
            }
        });
        server.start();
        try {
            ScoreAiProperties properties = new ScoreAiProperties();
            var config = properties.getTools().getFiles().getStorage().getGoogleDrive();
            config.setAccessToken("token");
            config.setApiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            GoogleDriveAiFileStorage storage = new GoogleDriveAiFileStorage(
                    properties, new ObjectMapper());

            assertThat(storage.store("ignored", "report.md", "text/markdown",
                    "file".getBytes(StandardCharsets.UTF_8))).isEqualTo("file_1");
            assertThat(new String(storage.load("file_1"), StandardCharsets.UTF_8)).isEqualTo("file");
            storage.delete("file_1");
            storage.delete("file_1");

            assertThat(stored.get()).asString().contains("report.md", "file");
            assertThat(deletes).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void s3StoresLoadsAndDeletesUsingTheConfiguredBucketAndPrefix() throws Exception {
        AtomicReference<byte[]> stored = new AtomicReference<>();
        AtomicInteger deletes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bucket/files/conversation/request/file.md", exchange -> {
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    stored.set(exchange.getRequestBody().readAllBytes());
                    respond(exchange, 200, new byte[0]);
                }
                case "GET" -> respond(exchange, 200, "file".getBytes(StandardCharsets.UTF_8));
                case "DELETE" -> {
                    deletes.incrementAndGet();
                    respond(exchange, 204, new byte[0]);
                }
                default -> respond(exchange, 405, new byte[0]);
            }
        });
        server.start();
        try {
            ScoreAiProperties properties = new ScoreAiProperties();
            var config = properties.getTools().getFiles().getStorage().getS3();
            config.setBucket("bucket");
            config.setPrefix("files");
            config.setRegion("us-east-1");
            config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            config.setAccessKey("access");
            config.setSecretKey("secret");
            config.setPathStyleAccess(true);
            S3AiFileStorage storage = new S3AiFileStorage(properties);
            byte[] content = "file".getBytes(StandardCharsets.UTF_8);

            String location = storage.store("conversation/request/file.md", "file.md",
                    "text/markdown", content);
            assertThat(location).isEqualTo("files/conversation/request/file.md");
            assertThat(storage.load(location)).isEqualTo(content);
            storage.delete(location);

            assertThat(new String(stored.get(), StandardCharsets.UTF_8)).contains("file");
            assertThat(deletes).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] content) throws java.io.IOException {
        if (status == 204 || status == 404 && content.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, content.length);
            exchange.getResponseBody().write(content);
        }
        exchange.close();
    }
}
