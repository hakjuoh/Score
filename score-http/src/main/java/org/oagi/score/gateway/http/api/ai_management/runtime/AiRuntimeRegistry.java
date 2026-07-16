package org.oagi.score.gateway.http.api.ai_management.runtime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves configured runtime names to their in-process Java implementations. */
@Component
public final class AiRuntimeRegistry {

    private final Map<String, AiRuntime> runtimes;

    public AiRuntimeRegistry(List<AiRuntime> runtimes) {
        Map<String, AiRuntime> indexed = new LinkedHashMap<>();
        for (AiRuntime runtime : runtimes) {
            AiRuntime duplicate = indexed.put(runtime.name(), runtime);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate AI runtime: " + runtime.name());
            }
        }
        this.runtimes = Map.copyOf(indexed);
    }

    public AiRuntime.Result execute(String name, AiRuntime.Context context) {
        return runtime(name).execute(context);
    }

    public List<AiRuntime.Setting> settings(String name, String modelName) {
        return runtime(name).settings(modelName);
    }

    public Map<String, Object> normalizeOptions(String name, String modelName,
                                                Map<String, Object> requested) {
        return Map.copyOf(runtime(name).normalizeOptions(modelName, requested));
    }

    private AiRuntime runtime(String name) {
        String normalized = StringUtils.hasText(name) ? name.strip().toLowerCase() : "";
        AiRuntime runtime = runtimes.get(normalized);
        if (runtime == null) {
            throw new IllegalArgumentException("Unknown AI runtime: " + name);
        }
        return runtime;
    }
}
