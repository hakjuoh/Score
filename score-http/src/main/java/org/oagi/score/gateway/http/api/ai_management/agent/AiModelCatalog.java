package org.oagi.score.gateway.http.api.ai_management.agent;

import java.util.List;

/** Provider-neutral catalog used by the shared Agent runner and query services. */
public interface AiModelCatalog {

    AiModel require(String modelId);

    AiModel defaultModel();

    List<AiModel> available();
}
