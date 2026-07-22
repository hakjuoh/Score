package org.oagi.score.gateway.http.api.ai_management.tool;

import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObservation;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionObserver;
import org.oagi.score.gateway.http.api.ai_management.execution.ExecutionState;
import org.oagi.score.gateway.http.api.ai_management.guardrail.GuardrailDecision;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolGuardrailRegistry;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolInputGuardrail;
import org.oagi.score.gateway.http.api.ai_management.guardrail.ToolOutputGuardrail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Single mandatory Tool boundary used by every provider and Spring AI callback. */
public final class ToolExecutionGateway {

    private static final int MAX_AUTHORIZATION_RESTARTS = 2;
    private static final String POLICY_UNAVAILABLE_RESULT =
            "{\"error\":\"TOOL_POLICY_UNAVAILABLE\",\"message\":"
                    + "\"The Tool result is unavailable because a required policy could not complete.\"}";
    private static final ToolExecutionGateway DISABLED = new ToolExecutionGateway();

    private final ToolSet tools;
    private final ToolGuardrailRegistry guardrails;
    private final List<ToolAuthorizationPolicy> authorization;
    private final RequestFence fence;
    private final ExecutionObserver observer;
    private final ExecutionState state;
    private final long rawOutputByteLimit;
    private final boolean disabled;

    private ToolExecutionGateway() {
        this.tools = ToolSet.empty(); this.guardrails = null; this.authorization = List.of();
        this.fence = RequestFence.ALLOW; this.observer = ExecutionObserver.noop();
        this.state = new ExecutionState(); this.rawOutputByteLimit = 1; this.disabled = true;
    }

    public ToolExecutionGateway(ToolSet tools, ToolGuardrailRegistry guardrails,
                                List<ToolAuthorizationPolicy> authorization,
                                RequestFence fence, ExecutionObserver observer,
                                ExecutionState state, long rawOutputByteLimit) {
        this.tools = tools != null ? tools : ToolSet.empty();
        this.guardrails = Objects.requireNonNull(guardrails, "guardrails");
        this.authorization = authorization != null
                ? authorization.stream().filter(Objects::nonNull).toList() : List.of();
        this.fence = fence != null ? fence : RequestFence.ALLOW;
        this.observer = ExecutionObserver.composite(observer != null
                ? List.of(observer) : List.of());
        this.state = state != null ? state : new ExecutionState();
        if (rawOutputByteLimit <= 0) throw new IllegalArgumentException("Tool output limit must be positive.");
        this.rawOutputByteLimit = rawOutputByteLimit; this.disabled = false;
    }

    public static ToolExecutionGateway disabled() { return DISABLED; }

    public AiTool.ToolResult execute(AiTool.ToolId toolId, AiTool.ToolArguments supplied,
                                     ExecutionScope scope) {
        if (disabled) throw new IllegalStateException("This Agent has no Tool execution gateway.");
        AiTool tool = tools.find(toolId).orElseThrow(() ->
                new IllegalArgumentException("Tool is not authorized for this Agent: " + toolId.value()));
        ToolGuardrailRegistry.Set policies = guardrails.resolve(toolId, scope);
        GuardedInput preAuth = runInput(policies.input(), ToolInputGuardrail.Stage.PRE_AUTHORIZATION,
                tool, supplied, scope);
        if (preAuth.refusal() != null) {
            return runOutput(policies.output(), tool, supplied, preAuth.refusal(), scope);
        }
        AiTool.ToolArguments arguments = preAuth.arguments();

        for (int restart = 0; restart <= MAX_AUTHORIZATION_RESTARTS; restart++) {
            AiTool.ToolResult authorizationRefusal = authorize(tool, arguments, scope);
            if (authorizationRefusal != null) {
                return runOutput(policies.output(), tool, arguments, authorizationRefusal, scope);
            }
            GuardedInput preExecution = runInput(policies.input(), ToolInputGuardrail.Stage.PRE_EXECUTION,
                    tool, arguments, scope);
            if (preExecution.refusal() != null) {
                return runOutput(policies.output(), tool, arguments,
                        preExecution.refusal(), scope);
            }
            if (!preExecution.arguments().json().equals(arguments.json())) {
                arguments = preExecution.arguments();
                if (restart == MAX_AUTHORIZATION_RESTARTS) {
                    throw new IllegalStateException("Tool arguments kept changing after authorization.");
                }
                continue;
            }
            return invoke(tool, arguments, policies.output(), scope);
        }
        throw new IllegalStateException("Tool authorization could not stabilize.");
    }

    private AiTool.ToolResult authorize(AiTool tool, AiTool.ToolArguments arguments,
                                        ExecutionScope scope) {
        for (ToolAuthorizationPolicy policy : authorization) {
            ToolAuthorizationPolicy.Result result;
            try {
                result = Objects.requireNonNull(policy.authorize(
                        new ToolAuthorizationPolicy.Request(tool.specification(), arguments, scope)));
            } catch (RuntimeException unavailable) {
                return unavailableResult(Map.of());
            }
            if (result instanceof ToolAuthorizationPolicy.Result.Refuse refuse) return refuse.replacement();
        }
        return null;
    }

    private AiTool.ToolResult invoke(AiTool tool, AiTool.ToolArguments arguments,
                                     List<ToolOutputGuardrail> outputPolicies,
                                     ExecutionScope scope) {
        fence.verifyActive(scope);
        ToolAuthorizationPolicy.Request authorizationRequest =
                new ToolAuthorizationPolicy.Request(tool.specification(), arguments, scope);
        java.util.ArrayList<ToolAuthorizationPolicy> executionPolicies = new java.util.ArrayList<>();
        for (ToolAuthorizationPolicy policy : authorization) {
            ToolAuthorizationPolicy.Result result;
            try {
                result = Objects.requireNonNull(policy.beforeExecution(authorizationRequest));
            } catch (RuntimeException failure) {
                abort(executionPolicies, authorizationRequest);
                return runOutput(outputPolicies, tool, arguments,
                        unavailableResult(Map.of()), scope);
            }
            if (result instanceof ToolAuthorizationPolicy.Result.Refuse refuse) {
                abort(executionPolicies, authorizationRequest);
                return runOutput(outputPolicies, tool, arguments, refuse.replacement(), scope);
            }
            executionPolicies.add(policy);
        }
        observer.observe(ExecutionObservation.of("tool.invocation.started", scope,
                Map.of("tool_id", tool.specification().id().value(),
                        "effect", tool.specification().effect().name())));
        AiTool.ToolResult raw;
        try {
            raw = Objects.requireNonNull(tool.execute(arguments,
                    new AiTool.ToolExecutionContext(scope, Map.of())), "Tool result");
        } catch (RuntimeException failure) {
            failed(executionPolicies, authorizationRequest, failure);
            observer.observe(ExecutionObservation.of("tool.invocation.failed", scope,
                    Map.of("tool_id", tool.specification().id().value(),
                            "failure_type", failure.getClass().getSimpleName())));
            throw failure;
        }
        state.toolCompleted(tool.specification().effect() != AiTool.ToolEffect.READ_ONLY);
        try {
            AiTool.ToolResult bounded = bound(raw);
            AiTool.ToolResult safe = runOutput(outputPolicies, tool, arguments, bounded, scope);
            // Authorization middleware may retain results for exact replay/read-back.
            // It must receive only the same output-guarded value exposed downstream.
            completed(executionPolicies, authorizationRequest, safe);
            observer.observe(ExecutionObservation.of("tool.invocation.completed", scope,
                    Map.of("tool_id", tool.specification().id().value(),
                            "effect", tool.specification().effect().name())));
            return safe;
        } catch (RuntimeException failure) {
            observer.observe(ExecutionObservation.of("tool.invocation.failed", scope,
                    Map.of("tool_id", tool.specification().id().value(),
                            "failure_type", failure.getClass().getSimpleName())));
            throw failure;
        }
    }

    private GuardedInput runInput(List<ToolInputGuardrail> policies, ToolInputGuardrail.Stage stage,
                                  AiTool tool, AiTool.ToolArguments supplied, ExecutionScope scope) {
        AiTool.ToolArguments current = supplied;
        for (ToolInputGuardrail policy : policies) {
            ToolInputGuardrail.Result result;
            try {
                result = Objects.requireNonNull(policy.evaluate(
                        new ToolInputGuardrail.Request(stage, tool.specification(), current, scope)));
            } catch (RuntimeException unavailable) {
                return new GuardedInput(null, unavailableResult(Map.of()));
            }
            if (result instanceof ToolInputGuardrail.Result.Allow allow) {
                current = allow.arguments(); observeDecision(allow.decision(), scope, tool);
            } else if (result instanceof ToolInputGuardrail.Result.Rewrite rewrite) {
                current = rewrite.safeArguments(); observeDecision(rewrite.decision(), scope, tool);
            }
            else if (result instanceof ToolInputGuardrail.Result.Refuse refuse) {
                observeDecision(refuse.decision(), scope, tool); return new GuardedInput(null, refuse.replacement());
            }
        }
        return new GuardedInput(current, null);
    }

    private AiTool.ToolResult runOutput(List<ToolOutputGuardrail> policies, AiTool tool,
                                        AiTool.ToolArguments arguments, AiTool.ToolResult supplied,
                                        ExecutionScope scope) {
        AiTool.ToolResult current = supplied;
        for (ToolOutputGuardrail policy : policies) {
            ToolOutputGuardrail.Result result;
            try {
                result = Objects.requireNonNull(policy.evaluate(
                        new ToolOutputGuardrail.Request(tool.specification(), arguments, current, scope)));
            } catch (RuntimeException unavailable) {
                return unavailableResult(current.metadata());
            }
            if (result instanceof ToolOutputGuardrail.Result.Allow allow) {
                current = allow.output(); observeDecision(allow.decision(), scope, tool);
            } else if (result instanceof ToolOutputGuardrail.Result.Rewrite rewrite) {
                current = rewrite.safeOutput(); observeDecision(rewrite.decision(), scope, tool);
            } else if (result instanceof ToolOutputGuardrail.Result.Refuse refuse) {
                current = refuse.replacement(); observeDecision(refuse.decision(), scope, tool);
            }
        }
        return current;
    }

    private AiTool.ToolResult unavailableResult(Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = new java.util.LinkedHashMap<>(
                metadata != null ? metadata : Map.of());
        safeMetadata.put("policy_unavailable", true);
        return new AiTool.ToolResult(POLICY_UNAVAILABLE_RESULT, Map.copyOf(safeMetadata));
    }

    private void abort(List<ToolAuthorizationPolicy> policies,
                       ToolAuthorizationPolicy.Request request) {
        policies.forEach(policy -> {
            try {
                policy.afterAborted(request);
            } catch (RuntimeException ignored) {
                // The provider was not invoked; continue releasing every acquired lease.
            }
        });
    }

    private void failed(List<ToolAuthorizationPolicy> policies,
                        ToolAuthorizationPolicy.Request request,
                        RuntimeException providerFailure) {
        policies.forEach(policy -> {
            try {
                policy.afterFailure(request, providerFailure);
            } catch (RuntimeException cleanupFailure) {
                providerFailure.addSuppressed(cleanupFailure);
            }
        });
    }

    private void completed(List<ToolAuthorizationPolicy> policies,
                           ToolAuthorizationPolicy.Request request,
                           AiTool.ToolResult result) {
        RuntimeException firstFailure = null;
        for (ToolAuthorizationPolicy policy : policies) {
            try {
                policy.afterExecution(request, result);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
    }

    private AiTool.ToolResult bound(AiTool.ToolResult raw) {
        byte[] bytes = raw.json().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= rawOutputByteLimit) return raw;
        int limit = Math.toIntExact(Math.min(rawOutputByteLimit, Integer.MAX_VALUE));
        String bounded = new String(bytes, 0, limit, StandardCharsets.UTF_8)
                + "\n[Tool output truncated by the application boundary]";
        return new AiTool.ToolResult(bounded, raw.metadata());
    }

    private void observeDecision(GuardrailDecision decision, ExecutionScope scope, AiTool tool) {
        observer.observe(ExecutionObservation.of("guardrail.tool.decision", scope,
                Map.of("decision_id", decision.decisionId(), "policy_id", decision.policyId(),
                        "action", decision.action().name(), "tool_id", tool.specification().id().value())));
    }

    private record GuardedInput(AiTool.ToolArguments arguments, AiTool.ToolResult refusal) { }

    @FunctionalInterface
    public interface RequestFence {
        RequestFence ALLOW = ignored -> { };
        void verifyActive(ExecutionScope scope);
    }
}
