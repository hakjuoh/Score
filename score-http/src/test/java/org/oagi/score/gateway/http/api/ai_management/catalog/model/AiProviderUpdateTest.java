package org.oagi.score.gateway.http.api.ai_management.catalog.model;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderUpdateTest {

    @Test
    void apiKeyIsWriteOnlyAndRedactedFromStringRepresentations() throws Exception {
        AiProviderUpdate update = new AiProviderUpdate(null, "provider", "anthropic",
                "https://example.test", null, null, null, false,
                "highly-sensitive-key");

        String json = JsonMapper.builder().build().writeValueAsString(update);

        assertThat(json).doesNotContain("highly-sensitive-key").doesNotContain("apiKey");
        assertThat(update.toString()).doesNotContain("highly-sensitive-key")
                .contains("apiKey=<redacted>");
    }
}
