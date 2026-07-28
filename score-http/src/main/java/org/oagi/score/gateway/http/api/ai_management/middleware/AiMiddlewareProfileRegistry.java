package org.oagi.score.gateway.http.api.ai_management.middleware;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;
import org.oagi.score.gateway.http.configuration.ai.ScoreAiProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates registered IDs and resolves immutable, condition-filtered middleware profiles. */
final class AiMiddlewareProfileRegistry {

    private static final String DEFAULT_PROFILE = "default";

    private final Map<String, Entry> registrations;
    private final Map<String, List<Entry>> profiles;
    private final Map<ExecutionScope.Purpose, String> profileByPurpose;

    AiMiddlewareProfileRegistry(ScoreAiProperties.Middleware settings,
                                List<? extends AiMiddleware> middleware) {
        ScoreAiProperties.Middleware configured = settings != null
                ? settings : new ScoreAiProperties.Middleware();
        registrations = registrations(configured, middleware);
        profiles = profiles(configured, registrations);
        profileByPurpose = Map.copyOf(configured.getProfileByPurpose());
        validatePurposeProfiles();
    }

    boolean isEmpty() {
        return registrations.isEmpty();
    }

    List<Entry> active(ExecutionScope scope, AiTool.ToolEffect effect) {
        String profileName = profileByPurpose.getOrDefault(scope.purpose(), DEFAULT_PROFILE);
        return profiles.getOrDefault(profileName, List.of()).stream()
                .filter(entry -> entry.applies(scope.purpose(), effect))
                .toList();
    }

    private Map<String, Entry> registrations(ScoreAiProperties.Middleware settings,
                                              List<? extends AiMiddleware> middleware) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (AiMiddleware candidate : middleware != null ? middleware : List.<AiMiddleware>of()) {
            if (candidate == null) continue;
            String id = id(candidate.id());
            ScoreAiProperties.MiddlewarePolicy policy = settings.getPolicies()
                    .getOrDefault(id, new ScoreAiProperties.MiddlewarePolicy());
            boolean required = candidate.required();
            if (required && policy.getMode() != ScoreAiProperties.MiddlewareMode.ENFORCE) {
                throw new IllegalStateException("Required AI middleware '" + id
                        + "' must use ENFORCE mode.");
            }
            if (required && (!policy.getPurposes().isEmpty()
                    || !policy.getToolEffects().isEmpty())) {
                throw new IllegalStateException("Required AI middleware '" + id
                        + "' cannot have applicability conditions.");
            }
            Entry previous = result.putIfAbsent(id, new Entry(id, candidate, required,
                    policy.getMode(), policy.getPurposes(), policy.getToolEffects()));
            if (previous != null) {
                throw new IllegalStateException("Duplicate AI middleware id: " + id);
            }
            candidate.configure(new AiMiddleware.Settings(policy.getSettings()));
        }
        Set<String> unknownPolicies = new LinkedHashSet<>(settings.getPolicies().keySet());
        unknownPolicies.removeAll(result.keySet());
        if (!unknownPolicies.isEmpty()) {
            throw new IllegalStateException("Policies reference unregistered AI middleware: "
                    + unknownPolicies);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private Map<String, List<Entry>> profiles(ScoreAiProperties.Middleware settings,
                                               Map<String, Entry> available) {
        Map<String, List<String>> configured = settings.getProfiles().isEmpty()
                ? Map.of(DEFAULT_PROFILE, List.of()) : settings.getProfiles();
        Map<String, List<Entry>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> profileEntry : configured.entrySet()) {
            String profile = id(profileEntry.getKey());
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            available.values().stream().filter(Entry::required)
                    .map(Entry::id).forEach(selected::add);
            for (String configuredId : profileEntry.getValue() != null
                    ? profileEntry.getValue() : List.<String>of()) {
                String middlewareId = id(configuredId);
                if (!available.containsKey(middlewareId)) {
                    throw new IllegalStateException("Profile '" + profile
                            + "' references unregistered AI middleware: " + middlewareId);
                }
                selected.add(middlewareId);
            }
            result.put(profile, selected.stream().map(available::get)
                    .filter(entry -> !entry.disabled()).toList());
        }
        result.computeIfAbsent(DEFAULT_PROFILE, ignored -> available.values().stream()
                .filter(Entry::required).toList());
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private void validatePurposeProfiles() {
        Set<String> unknown = new LinkedHashSet<>(profileByPurpose.values());
        unknown.removeAll(profiles.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Purpose mapping references unknown middleware profiles: "
                    + unknown);
        }
    }

    private static String id(String value) {
        String normalized = Objects.requireNonNull(value, "middleware id").strip().toLowerCase();
        if (!normalized.matches("[a-z0-9][a-z0-9-]{1,79}")) {
            throw new IllegalArgumentException("Invalid AI middleware id: " + value);
        }
        return normalized;
    }

    record Entry(String id, AiMiddleware middleware, boolean required,
                 ScoreAiProperties.MiddlewareMode mode,
                 List<ExecutionScope.Purpose> purposes,
                 List<AiTool.ToolEffect> toolEffects) {
        Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(middleware, "middleware");
            mode = Objects.requireNonNull(mode, "mode");
            purposes = purposes != null ? List.copyOf(purposes) : List.of();
            toolEffects = toolEffects != null ? List.copyOf(toolEffects) : List.of();
        }

        boolean shadow() { return mode == ScoreAiProperties.MiddlewareMode.SHADOW; }
        boolean disabled() { return mode == ScoreAiProperties.MiddlewareMode.DISABLED; }
        boolean applies(ExecutionScope.Purpose purpose, AiTool.ToolEffect effect) {
            if (!purposes.isEmpty() && !purposes.contains(purpose)) return false;
            return toolEffects.isEmpty() || effect != null && toolEffects.contains(effect);
        }
    }
}
