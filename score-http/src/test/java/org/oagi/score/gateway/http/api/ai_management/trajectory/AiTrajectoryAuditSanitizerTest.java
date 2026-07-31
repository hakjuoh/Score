package org.oagi.score.gateway.http.api.ai_management.trajectory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class AiTrajectoryAuditSanitizerTest {

    private final AiTrajectoryAuditSanitizer sanitizer =
            new AiTrajectoryAuditSanitizer(new ObjectMapper());

    @Test
    void redactsNestedJsonAndPlainTextSecrets() {
        String json = sanitizer.auditText(
                "{\"password\":\"secret-1\",\"nested\":{\"apiKey\":\"secret-2\"}}");

        assertThat(json).contains("[REDACTED]")
                .doesNotContain("secret-1", "secret-2");
        assertThat(sanitizer.auditText("password=secret-3"))
                .doesNotContain("secret-3");
    }

    @Test
    void appliesTheExactAuditBoundaryAndReportsTheOmittedCount() {
        String boundary = "a".repeat(32_768);

        assertThat(sanitizer.auditText(boundary)).isEqualTo(boundary);
        assertThat(sanitizer.auditText(boundary + "b"))
                .isEqualTo(boundary + "\n[TRUNCATED 1 CHARACTERS]");
    }

    @Test
    void boundsOversizedStructuredValuesAfterRedaction() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("password", "never-export-this-secret");
        source.put("payload", "x".repeat(40_000));
        Object bounded = sanitizer.boundedValue(source);

        assertThat(bounded).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) bounded;
        String summary = result.get("summary").toString();
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("password", "[REDACTED]");
        redacted.put("payload", sanitizer.auditText("x".repeat(40_000)));
        String serialized = new ObjectMapper().writeValueAsString(redacted);
        String marker = "\n[TRUNCATED " + (serialized.length() - 32_768) + " CHARACTERS]";
        assertThat(result).containsEntry("truncated", true);
        assertThat(summary)
                .isEqualTo(serialized.substring(0, 32_768) + marker)
                .doesNotContain("never-export-this-secret");
    }
}
