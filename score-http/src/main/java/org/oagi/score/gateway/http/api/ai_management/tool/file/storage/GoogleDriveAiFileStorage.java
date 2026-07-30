package org.oagi.score.gateway.http.api.ai_management.tool.file.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@Component
public final class GoogleDriveAiFileStorage implements AiFileStorage {

    private final ScoreAiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    public GoogleDriveAiFileStorage(ScoreAiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String id() { return "google-drive"; }

    @Override
    public String store(String objectKey, String filename, String mediaType, byte[] content) {
        var config = configuration();
        String boundary = "score-file-" + UUID.randomUUID();
        String metadata = StringUtils.hasText(config.getFolderId())
                ? "{\"name\":" + json(filename) + ",\"parents\":[" + json(config.getFolderId().strip()) + "]}"
                : "{\"name\":" + json(filename) + "}";
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + metadata + "\r\n--" + boundary + "\r\nContent-Type: " + mediaType
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        HttpRequest request = authorized(URI.create(base(config)
                        + "/upload/drive/v3/files?uploadType=multipart&fields=id"))
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<byte[]> response = send(request);
        try {
            String id = objectMapper.readTree(response.body()).path("id").asText();
            if (!StringUtils.hasText(id)) throw new IllegalStateException("Google Drive did not return a file id.");
            return id;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not parse the Google Drive upload response.", failure);
        }
    }

    @Override
    public byte[] load(String location) {
        HttpRequest request = authorized(URI.create(base(configuration()) + "/drive/v3/files/"
                + encodePath(location) + "?alt=media")).GET().build();
        return send(request).body();
    }

    @Override
    public void delete(String location) {
        HttpRequest request = authorized(URI.create(base(configuration()) + "/drive/v3/files/"
                + encodePath(location))).DELETE().build();
        HttpResponse<byte[]> response = sendAllowingNotFound(request);
        if (response.statusCode() == 404) return;
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + required(configuration().getAccessToken(),
                        "Google Drive access-token"));
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        HttpResponse<byte[]> response = sendAllowingNotFound(request);
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Google Drive object does not exist.");
        }
        return response;
    }

    private HttpResponse<byte[]> sendAllowingNotFound(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if ((response.statusCode() < 200 || response.statusCode() >= 300)
                    && response.statusCode() != 404) {
                throw new IllegalStateException("Google Drive request failed with HTTP " + response.statusCode() + ".");
            }
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google Drive request was interrupted.", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Google Drive request failed.", failure);
        }
    }

    private String json(String value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Could not encode Google Drive metadata.", failure); }
    }

    private String base(ScoreAiProperties.GoogleDriveFileStorage config) {
        return required(config.getApiBaseUrl(), "Google Drive api-base-url").replaceAll("/+$", "");
    }

    private String encodePath(String value) {
        if (!StringUtils.hasText(value) || !value.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid Google Drive file id.");
        }
        return value;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(label + " is required.");
        return value.strip();
    }

    private ScoreAiProperties.GoogleDriveFileStorage configuration() {
        return properties.getTools().getFiles().getStorage().getGoogleDrive();
    }
}
