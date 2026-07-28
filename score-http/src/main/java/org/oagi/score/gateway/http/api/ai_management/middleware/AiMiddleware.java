package org.oagi.score.gateway.http.api.ai_management.middleware;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.ExecutionScope;
import org.oagi.score.gateway.http.api.ai_management.tool.AiTool;

import java.util.Objects;
import java.util.Map;

/**
 * Provider-neutral interception contract for the common Agent, model, and Tool boundaries.
 * Implementations are registered as Java beans and selected by stable {@link #id()} values.
 */
public interface AiMiddleware {

    String id();

    /** Required registrations are automatically present in every profile. */
    default boolean required() {
        return false;
    }

    /** Receives this registration's declarative, string-only policy settings at startup. */
    default void configure(Settings settings) {
    }

    default AgentResult beforeAgent(AgentContext context) {
        return AgentResult.continueWith(context);
    }

    default ModelResult beforeModel(ModelContext context) {
        return ModelResult.continueWith(context);
    }

    default AgentRunResult wrapModelCall(ModelContext context, ModelCall next) {
        return next.call(context);
    }

    default AiTool.ToolResult wrapToolCall(ToolContext context, ToolCall next) {
        return next.call(context);
    }

    default ModelResult afterModel(ModelContext context, AgentRunResult response) {
        return ModelResult.completeWith(response);
    }

    default AgentResult afterAgent(AgentContext context, AgentDecision response) {
        return AgentResult.completeWith(response);
    }

    @FunctionalInterface
    interface AgentCall {
        AgentDecision call(AgentContext context);
    }

    @FunctionalInterface
    interface ModelCall {
        AgentRunResult call(ModelContext context);
    }

    @FunctionalInterface
    interface ToolCall {
        AiTool.ToolResult call(ToolContext context);
    }

    record Settings(Map<String, String> values) {
        public Settings {
            values = values != null ? Map.copyOf(values) : Map.of();
        }
    }

    record AgentContext(Agent agent, AgentWorkflowContext workflow, MiddlewareState state) {
        public AgentContext {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(state, "state");
        }

        public ExecutionScope scope() {
            return workflow.executionScope(workflow.execution().executionPurpose());
        }

        public AgentContext withWorkflow(AgentWorkflowContext replacement) {
            return new AgentContext(agent, replacement, state);
        }
    }

    record ModelContext(Agent agent, AgentWorkflowContext workflow,
                        AgentRunRequest request, AgentToolBinding tools,
                        MiddlewareState state) {
        public ModelContext {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(workflow, "workflow");
            Objects.requireNonNull(request, "request");
            tools = tools != null ? tools : AgentToolBinding.none();
            Objects.requireNonNull(state, "state");
        }

        public ExecutionScope scope() {
            if (request instanceof AgentRunRequest.Model model) return model.scope();
            if (request instanceof AgentRunRequest.Chat chat) {
                return new ExecutionScope(chat.context().requestId(), chat.context().conversationId(),
                        chat.context().requesterId(), chat.context().agentDepth(),
                        chat.context().executionPurpose(), chat.context().guardrailDecisionIds());
            }
            throw new IllegalStateException("A Skip request has no model scope.");
        }

        public ModelContext withRequest(AgentRunRequest replacement) {
            return new ModelContext(agent, workflow, replacement, tools, state);
        }

        public ModelContext withTools(AgentToolBinding replacement) {
            return new ModelContext(agent, workflow, request, replacement, state);
        }
    }

    record ToolContext(AiTool.ToolSpecification tool, AiTool.ToolArguments arguments,
                       ExecutionScope scope, MiddlewareState state) {
        public ToolContext {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(state, "state");
        }

        public ToolContext withArguments(AiTool.ToolArguments replacement) {
            return new ToolContext(tool, replacement, scope, state);
        }
    }

    /** Exactly one of {@code context} and {@code response} is populated. */
    record AgentResult(AgentContext context, AgentDecision response) {
        public AgentResult {
            if ((context == null) == (response == null)) {
                throw new IllegalArgumentException("Agent middleware result requires one value.");
            }
        }

        public static AgentResult continueWith(AgentContext context) {
            return new AgentResult(Objects.requireNonNull(context), null);
        }

        public static AgentResult completeWith(AgentDecision response) {
            return new AgentResult(null, Objects.requireNonNull(response));
        }
    }

    /** Exactly one of {@code context} and {@code response} is populated. */
    record ModelResult(ModelContext context, AgentRunResult response) {
        public ModelResult {
            if ((context == null) == (response == null)) {
                throw new IllegalArgumentException("Model middleware result requires one value.");
            }
        }

        public static ModelResult continueWith(ModelContext context) {
            return new ModelResult(Objects.requireNonNull(context), null);
        }

        public static ModelResult completeWith(AgentRunResult response) {
            return new ModelResult(null, Objects.requireNonNull(response));
        }
    }
}
