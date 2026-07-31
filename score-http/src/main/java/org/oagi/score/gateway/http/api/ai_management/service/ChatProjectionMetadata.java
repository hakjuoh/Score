package org.oagi.score.gateway.http.api.ai_management.service;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the stable metadata projected with visible conversation messages. */
final class ChatProjectionMetadata {

    private ChatProjectionMetadata() {
    }

    static Map<String, Object> user(Map<String, Object> traceContext,
                                    String permissionMode, ChatRequest request) {
        return build(Map.of(), traceContext, permissionMode, request);
    }

    static Map<String, Object> assistant(Map<String, Object> committedTrace,
                                         Map<String, Object> traceContext,
                                         String permissionMode, ChatRequest request) {
        return build(committedTrace, traceContext, permissionMode, request);
    }

    private static Map<String, Object> build(Map<String, Object> committedTrace,
                                             Map<String, Object> traceContext,
                                             String permissionMode, ChatRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (committedTrace != null) metadata.putAll(committedTrace);
        if (traceContext != null) metadata.putAll(traceContext);
        metadata.put("ui_projection", true);
        metadata.put("permission_mode", permissionMode);
        metadata.put("multi_agent", request.multiAgent().asMap());
        if (request.activeWorkflow() != null) {
            metadata.put("active_workflow", request.activeWorkflow());
        }
        return Map.copyOf(metadata);
    }
}
