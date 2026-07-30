package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.util.StringUtils;

import java.util.Map;

/** Request-scoped local platform Tool for creating a downloadable artifact. */
final class CreateArtifactTool implements AiTool {

    static final String NAME = "create_artifact";
    private static final String INPUT_SCHEMA = """
            {
              "type":"object",
              "properties":{
                "format":{"type":"string","description":"Registered output format such as markdown or pdf."},
                "filename":{"type":"string","description":"Suggested download filename."},
                "content":{"description":"Renderer-specific content. Markdown and PDF accept a Markdown string."},
                "options":{"type":"object","description":"Optional renderer-specific settings."}
              },
              "required":["format","content"],
              "additionalProperties":false
            }
            """;
    private final ScoreUser requester;
    private final AiArtifactService artifacts;
    private final ObjectMapper objectMapper;

    CreateArtifactTool(ScoreUser requester, AiArtifactService artifacts, ObjectMapper objectMapper) {
        this.requester = requester;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolSpecification specification() {
        return new ToolSpecification(new ToolId(NAME), NAME,
                "Create and retain a downloadable Assistant artifact after its content is complete. "
                        + "Use a registered format; currently markdown and pdf are available.",
                INPUT_SCHEMA, "{\"type\":\"object\"}", ToolEffect.OUTPUT_WRITE);
    }

    @Override
    public ToolResult execute(ToolArguments arguments, ToolExecutionContext context) {
        try {
            JsonNode input = objectMapper.readTree(arguments.json());
            String format = text(input, "format", true);
            String filename = text(input, "filename", false);
            JsonNode content = input.get("content");
            if (content == null || content.isNull()) throw new IllegalArgumentException("Artifact content is required.");
            Map<String, Object> options = input.has("options") && input.get("options").isObject()
                    ? objectMapper.convertValue(input.get("options"), new TypeReference<>() {}) : Map.of();
            AiArtifactDescriptor created = artifacts.create(requester, context.scope(), format,
                    filename, content, options);
            return new ToolResult(objectMapper.writeValueAsString(Map.of(
                    "artifactId", created.artifactId(),
                    "format", created.format(),
                    "filename", created.filename(),
                    "mediaType", created.mediaType(),
                    "size", created.size(),
                    "sha256", created.sha256())));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid create_artifact input.", failure);
        }
    }

    private String text(JsonNode input, String field, boolean required) {
        String value = input != null && input.path(field).isTextual()
                ? input.path(field).asText().strip() : null;
        if (required && !StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Artifact " + field + " is required.");
        }
        return value;
    }
}
