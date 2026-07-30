package org.oagi.score.gateway.http.api.ai_management.file.storage;

import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

@Component
public final class S3AiFileStorage implements AiFileStorage {

    private final ScoreAiProperties properties;

    public S3AiFileStorage(ScoreAiProperties properties) { this.properties = properties; }

    @Override public String id() { return "s3"; }

    @Override
    public String store(String objectKey, String filename, String mediaType, byte[] content) {
        var config = configuration();
        String key = prefixed(config.getPrefix(), objectKey);
        try (S3Client client = client(config)) {
            client.putObject(builder -> builder.bucket(required(config.getBucket(), "S3 bucket"))
                            .key(key).contentType(mediaType), RequestBody.fromBytes(content));
        }
        return key;
    }

    @Override
    public byte[] load(String location) {
        var config = configuration();
        try (S3Client client = client(config)) {
            return client.getObjectAsBytes(builder -> builder
                    .bucket(required(config.getBucket(), "S3 bucket")).key(location)).asByteArray();
        }
    }

    @Override
    public void delete(String location) {
        var config = configuration();
        try (S3Client client = client(config)) {
            client.deleteObject(builder -> builder
                    .bucket(required(config.getBucket(), "S3 bucket")).key(location));
        }
    }

    private ScoreAiProperties.S3FileStorage configuration() {
        return properties.getTools().getFiles().getStorage().getS3();
    }

    private S3Client client(ScoreAiProperties.S3FileStorage config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(required(config.getRegion(), "S3 region")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.isPathStyleAccess()).build());
        if (StringUtils.hasText(config.getEndpoint())) {
            builder.endpointOverride(URI.create(config.getEndpoint().strip()));
        }
        if (StringUtils.hasText(config.getAccessKey()) || StringUtils.hasText(config.getSecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    required(config.getAccessKey(), "S3 access-key"),
                    required(config.getSecretKey(), "S3 secret-key"))));
        }
        return builder.build();
    }

    private String prefixed(String prefix, String key) {
        return !StringUtils.hasText(prefix) ? key : prefix.strip().replaceAll("^/+|/+$", "") + "/" + key;
    }

    private String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(label + " is required.");
        return value.strip();
    }
}
