package org.oagi.score.gateway.http.api.ai_management.agent;

import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailRefusal;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Strict result of the trusted per-turn no-Tool Gateway Agent. */
public sealed interface GatewayResult permits GatewayResult.Direct, GatewayResult.Handoff,
        GatewayResult.Review, GatewayResult.Refuse {

    Optional<Execution> execution();

    record Execution(Agent.AgentId agentId, AiModel.ModelId modelId) {
        public Execution {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(modelId, "modelId");
        }
    }

    record GuardedTurn(AiMessage.User turn, List<GuardrailDecision> decisions) {
        public GuardedTurn {
            Objects.requireNonNull(turn, "turn");
            decisions = decisions != null ? List.copyOf(decisions) : List.of();
        }
    }

    enum DirectIntent { GREETING, THANKS, CAPABILITIES_HELP }

    record Direct(GuardedTurn turn, DirectIntent intent, AiMessage.Assistant candidate,
                  double confidence, Optional<Execution> execution) implements GatewayResult {
        public Direct {
            Objects.requireNonNull(turn); Objects.requireNonNull(intent); Objects.requireNonNull(candidate);
            execution = execution != null ? execution : Optional.empty();
            validateConfidence(confidence);
        }

        public Direct(GuardedTurn turn, DirectIntent intent, AiMessage.Assistant candidate,
                      double confidence) {
            this(turn, intent, candidate, confidence, Optional.empty());
        }
    }

    record Handoff(GuardedTurn turn, Optional<String> suggestedWorkflow,
                   double confidence, boolean routingFallback,
                   Optional<Execution> execution) implements GatewayResult {
        public Handoff {
            Objects.requireNonNull(turn);
            suggestedWorkflow = suggestedWorkflow != null ? suggestedWorkflow : Optional.empty();
            execution = execution != null ? execution : Optional.empty();
            validateConfidence(confidence);
        }

        public Handoff(GuardedTurn turn, Optional<String> suggestedWorkflow,
                       double confidence, boolean routingFallback) {
            this(turn, suggestedWorkflow, confidence, routingFallback, Optional.empty());
        }
    }

    record Review(GuardedTurn turn, String reason,
                  Optional<Execution> execution) implements GatewayResult {
        public Review {
            Objects.requireNonNull(turn);
            reason = Objects.requireNonNullElse(reason, "review");
            execution = execution != null ? execution : Optional.empty();
        }

        public Review(GuardedTurn turn, String reason) {
            this(turn, reason, Optional.empty());
        }
    }

    record Refuse(GuardrailRefusal refusal,
                  Optional<Execution> execution) implements GatewayResult {
        public Refuse {
            Objects.requireNonNull(refusal);
            execution = execution != null ? execution : Optional.empty();
        }

        public Refuse(GuardrailRefusal refusal) {
            this(refusal, Optional.empty());
        }
    }

    private static void validateConfidence(double value) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException("Gateway confidence is invalid.");
        }
    }
}
