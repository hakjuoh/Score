package org.oagi.score.gateway.http.api.ai_management.tool.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.util.StringUtils;

import java.util.Map;

/** Request-scoped local platform Tool for creating a downloadable file. */
final class CreateFileTool implements AiTool {

    static final String NAME = "create_file";
    private static final String INPUT_SCHEMA =
            JsonSchemaGenerator.generateForType(CreateFileInput.class);
    private static final String OUTPUT_SCHEMA =
            JsonSchemaGenerator.generateForType(CreateFileOutput.class);
    private final ScoreUser requester;
    private final AiFileService files;
    private final ObjectMapper objectMapper;

    CreateFileTool(ScoreUser requester, AiFileService files, ObjectMapper objectMapper) {
        this.requester = requester;
        this.files = files;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolSpecification specification() {
        return new ToolSpecification(new ToolId(NAME), NAME,
                "Create and retain a downloadable Assistant file after its content is complete. "
                        + "Use a registered format; currently markdown and pdf are available.",
                INPUT_SCHEMA, OUTPUT_SCHEMA, ToolEffect.OUTPUT_WRITE);
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
        try {
            JsonNode input = objectMapper.readTree(arguments.json());
            String format = text(input, "format", true);
            String filename = text(input, "filename", false);
            JsonNode content = input.get("content");
            if (content == null || content.isNull()) throw new IllegalArgumentException("File content is required.");
            Map<String, Object> options = input.has("options") && input.get("options").isObject()
                    ? objectMapper.convertValue(input.get("options"), new TypeReference<>() {}) : Map.of();
            AiFileDescriptor created = files.create(requester, context.scope(), format,
                    filename, content, options);
            return new ToolResult(objectMapper.writeValueAsString(Map.of(
                    "fileId", created.fileId(),
                    "format", created.format(),
                    "filename", created.filename(),
                    "mediaType", created.mediaType(),
                    "size", created.size(),
                    "sha256", created.sha256())));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid create_file input.", failure);
        }
    }

    private String text(JsonNode input, String field, boolean required) {
        String value = input != null && input.path(field).isTextual()
                ? input.path(field).asText().strip() : null;
        if (required && !StringUtils.hasText(value)) {
            throw new IllegalArgumentException("File " + field + " is required.");
        }
        return value;
    }

    private record CreateFileInput(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Registered output format such as markdown or pdf.")
            String format,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Suggested download filename.")
            String filename,
            @JsonProperty(required = true)
            @JsonPropertyDescription(
                    "Renderer-specific content. Markdown and PDF accept a Markdown string.")
            Object content,
            @JsonProperty(required = false)
            @JsonPropertyDescription("Optional renderer-specific settings.")
            Map<String, Object> options) {
    }

    private record CreateFileOutput(
            @JsonProperty(required = true)
            @JsonPropertyDescription("Stable identifier of the retained file.")
            String fileId,
            @JsonProperty(required = true)
            @JsonPropertyDescription("Registered renderer format.")
            String format,
            @JsonProperty(required = true)
            @JsonPropertyDescription("Download filename.")
            String filename,
            @JsonProperty(required = true)
            @JsonPropertyDescription("File media type.")
            String mediaType,
            @JsonProperty(required = true)
            @JsonPropertyDescription("File size in bytes.")
            @Schema(minimum = "0")
            long size,
            @JsonProperty(required = true)
            @JsonPropertyDescription("SHA-256 content digest.")
            @Schema(pattern = "^[a-fA-F0-9]{64}$")
            String sha256) {
    }
}
