package org.oagi.score.gateway.http.api.ai_management.catalog.repository.jooq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiModelCatalogUpdate;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.AiProviderId;
import org.oagi.score.gateway.http.api.ai_management.catalog.model.profile.ClaudeHaiku45Profile;
import org.oagi.score.gateway.http.common.repository.jooq.RepositoryFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JooqAiModelCatalogRepositoryTest {

    @Test
    void mapsResolvedProfileSettingsIntoThePersistenceRecord() throws Exception {
        var dsl = DSL.using(SQLDialect.MARIADB);
        var repository = new JooqAiModelCatalogRepository(
                dsl, mock(RepositoryFactory.class), new ObjectMapper());
        var profile = new ClaudeHaiku45Profile();
        var update = new AiModelCatalogUpdate(null, AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, 32_000, 100_000L, 32_000L, 60_000L,
                4_096L, 16_000L, false, null, 2_048, false,
                null, "conversation-history", null, false, null, false,
                List.of("disabled"), "disabled",
                Map.of("topP", 0.5, "messageTypeTtl", Map.of("SYSTEM", "FIVE_MINUTES")),
                List.of());

        var record = repository.configurationRecord(dsl, profile, update);
        var options = new ObjectMapper().readTree(record.getModelOptionsJson());

        assertThat(record.getProviderModelName()).isEqualTo("claude-haiku-4-5");
        assertThat(record.getDisplayName()).isEqualTo("Claude Haiku 4.5");
        assertThat(record.getContextWindow().longValue()).isEqualTo(100_000L);
        assertThat(record.getMaxTokens().intValue()).isEqualTo(32_000);
        assertThat(record.getOutputReserveTokens().longValue()).isEqualTo(32_000L);
        assertThat(record.getAutoCompactThresholdTokens().longValue()).isEqualTo(60_000L);
        assertThat(options.get("thinkingBudgetTokens").asInt()).isEqualTo(2_048);
        assertThat(options.get("adaptiveThinking").asBoolean()).isFalse();
        assertThat(options.has("outputEffort")).isFalse();
        assertThat(options.get("thinkingModes").toString())
                .isEqualTo("[\"enabled\",\"disabled\"]");
        assertThat(options.get("defaultThinking").asText()).isEqualTo("disabled");
        assertThat(options.get("topP").asDouble()).isEqualTo(0.5);
        assertThat(options.at("/messageTypeTtl/SYSTEM").asText()).isEqualTo("FIVE_MINUTES");
    }

    @Test
    void mapsProfileDefaultsWhenOptionalSettingsAreNull() throws Exception {
        var dsl = DSL.using(SQLDialect.MARIADB);
        var repository = new JooqAiModelCatalogRepository(
                dsl, mock(RepositoryFactory.class), new ObjectMapper());
        var profile = new ClaudeHaiku45Profile();
        var update = new AiModelCatalogUpdate(null, AiProviderId.from(1L), profile.getModelKey(),
                true, false, 0, null, 200_000L, null, null,
                8_192L, 32_000L, false, null, null, false,
                null, "conversation-history", null, false, null, false,
                List.of("enabled", "disabled"), "enabled", List.of());

        var record = repository.configurationRecord(dsl, profile, update);
        var options = new ObjectMapper().readTree(record.getModelOptionsJson());

        assertThat(record.getMaxTokens().intValue()).isEqualTo(64_000);
        assertThat(record.getOutputReserveTokens().longValue()).isEqualTo(64_000L);
        assertThat(record.getAutoCompactThresholdTokens().longValue()).isEqualTo(120_000L);
        assertThat(options.get("thinkingBudgetTokens").asInt()).isEqualTo(4_096);
    }
}
