package org.oagi.score.gateway.http.api.ai_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeConfirmationCommandRepository;
import org.oagi.score.gateway.http.api.ai_management.repository.AiChangeConfirmationQueryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiChangeConfirmationServiceTest {

    private final AiChangeConfirmationService service =
            new AiChangeConfirmationService(
                    mock(AiChangeConfirmationQueryRepository.class),
                    mock(AiChangeConfirmationCommandRepository.class),
                    new ObjectMapper());

    @Test
    void bindsApprovalToCanonicalToolArgumentsInsteadOfJsonFormatting() {
        String first = service.argumentsDigest("update_business_context", "{\"id\":1,\"value\":{\"b\":2,\"a\":1}}");
        String reordered = service.argumentsDigest("update_business_context", "{\"value\":{\"a\":1,\"b\":2},\"id\":1}");
        String tampered = service.argumentsDigest("update_business_context", "{\"id\":2,\"value\":{\"a\":1,\"b\":2}}");

        assertThat(first).isEqualTo(reordered).isNotEqualTo(tampered);
        assertThat(first).hasSize(64);
    }

    @Test
    void bindsARevisionApprovalToTheSameToolAndExactUserPrompt() {
        String first = service.revisionDigest(
                "create_business_context", "Use the name Revised");
        String whitespaceEquivalent = service.revisionDigest(
                "create_business_context", "  Use the name Revised  ");
        String changedPrompt = service.revisionDigest(
                "create_business_context", "Use the name Other");
        String changedTool = service.revisionDigest(
                "delete_business_context", "Use the name Revised");

        assertThat(first).isEqualTo(whitespaceEquivalent)
                .isNotEqualTo(changedPrompt)
                .isNotEqualTo(changedTool)
                .hasSize(64);
    }

    @Test
    void presentsBoundedCanonicalArgumentsWithoutSecretsForInformedApproval() {
        String summary = service.argumentSummary("{\"target\":2,\"apiKey\":\"secret-value\","
                + "\"nested\":{\"password\":\"hidden\",\"name\":\"safe\"},"
                + "\"cookie\":\"browser-cookie\",\"sessionId\":\"session-value\","
                + "\"credential\":\"credential-value\","
                + "\"authorId\":42,\"isAuthenticated\":true,\"sessionCount\":3,"
                + "\"notes\":[\"token=plain-token\",\"auth: bearer-value\"]}");

        assertThat(summary).contains("\"apiKey\":\"[REDACTED]\"", "\"cookie\":\"[REDACTED]\"",
                        "\"sessionId\":\"[REDACTED]\"", "\"credential\":\"[REDACTED]\"",
                        "\"authorId\":42", "\"isAuthenticated\":true", "\"sessionCount\":3",
                        "token=[REDACTED]", "auth: [REDACTED]")
                .doesNotContain("secret-value", "hidden", "browser-cookie", "session-value",
                        "credential-value", "plain-token", "bearer-value");
        assertThat(service.argumentSummary("not-json")).isEqualTo("[Invalid non-JSON tool arguments]");
    }
}
