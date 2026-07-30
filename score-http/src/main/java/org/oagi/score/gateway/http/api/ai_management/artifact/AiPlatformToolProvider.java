package org.oagi.score.gateway.http.api.ai_management.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.ToolSet;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Supplies request-scoped local platform tools independently of the MCP transport. */
@Component
public final class AiPlatformToolProvider {

    private final ScoreAiProperties properties;
    private final AiArtifactService artifacts;
    private final ObjectMapper objectMapper;

    public AiPlatformToolProvider(ScoreAiProperties properties, AiArtifactService artifacts,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.artifacts = artifacts;
        this.objectMapper = objectMapper;
    }

    public ToolSet tools(ScoreUser requester, ExecutionScope scope) {
        if (!properties.getTools().getArtifacts().isEnabled()) return ToolSet.empty();
        return new ToolSet(List.of(new CreateArtifactTool(requester, artifacts, objectMapper)));
    }
}
