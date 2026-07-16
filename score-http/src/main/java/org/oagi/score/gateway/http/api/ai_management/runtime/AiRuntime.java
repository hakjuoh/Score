package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.oagi.score.gateway.http.api.ai_management.controller.payload.ChatRequest;
import org.oagi.score.gateway.http.api.ai_management.service.AiTrajectoryRecorder;
import org.oagi.score.gateway.http.common.model.ScoreUser;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

/** Executes one assistant request using a configured in-process Java runtime. */
public interface AiRuntime {

    String name();

    default List<Setting> settings(String modelName) {
        return List.of();
    }

    default Map<String, Object> normalizeOptions(String modelName, Map<String, Object> requested) {
        if (requested != null && !requested.isEmpty()) {
            throw new IllegalArgumentException("Runtime '" + name() + "' does not accept runtime options.");
        }
        return Map.of();
    }

    Result execute(Context context);

    record Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                   ScoreUser requester, AiTrajectoryRecorder recorder,
                   boolean toolsEnabled, boolean streamVisibleContent) {

        public Context(ChatRequest request, List<Message> history, UserMessage userMessage,
                       ScoreUser requester, AiTrajectoryRecorder recorder) {
            this(request, history, userMessage, requester, recorder, true, true);
        }

        public Context {
            history = history != null ? List.copyOf(history) : List.of();
        }
    }

    record Result(String answer) {}

    record Setting(String name, String displayName, String description, String type,
                   Object defaultValue, List<Option> options,
                   Number minimum, Number maximum, Number step) {

        public Setting {
            options = options != null ? List.copyOf(options) : List.of();
        }
    }

    record Option(String value, String displayName) {}
}
