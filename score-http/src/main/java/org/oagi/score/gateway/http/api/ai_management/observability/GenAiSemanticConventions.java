package org.oagi.score.gateway.http.api.ai_management.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanBuilder;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * GenAI semantic-convention vocabulary used by SCORE.
 *
 * <p>The GenAI conventions are still marked Development, so the application keeps the
 * vocabulary in one place and pins its implementation to semantic-conventions-genai commit
 * {@value #SPEC_COMMIT}. This avoids depending on unstable generated Java constants while keeping
 * emitted telemetry wire-compatible with the specification.</p>
 */
final class GenAiSemanticConventions {

    static final String SPEC_COMMIT = "2e994c6d59a93bb4fc1752c5378eedb9b8e14d6b";

    static final String CHAT = "chat";
    static final String INVOKE_AGENT = "invoke_agent";
    static final String INVOKE_WORKFLOW = "invoke_workflow";
    static final String PLAN = "plan";
    static final String EXECUTE_TOOL = "execute_tool";

    static final List<Double> DURATION_BUCKETS_SECONDS = List.of(
            0.01, 0.02, 0.04, 0.08, 0.16, 0.32, 0.64, 1.28,
            2.56, 5.12, 10.24, 20.48, 40.96, 81.92);
    static final List<Long> TOKEN_BUCKETS = List.of(
            1L, 4L, 16L, 64L, 256L, 1_024L, 4_096L, 16_384L,
            65_536L, 262_144L, 1_048_576L, 4_194_304L, 16_777_216L, 67_108_864L);
    static final List<Long> CALL_BUCKETS = List.of(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L);

    private GenAiSemanticConventions() {
    }

    static String spanName(String operation, String target) {
        return StringUtils.hasText(target) && !"unknown".equalsIgnoreCase(target.strip())
                ? operation + " " + target.strip() : operation;
    }

    static double elapsedSeconds(long startedNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos)).toNanos()
                / 1_000_000_000.0;
    }

    static Attributes inferenceAttributes(String operation, String provider, String requestModel,
                                          String responseModel, String errorType) {
        AttributesBuilder attributes = Attributes.builder().put("gen_ai.operation.name", operation);
        putIfKnown(attributes, "gen_ai.provider.name", providerName(provider));
        putIfKnown(attributes, "gen_ai.request.model", requestModel);
        putIfKnown(attributes, "gen_ai.response.model", responseModel);
        putIfKnown(attributes, "error.type", errorType);
        return attributes.build();
    }

    static Attributes agentDurationAttributes(String agentName, String model, String errorType) {
        AttributesBuilder attributes = Attributes.builder();
        putIfKnown(attributes, "gen_ai.agent.name", agentName);
        putIfKnown(attributes, "gen_ai.request.model", model);
        putIfKnown(attributes, "error.type", errorType);
        return attributes.build();
    }

    static Attributes agentCallAttributes(String agentName) {
        AttributesBuilder attributes = Attributes.builder();
        putIfKnown(attributes, "gen_ai.agent.name", agentName);
        return attributes.build();
    }

    static Attributes toolDurationAttributes(String toolName, String agentName, String errorType) {
        AttributesBuilder attributes = Attributes.builder();
        putIfKnown(attributes, "gen_ai.tool.name", toolName);
        attributes.put("gen_ai.tool.type", "function");
        putIfKnown(attributes, "gen_ai.agent.name", agentName);
        putIfKnown(attributes, "error.type", errorType);
        return attributes.build();
    }

    static Attributes workflowDurationAttributes(String workflowName, String errorType) {
        AttributesBuilder attributes = Attributes.builder();
        putIfKnown(attributes, "gen_ai.workflow.name", workflowName);
        putIfKnown(attributes, "error.type", errorType);
        return attributes.build();
    }

    static void putIfKnown(AttributesBuilder attributes, String key, String value) {
        if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value.strip())) {
            attributes.put(key, value.strip());
        }
    }

    static void putIfKnown(SpanBuilder span, String key, String value) {
        if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value.strip())) {
            span.setAttribute(key, value.strip());
        }
    }

    static String providerName(String provider) {
        if (!StringUtils.hasText(provider)) return provider;
        String normalized = provider.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "azure-openai", "azure_openai" -> "azure.ai.openai";
            default -> normalized;
        };
    }
}
