package org.oagi.score.gateway.http.api.ai_management.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Creates stable JSON protocol results for approval and execution failures. */
final class AiChangeToolResults {

    private final ObjectMapper objectMapper;

    AiChangeToolResults(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String confirmationRequired(String confirmationRequestId) {
        var result = objectMapper.createObjectNode();
        result.put("error", AiChangeToolGuard.CHANGE_CONFIRMATION_REQUIRED);
        result.put("message", "This data-changing tool call was not executed."
                + " Wait for explicit user approval.");
        result.put("confirmationRequestId", confirmationRequestId);
        return result.toString();
    }

    String changeFailed(RuntimeException failure) {
        return objectMapper.createObjectNode()
                .put("error", AiChangeToolGuard.CHANGE_FAILED)
                .put("message", AiToolFailureMessage.userMessage(failure))
                .toString();
    }
}
