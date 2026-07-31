package org.oagi.score.gateway.http.api.ai_management.workflow;

import org.oagi.score.gateway.http.api.ai_management.agent.Agent;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentChatSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentDecision;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentFailure;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentFactory;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentExecutionRecorder;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentGuardrailRefusedException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInvocation;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunRequest;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentRunResult;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentResponseContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentSession;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentToolBinding;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentWorkflowContext;
import org.oagi.score.gateway.http.api.ai_management.agent.AiAgentCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentInstructions;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModel;
import org.oagi.score.gateway.http.api.ai_management.agent.AiModelCatalog;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryLimitException;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutputRetryHandoffException;
import org.oagi.score.gateway.http.api.ai_management.agent.AiMessage;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentIdentityProvider;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentOutput;
import org.oagi.score.gateway.http.api.ai_management.agent.AgentPolicyEngine;
import org.oagi.score.gateway.http.api.ai_management.agent.AssistantAgent;
import org.oagi.score.gateway.http.api.ai_management.execution.AgentExecutionService;
import org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrailChain;
import org.oagi.score.gateway.http.api.ai_management.observability.ScoreAiObservability;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowPlan;
import org.oagi.score.gateway.http.api.ai_management.model.AiWorkflowFeedback;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddleware;
import org.oagi.score.gateway.http.api.ai_management.middleware.AiMiddlewareChain;
import org.oagi.score.gateway.http.api.ai_management.middleware.MiddlewareState;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * The single Agent execution engine.
 *
 * <p>Agents are immutable definitions. This runner indexes many definitions,
 * prepares each requested turn, binds the model and tools, invokes the lower
 * level model port, delegates interpretation to the definition's response
 * handler, and applies output guardrails to that final decision. No Agent class
 * implements this type.</p>
 */
@Component
public final class AgentRunner implements AgentIdentityProvider {

    private static final int MAX_OUTPUT_GUARDRAIL_RETRIES = 2;
    private static final MiddlewareState.Key<AgentAssignmentRun.Lifecycle>
            ASSIGNMENT_LIFECYCLE = new MiddlewareState.Key<>(
            "agent-runner", "assignment-lifecycle", AgentAssignmentRun.Lifecycle.class);
    public static final String OUTPUT_GUARDRAIL_APPLIED = AgentOutput.OUTPUT_GUARDRAIL_APPLIED;
    public static final String OUTPUT_GUARDRAIL_SCOPE = AgentOutput.OUTPUT_GUARDRAIL_SCOPE;

    private final AgentExecutionService execution;
    private final AiModelCatalog models;
    private final AgentDirectory directory;
    private final AgentPolicyEngine policies;
    private final AgentFactory factory = AgentFactory.binding();
    private final String rootAgentId;
    private final AiMiddlewareChain middleware;

    public AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                       AiAgentCatalog catalog,
                       AgentInstructions instructions, ObjectProvider<Agent> agents) {
        this(execution, models, catalog, instructions,
                agents != null ? agents.orderedStream().toList() : List.of(),
                null, ScoreAiObservability.noop(), AiMiddlewareChain.none());
    }

    @Autowired
    public AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                       AiAgentCatalog catalog,
                       AgentInstructions instructions, ObjectProvider<Agent> agents,
                       AgentOutputGuardrailChain outputGuardrails,
                       ScoreAiObservability observability,
                       AiMiddlewareChain middleware) {
        this(execution, models, catalog, instructions,
                agents != null ? agents.orderedStream().toList() : List.of(),
                outputGuardrails, observability, middleware);
    }

    /** Compatibility constructor for callers predating configurable middleware. */
    public AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                       AiAgentCatalog catalog,
                       AgentInstructions instructions, ObjectProvider<Agent> agents,
                       AgentOutputGuardrailChain outputGuardrails,
                       ScoreAiObservability observability) {
        this(execution, models, catalog, instructions,
                agents != null ? agents.orderedStream().toList() : List.of(),
                outputGuardrails, observability, AiMiddlewareChain.none());
    }

    /** Constructor for focused tests with an explicit shared execution port. */
    public AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                       AiAgentCatalog catalog, AgentInstructions instructions,
                       List<? extends Agent> agents) {
        this(execution, models, catalog, instructions, agents,
                null, ScoreAiObservability.noop(), AiMiddlewareChain.none());
    }

    AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                AiAgentCatalog catalog, AgentInstructions instructions,
                List<? extends Agent> agents, AiMiddlewareChain middleware) {
        this(execution, models, catalog, instructions, agents,
                null, ScoreAiObservability.noop(), middleware);
    }

    private AgentRunner(AgentExecutionService execution, AiModelCatalog models,
                        AiAgentCatalog catalog,
                        AgentInstructions instructions, List<? extends Agent> agents,
                        AgentOutputGuardrailChain outputGuardrails,
                        ScoreAiObservability observability,
                        AiMiddlewareChain middleware) {
        this.execution = execution;
        this.models = models;
        this.policies = new AgentPolicyEngine(execution != null);
        this.rootAgentId = rootAgentId(agents);
        this.directory = new AgentDirectory(catalog, instructions, agents, outputGuardrails,
                observability);
        this.middleware = middleware != null ? middleware : AiMiddlewareChain.none();
    }

    @Override
    public String rootAgentId() {
        return rootAgentId;
    }

    /** Whether opaque Runner-issued evidence covers this exact public candidate. */
    public static boolean publicOutputGuardrailApplied(AgentOutput output) {
        return output != null && output.passedOutputGuardrail(
                org.oagi.score.gateway.http.api.ai_management.guardrail.AgentOutputGuardrail.Scope.PUBLIC);
    }

    private static String rootAgentId(List<? extends Agent> agents) {
        if (agents != null) {
            for (Agent agent : agents) {
                if (agent != null && agent.callId().equals(AssistantAgent.ASSISTANT_ID)) {
                    return agent.id().value();
                }
            }
            for (Agent agent : agents) {
                if (agent != null) return agent.id().value();
            }
        }
        return AssistantAgent.ASSISTANT_ID.value();
    }

    /** Constructor for tests whose definitions return Skip decisions. */
    public AgentRunner(AgentExecutionService execution, List<? extends Agent> agents) {
        this(execution, null, null, null, agents,
                null, ScoreAiObservability.noop(), AiMiddlewareChain.none());
    }

    public AgentDecision run(Agent.AgentId id, AgentWorkflowContext context) {
        Objects.requireNonNull(id, "id");
        return run(resolve(id), context);
    }

    /** Executes one definition through the common Agent lifecycle. */
    public AgentDecision run(Agent agent, AgentWorkflowContext context) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(context, "context");
        context.progress();
        // A catalog-backed definition may reload between calls. Snapshot it once so
        // identity, handlers, instruction, tools, and guardrails describe one turn.
        agent = new org.oagi.score.gateway.http.api.ai_management.agent.DefinedAgent(
                Objects.requireNonNull(agent.definition(), "Agent definition"));
        MiddlewareState state = new MiddlewareState();
        try {
            AiMiddlewareChain.AgentExecution middlewareExecution = middleware.executeAgentWithContext(
                    new AiMiddleware.AgentContext(agent, context, state),
                    middlewareContext -> runCore(middlewareContext.agent(),
                            middlewareContext.workflow(), middlewareContext.state()));
            AgentDecision checked = ensureMiddlewareOutputChecked(
                    middlewareExecution.context().agent(),
                    middlewareExecution.context().workflow(), middlewareExecution.decision());
            if (checked instanceof AgentDecision.Complete complete) {
                state.get(ASSIGNMENT_LIFECYCLE)
                        .ifPresent(lifecycle -> lifecycle.completed().accept(complete.result()));
            } else if (context.assignment() != null
                    && context.assignment().delegation() == AiWorkflowPlan.Delegation.DIRECT) {
                throw new IllegalStateException(
                        "A DIRECT Agent assignment cannot hand off or delegate.");
            }
            middlewareExecution.context().workflow().progress();
            return checked;
        } catch (RuntimeException failure) {
            var lifecycle = state.get(ASSIGNMENT_LIFECYCLE);
            if (lifecycle.isPresent()) {
                lifecycle.orElseThrow().failed().accept(failure);
            } else {
                AgentAssignmentRun.failPreflight(agent, context, null, failure);
            }
            throw failure;
        }
    }

    /** Middleware may short-circuit or replace a decision, but never bypass final output policy. */
    private AgentDecision ensureMiddlewareOutputChecked(Agent agent,
                                                        AgentWorkflowContext context,
                                                        AgentDecision decision) {
        if (decision instanceof AgentDecision.Complete complete
                && agent.guardrails().hasOutput()
                && complete.result().passedOutputGuardrail(agent.guardrails().outputScope())) {
            return decision;
        }
        return policies.applyTerminalDecision(agent, context, decision);
    }

    private AgentDecision runCore(Agent agent, AgentWorkflowContext context,
                                  MiddlewareState middlewareState) {
        AgentWorkflowContext current = context;
        if (current.assignment() != null) {
            // Marks that runCore owns any preflight failure. The outer middleware
            // boundary only emits a fallback terminal when runCore was never entered.
            middlewareState.put(ASSIGNMENT_LIFECYCLE, AgentAssignmentRun.Lifecycle.noop());
        }
        current.checkpoint();
        current.progress();
        AgentAssignmentRun.Lifecycle activeLifecycle = null;
        AgentAssignmentRun.UsageSource dedicatedUsageSource = null;
        AgentAssignmentRun.preflightStarted(agent, current);
        try {
            PreparedRequest initial = prepareRequest(agent, current);
            current = initial.context();
            current.progress();
            AgentRunRequest prepared = initial.request();
            if (prepared instanceof AgentRunRequest.Skip guardedSkip) {
                activeLifecycle = AgentAssignmentRun.lifecycle(agent, current, prepared);
                middlewareState.put(ASSIGNMENT_LIFECYCLE, activeLifecycle);
                activeLifecycle.started().run();
                current.progress();
                current.checkpoint();
                AgentDecision decision = policies.applyTerminalDecision(agent, current,
                        guardedSkip.decision());
                current.checkpoint();
                current.progress();
                return decision;
            }

            String retryFeedback = null;
            dedicatedUsageSource = AgentAssignmentRun.registerUsage(agent, current, prepared);
            activeLifecycle = AgentAssignmentRun.lifecycle(agent, current, prepared);
            middlewareState.put(ASSIGNMENT_LIFECYCLE, activeLifecycle);
            activeLifecycle.started().run();
            current.progress();
            AgentToolBinding resolvedBinding = null;
            for (int retry = 0; retry <= MAX_OUTPUT_GUARDRAIL_RETRIES; retry++) {
                current.checkpoint();
                AgentRunRequest attempt = retryFeedback != null
                        ? policies.applyRetryFeedback(prepared, retryFeedback) : prepared;
                // Always evaluate the concrete request returned by the handler. A handler
                // may choose not to reuse a context input that was rewritten before Skip.
                AgentRunRequest guarded = policies.applyInput(agent, current, attempt);
                if (resolvedBinding == null) resolvedBinding = ownedTools(agent, current);
                AgentToolBinding attemptBinding = executionBinding(guarded, resolvedBinding);
                AgentRetrySafety.Activity toolActivityBefore =
                        AgentRetrySafety.snapshot(current, guarded);
                current.checkpoint();
                executionRecorder(current, guarded).verifyActive();
                AgentRunResult result = executeRequest(agent, current, guarded, attemptBinding,
                        middlewareState);
                if (dedicatedUsageSource == null) {
                    current.recordUsage(agent.definition().name(), result);
                } else {
                    dedicatedUsageSource.record(result);
                }
                // Usage from an admitted call is recorded before an inactivity or
                // cancellation check rejects every remaining response-side action.
                current.progress();
                current.checkpoint();
                AgentDecision handled = Objects.requireNonNull(agent.responseHandler().handle(
                                new AgentResponseContext(agent, current, result)),
                        "Agent response handler result");
                current.progress();
                current.checkpoint();
                AgentPolicyEngine.DecisionCheck checked = policies.applyOutput(
                        agent, current, handled);
                current.progress();
                if (checked.retryFeedback() != null) {
                    AgentDecision.Complete candidate = (AgentDecision.Complete) handled;
                    if (AgentRetrySafety.changed(toolActivityBefore,
                            AgentRetrySafety.snapshot(current, guarded))
                            || AgentRetrySafety.wouldReplay(attemptBinding, guarded)) {
                        throw new AgentOutputRetryHandoffException(agent.id(),
                                candidate.result().content(), checked.retryFeedback(),
                                candidate.result().metadata());
                    }
                    if (retry == MAX_OUTPUT_GUARDRAIL_RETRIES) {
                        throw new AgentOutputRetryLimitException(agent.id(),
                                checked.retryFeedback());
                    }
                    activeLifecycle.retried().run();
                    current.progress();
                    current = current.withFeedback(
                            new AiWorkflowFeedback(
                                    current.iteration(), current.workflow() != null
                                            ? current.workflow().root().id() : null,
                                    result.response().content(), checked.retryFeedback(),
                                    "Regenerate a response that satisfies the output policy."));
                    retryFeedback = checked.retryFeedback();
                    continue;
                }
                current.checkpoint();
                current.progress();
                return checked.decision();
            }
            throw new IllegalStateException("Agent execution did not reach a terminal result.");
        } catch (AgentOutputRetryHandoffException | AgentOutputRetryLimitException terminal) {
            AgentAssignmentRun.failPreflight(agent, current, activeLifecycle, terminal);
            throw terminal;
        } catch (AgentInvocationStalledException terminal) {
            AgentAssignmentRun.failPreflight(agent, current, activeLifecycle, terminal);
            throw terminal;
        } catch (CancellationException | AgentGuardrailRefusedException terminal) {
            AgentAssignmentRun.failPreflight(agent, current, activeLifecycle, terminal);
            throw terminal;
        } catch (RuntimeException failure) {
            AgentAssignmentRun.failPreflight(agent, current, activeLifecycle, failure);
            current.checkpoint();
            AgentDecision recovered = agent.responseHandler().onFailure(
                    new AgentFailure(agent, current, failure));
            current.progress();
            current.checkpoint();
            AgentDecision guarded = policies.applyTerminalDecision(agent, current,
                    Objects.requireNonNull(recovered, "Agent failure handler result"));
            current.checkpoint();
            return guarded;
        } finally {
            if (dedicatedUsageSource != null) dedicatedUsageSource.complete();
        }
    }

    private PreparedRequest prepareRequest(Agent agent, AgentWorkflowContext context) {
        AgentRunRequest prepared = Objects.requireNonNull(
                agent.requestHandler().prepare(agent, context),
                "Agent request handler result");
        if (!(prepared instanceof AgentRunRequest.Skip)) {
            return new PreparedRequest(context, prepared);
        }

        AgentWorkflowContext guardedContext = policies.prepareInputContext(agent, context);
        if (guardedContext == context) return new PreparedRequest(context, prepared);
        AgentRunRequest guarded = Objects.requireNonNull(
                agent.requestHandler().prepare(agent, guardedContext),
                "Agent request handler result");
        return new PreparedRequest(guardedContext, guarded);
    }

    public Agent agent(String id) {
        return directory.resolve(new Agent.AgentId(id));
    }

    Agent role(String id) {
        return directory.role(id);
    }

    Agent resolve(Agent.AgentId id) {
        return directory.resolve(id);
    }

    boolean isEmpty() {
        return directory.isEmpty();
    }

    boolean assignable(String id) {
        return directory.assignable(id);
    }

    Agent assigned(AiWorkflowPlan.AgentTask task) {
        return directory.assigned(task);
    }

    private AgentRunResult executeRequest(Agent agent, AgentWorkflowContext context,
                                          AgentRunRequest request,
                                          AgentToolBinding selectedBinding,
                                          MiddlewareState middlewareState) {
        return middleware.executeModel(new AiMiddleware.ModelContext(agent, context,
                        request, selectedBinding, middlewareState),
                modelContext -> executeRequestUnwrapped(modelContext.agent(),
                        modelContext.workflow(), modelContext.request(), modelContext.tools(),
                        modelContext.state()));
    }

    private AgentRunResult executeRequestUnwrapped(Agent agent, AgentWorkflowContext context,
                                                   AgentRunRequest request,
                                                   AgentToolBinding selectedBinding,
                                                   MiddlewareState middlewareState) {
        if (request instanceof AgentRunRequest.Model model) {
            if (execution == null || models == null) {
                throw new IllegalStateException("No model execution service is configured.");
            }
            try {
                AgentToolBinding binding = selectedBinding.withExecutionFence(
                        context.execution().recorder(), context::checkpoint, context::progress)
                        .withMiddleware(middleware, middlewareState);
                AgentSession session = factory.create(agent, models.require(model.modelName()),
                        binding.tools());
                AgentSession instructed = new AgentSession(session.agent(), session.model(),
                        model.instruction(), session.tools());
                context.checkpoint();
                context.execution().recorder().verifyActive();
                AgentRunResult result = executeModel(new AgentInvocation(null, instructed,
                        model.input(), model.history(), model.scope(), binding.gateway(),
                        model.observationContext(), context.execution().recorder()));
                return result;
            } catch (RuntimeException failure) {
                throw failure;
            }
        }
        if (request instanceof AgentRunRequest.Chat chat) {
            if (execution == null) {
                throw new IllegalStateException("No chat execution service is configured.");
            }
            try {
                AgentToolBinding binding = selectedBinding.withExecutionFence(
                        chat.context().recorder(), context::checkpoint, context::progress)
                        .withMiddleware(middleware, middlewareState);
                AgentExecutionContext executionContext = chat.context()
                        .withAgentIdentity(agent.id().value(), chat.context().executionPurpose());
                if (!binding.transportInherited()) {
                    executionContext = executionContext.withToolBinding(binding);
                }
                context.checkpoint();
                executionContext.recorder().verifyActive();
                Agent.Instruction instruction = chat.instruction() != null
                        ? chat.instruction()
                        : agent.definition().instruction().render(
                        chat.context().instructionParameters());
                instruction = executionContext.finalizeInstruction(instruction);
                AgentChatResult result = execution.executeChat(new AgentChatSession(
                        agent, chat.context().modelName(), instruction,
                        executionContext, binding, middlewareState, context::progress,
                        context.runControl()));
                AgentRunResult runResult = new AgentRunResult(
                        new AiMessage.Assistant(result.answer()), List.of(), result.usage(),
                        new AgentRunResult.RunMetadata(agent.id(),
                                new AiModel.ModelId(context.request().modelName()),
                                null, result.traceMetadata()));
                return runResult;
            } catch (RuntimeException failure) {
                throw failure;
            }
        }
        throw new IllegalStateException("Unsupported Agent request: " + request.getClass().getName());
    }

    private AgentToolBinding ownedTools(Agent agent, AgentWorkflowContext context) {
        return Objects.requireNonNull(agent.toolHandler().resolve(agent, context),
                "Agent Tool handler must return a binding");
    }

    private AgentRunResult executeModel(AgentInvocation invocation) {
        if (execution == null) {
            throw new IllegalStateException("No model execution service is configured.");
        }
        return execution.execute(invocation);
    }

    private AgentToolBinding executionBinding(AgentRunRequest request,
                                              AgentToolBinding selected) {
        return request instanceof AgentRunRequest.Model && selected.transportInherited()
                ? AgentToolBinding.none() : selected;
    }

    private AgentExecutionRecorder executionRecorder(AgentWorkflowContext context,
                                                      AgentRunRequest request) {
        return request instanceof AgentRunRequest.Chat chat
                ? chat.context().recorder() : context.execution().recorder();
    }

    private record PreparedRequest(AgentWorkflowContext context, AgentRunRequest request) { }

}
