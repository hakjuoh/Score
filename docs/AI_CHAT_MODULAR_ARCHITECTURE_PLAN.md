# AI Chat Modular Architecture and Refactoring Plan

> Status: **IN PROGRESS — FOUNDATION IMPLEMENTED**
>
> Date: **2026-07-22**
>
> Scope: establish `Agent` as the central domain concept, standardize all model execution on Spring AI,
> enforce input and output Guardrails around AI processing, add a low-latency Gateway Agent for bounded
> per-turn simple-request handling, and keep workflows, tools, conversation history, compaction, mutation confirmation,
> and trajectory composable within `score-http`.

## Implementation checkpoint — 2026-07-22

The first production slice and the protocol-neutral seams for the following slices are implemented:

- a common `Agent` identity contract implemented by static pipeline Agents, plus configured `AiModel`,
  `ResolvedAgent`, `ToolSet`, immutable invocation/result, and trusted `ExecutionScope` types;
- mandatory Agent input/output and Tool input/output Guardrail chains, including pre-disclosure output
  buffering and bounded response-only retry;
- the per-turn no-Tool Gateway Agent with the closed direct-intent set, a separately configurable
  low-cost/low-latency Model, and deferred history loading on handoff;
- one protocol-neutral `ToolExecutionGateway`, with MCP callbacks adapted through it, exact mutation
  confirmation implemented as authorization middleware, cancellation fencing, output bounds, and
  retained-session approval resume;
- a registration-driven installed Workflow catalog and a protocol-neutral `WorkflowInvocation` that no
  longer exposes `AiChatExecutor.Context` to Workflow implementations;
- conversation snapshot/store/commit ports, generation-fenced result commit, mandatory `ExecutionState`,
  failure-isolated observer composites, and a no-Tool Compactor Agent;
- configured model publication and the legacy definition/name generators routed through the same
  `AgentExecutionService` instead of a provider-specific Ollama inference path; and
- ArchUnit checks that prevent Spring AI/MCP, HTTP, persistence, and provider SDK types from entering the
  new core boundaries.

The compatibility migration is intentionally not marked complete yet. The remaining work is to make
`ChatService.prepare`/`chat` thin orchestration, move all legacy `AiChatExecutor.Context` mechanics and
trajectory-owned progress/state into focused execution/observer adapters, back every conversation port
with its final production adapter, and pass the complete optional-feature removal matrix in Section 8.2.
The compatibility path remains behind the new boundaries while those extractions proceed; the protected
approval, cancellation, retry, read-back, and commit-fence behaviors remain covered by regression tests.

## 1. Core concepts

This section defines the architecture's vocabulary. These definitions are normative: implementation
types and package boundaries should follow them rather than redefining the same words locally.

### 1.1 Model

A `Model` identifies a configured model that performs inference and describes its model-level
capabilities and limits.

```java
public record AiModel(
        ModelId id,
        ProviderId provider,
        ModelCapabilities capabilities,
        ContextWindow contextWindow) {
}
```

Every configured model is exposed to the application as a Spring AI `ChatModel`. Provider-specific
client construction, credentials, endpoints, and option mapping belong to Spring configuration. The
domain model does not select an SDK and does not own conversation history, tools, confirmation,
compaction, or trajectory.

`ProviderId` is descriptive configuration metadata. It is useful for model setup, diagnostics, and UI
display, but it is not an execution-strategy selector.

### 1.2 Tool

A `Tool` is a capability an Agent may call. The Agent and Model see only its name, description, input
contract, expected output contract, and declared effects.

```java
public interface AiTool {
    ToolSpecification specification();
    ToolResult execute(ToolArguments arguments, ToolExecutionContext context);
}

public record ToolSpecification(
        ToolId id,
        String name,
        String description,
        JsonSchema inputSchema,
        JsonSchema outputSchema,
        ToolEffect effect) {
}
```

MCP, web search, Bash, and application-local functions are Tool implementations or Tool providers.
Their protocols must not leak into Agent, Workflow, or conversation abstractions.

`ToolEffect` identifies behavior relevant to execution policy, such as `READ_ONLY` or `MUTATION`.
Unknown effects are treated conservatively according to deployment policy.

### 1.3 Agent

`Agent` is the common identity contract for every AI Agent. Static pipeline Agents such as Gateway,
Workflow Planner, Workflow Evaluator, and Compactor implement it directly, so Agent implementations can
be found and inspected through one type:

```java
public interface Agent {
    AgentDefinition definition();

    default AgentId id() {
        return definition().id();
    }
}
```

A `ResolvedAgent` is the immutable execution subject composed of exactly the concepts required by the
simple definition:

```text
Resolved Agent = Model + Instruction + Tool Set
```

```java
public record ResolvedAgent(
        AgentDefinition definition,
        AiModel model,
        Instruction instruction,
        ToolSet tools) implements Agent {
}
```

When executed, a resolved Agent asks its Model for a response and may use its assigned Tools. All Agent
inference is executed through the application's Spring AI integration.

An Agent is not a workflow, conversation, trajectory record, or Spring AI client object.

### 1.4 Agent definition and Agent run

An `AgentDefinition` is a reusable identity and instruction template from which a fully bound
`ResolvedAgent` is created. It does not declare a role or Tool policy and is not executable until a
Model and request-authorized concrete ToolSet are resolved. Prompt resources use three self-contained groups:

- `resources/ai/system/system-prompt-*.md` for fixed system Agents such as the root assistant, Gateway,
  Planner, Evaluator, and Compactor;
- `resources/ai/agent/agent-prompt-*.md` for dynamic Multi-Agent Workers; and
- `resources/ai/workflow/*.md` for reusable Workflow instruction templates; and
- `resources/ai/execution/execution-*.md` for reusable execution and recovery instruction templates.

`AiAgentCatalog` owns all four registries. The filename group is the visibility boundary: only
`agent-prompt-*` definitions are exposed to Workflow planning, `system-prompt-*` definitions are resolved
by fixed application Agents, and `workflow/*` and `execution/*` entries are closed instruction templates
rendered into Root, Worker, Synthesizer, or execution-recovery Agent runs. Instruction templates are not additional selectable Agent
identities; every model invocation that uses one is still owned by a catalog-backed static Agent or a
catalog-resolved `ResolvedAgent`. System and Worker definition frontmatter contains only `id`, `name`,
and `description`; execution capabilities are not prompt metadata. Workflow and execution templates
contain only the instruction body and declared `${...}` placeholders, with missing or unused values
rejected when rendered.

The configured root resource is also resolved through this catalog. An external root override uses
the same minimal frontmatter and instruction format and is reloaded when the root
Agent definition is requested. `AssistantAgent` therefore exposes the same authoritative
identity and instruction that `AiChatExecutor` executes; there is no separate root-prompt loader. The
executor snapshots that definition once per root run so hot reload cannot mix one definition's identity
with another definition's instruction in observations, persisted metadata, or the final response.

An `AgentRun` is one execution of an Agent for one request:

```java
public record AgentRun(
        RunId id,
        ResolvedAgent agent,
        AiUserMessage request,
        List<AiMessage> history,
        ExecutionScope scope) {
}
```

The same Agent may have many runs. A run owns transient execution identity and lifecycle; the Agent owns
stable behavior and capabilities.

### 1.5 Spring AI execution boundary

Spring AI is the single model-execution stack. The application does not define an alternative execution
SPI, execution implementation registry, execution ID, or SDK-selection policy.

The application-facing `AgentExecutionService` port accepts an `AgentInvocation` and returns an
`AgentRunResult`. Its only production implementation is `SpringAiAgentExecutionService`, which resolves
the selected model's Spring AI `ChatModel`, builds a `ChatClient` request, and adapts the Agent's Tools to
Spring AI callbacks. The port exists to keep orchestration independent of library types and to support
focused tests; it has no implementation selection, identity, registry, or capability negotiation.
Provider-specific differences are handled by configured Spring AI model beans and option factories.

The Spring AI boundary does not own canonical conversation history, mutation confirmation, compaction,
or trajectory persistence. It receives an immutable execution request and returns a result.

### 1.6 Workflow

A `Workflow` describes how one or more Agent runs are composed to produce a result. `direct` is the
standard workflow and requires no optional executor registration.

Examples include:

- direct;
- chain;
- parallel;
- routing;
- orchestrator-workers; and
- evaluator-optimizer.

Advanced Workflow implementations are registered globally by `WorkflowId`. Because every model call is
made through Spring AI, Workflow availability does not vary by SDK. An explicitly requested Workflow
that is not installed returns a capability error. Automatic selection chooses only installed Workflows
and falls back to `direct` when no suitable advanced Workflow is present.

### 1.7 Conversation and history

A `Conversation` is the application-owned continuity boundary between user turns. Its canonical history
is independent of model-provider sessions and Spring AI client instances.

```text
Conversation
  - current Agent/model selection
  - durable preferences
  - visible transcript
  - canonical model-facing history
```

Every Agent run receives a history snapshot as input and returns messages or output as a result. A
provider-native conversation ID may be stored as an optional optimization, but it is never the
authoritative history and the conversation must remain reconstructible without it.

### 1.8 Guardrails

`Guardrails` are mandatory, ordered policies that validate or transform content entering and leaving AI
processing. `Guardrails` is the umbrella concept; direction-specific contracts define when the policy is
applied.

The terminology follows the common Guardrails model, including the
[LangChain4j Input/Output distinction](https://docs.langchain4j.dev/tutorials/guardrails) and
[OpenAI Agents SDK Tool Guardrails](https://openai.github.io/openai-agents-python/guardrails/), but this
application owns the contracts and remains Spring AI-based. In particular, the turn-level input check
intentionally runs earlier than LangChain4j's documented LLM-adjacent Input Guardrail because an internal
policy may prohibit Workflow or RAG processing itself.

`AgentInputGuardrail` protects input before it reaches a general Agent or Model:

- `TURN` scope evaluates the normalized user request before Workflow planning, RAG, general Agent
  execution, or Tool resolution. It has a mandatory local stage that runs before any model, including
  the Gateway Agent. This is the required stage for internal policies that prohibit sending a sensitive
  request to any external Model.
- A model-assisted `TURN` policy may run after the local stage. On an eligible turn it may be batched into
  the Gateway Agent's structured inference, but it remains a separate policy decision with its own ID,
  version, retention directive, and fail-closed semantics.
- `MODEL` scope evaluates the fully assembled messages immediately before each Spring AI `ChatClient`
  call. It can detect prompt injection or prohibited data egress introduced by history, retrieved
  content, or Tool results, but it must not replace the earlier `TURN` check.
- Its outcomes are `ALLOW`, `REWRITE`, or `REFUSE`.

`AgentOutputGuardrail` protects the candidate Agent result before it becomes externally visible or
canonical:

- `ALLOW` accepts the candidate output.
- `REWRITE` produces a safe replacement, including deterministic redaction of secrets or sensitive
  fields.
- `RETRY` or `REPROMPT` requests a bounded response-only regeneration when that can be done without
  replaying Tool side effects.
- `REFUSE` suppresses the candidate output and returns an approved application-owned response.

Tool Guardrails protect every Tool invocation independently of which Agent or Workflow initiated it:

- `ToolInputGuardrail` evaluates normalized Tool arguments before execution and may `ALLOW`, `REWRITE`,
  or `REFUSE` the call.
- `ToolOutputGuardrail` evaluates the returned Tool result before it reaches the Model, UI, transcript,
  or general observers and may `ALLOW`, `REWRITE`/redact, or `REFUSE` disclosure.
- An output refusal cannot undo a Tool side effect. Mutation state and read-back remain truthful even
  when the Tool result must be suppressed or redacted.

An input or output refusal is a normal, terminal application outcome, not a provider error and not an
arbitrary Agent-generated answer. The application maps it to an approved, non-sensitive message.
Internal policy rules, classifier rationale, prohibited input, and suppressed output are not copied into
the public response.

Guardrails are not merely system-prompt instructions. Prompt instructions may reinforce behavior, but
they cannot prove that prohibited input was never processed or that unsafe output was never disclosed.
A Tool Guardrail is a semantic policy executed within the common Tool middleware pipeline; access
control, mutation confirmation, cancellation fencing, observation, and output-size limiting remain
separate middleware responsibilities.

If a Guardrail requires model-assisted classification, it uses a dedicated no-Tool Guardrail Agent with
`GUARDRAIL_EVALUATION` execution purpose. On an eligible turn, the no-Tool Gateway Agent may batch the same
policy fields with routing under trusted `GATEWAY_ROUTING` purpose. Either path is exempt only from
recursively applying the Guardrail it is evaluating, uses a policy-approved Model, returns a strict
structured decision, and never invents the user-facing refusal text. Content that policy forbids sending
to any external Model must be evaluated locally or by deterministic rules before either path.

### 1.9 Gateway Agent

`GatewayAgent` is a specialized, no-Tool Agent backed by a separately configured lightweight Spring AI
`ChatModel`. It handles every eligible user turn with a strict structured result:

- `DIRECT` only for a closed set of low-risk intents such as greeting, thanks, and basic capability/help
  questions that need no conversation retrieval, Workflow, or Tool;
- `HANDOFF` for every contextual, domain, Tool-requiring, mutating, or otherwise non-trivial request; and
- `REVIEW` when the request is policy-sensitive or the classification confidence is insufficient.

Its identity, description, and instruction are registered as a self-contained
`resources/ai/system/system-prompt-*.md` definition. The Gateway service resolves that definition through
the common catalog using its application registration name and binds the configured Model with an empty
request ToolSet. Because only `agent-prompt-*` entries are published to planning, the Planner cannot
assign the Gateway as a Workflow Worker.

For `DIRECT`, the Gateway Agent may produce a small candidate response itself. That candidate still
passes the applicable Agent Output Guardrails and the normal fenced conversation commit before it is
visible. It cannot call Tools, mutate state, read broad conversation history, or bypass retention rules.

The Gateway Agent is a latency optimization, not a security boundary and not an alternative execution
stack. Mandatory local `TURN` Guardrails always precede it. Its structured inference may batch
model-assisted `TURN` policy classification and routing into one model call, but the policy decision and
route decision remain logically separate. A policy refusal terminates the turn; a routing failure falls
back to the normal flow, which performs any still-required Guardrail evaluation before proceeding.

After a successful `HANDOFF`, the validated turn and its Guardrail decision IDs are carried in
`ExecutionScope`; the normal flow does not repeat an equivalent ingress classification. The Gateway
receives only the current turn, so a contextual or domain follow-up hands off before broad conversation
history is loaded.

Gateway Model selection, prompt, confidence threshold, direct-intent set, and execution purpose are
trusted server configuration. A client, stored preference, prompt, or Tool result cannot force
`DIRECT`, select the Gateway Model, or manufacture a completed Guardrail decision.
Every model-backed Gateway result carries the actually executed Gateway Agent and Model identity.
Gateway route and direct-response trajectory rows persist that identity rather than the conversation's
root Model selection; local pre-inference handoffs have no Gateway execution identity.

### 1.10 Middleware

Middleware is ordered behavior that may inspect, transform, block, or surround an operation.

Mutation confirmation is Tool execution middleware because it may stop a mutation Tool call and replace
it with an approval-required result. Input normalization, effect authorization, cancellation fencing,
and output limiting are also Tool execution middleware. Agent and Tool Guardrail chains may be
implemented as boundary middleware, but their domain contracts remain explicit.

Middleware is not appropriate for passive recording because a recording component must not change the
operation's result.

### 1.11 Hook and observer

A hook or observer receives lifecycle facts but cannot alter the execution result. Trajectory recording,
metrics export, audit logging, and UI lifecycle events use observers.

```java
public interface ExecutionObserver {
    void observe(ExecutionObservation observation);
}
```

An empty observer composite is valid and means that no optional observations are persisted.

### 1.12 Facade

A facade exposes a small application API over several subsystems. `ChatService` should be a facade, not
the implementation location for Agent execution, Tool policy, compaction, trajectory, or Spring AI
request construction.

### 1.13 Compaction

Compaction is a normal direct Agent run whose request is to summarize existing context into a smaller,
factual memory. It requires only:

- an Agent with the selected Model;
- a compaction instruction resource;
- no Tools; and
- the standard Spring AI execution path.

Automatic compaction is a policy that decides when to invoke this Agent; manual `/compact` is a command
that invokes the same service. Compaction does not require an advanced Workflow or a separate model-call
mechanism.

### 1.14 Mutation confirmation

Mutation confirmation is an optional authorization policy around mutation Tool execution. It is neither
an Agent concern nor a model-execution concern.

When present, it may pause a Tool call until an exact grant is supplied. When absent, there is no approval
concept and mutation Tools proceed through the remaining safety middleware.

Read-only filtering, cancellation fencing, and post-mutation read-back are not confirmation features and
must continue to work without it.

### 1.15 Trajectory

Trajectory is an optional persisted projection of Guardrail, Agent, Workflow, Model, and Tool
observations. It is not authoritative conversation state and cannot be required for correct execution.

### 1.16 Naming rule

| Name | Use when |
|---|---|
| Middleware | The component can transform, block, or surround an operation |
| Guardrail | The component validates or transforms content at an AI input or output boundary |
| Observer/hook | The component only reacts to facts and cannot affect the result |
| Policy | The component makes a decision, such as when to compact |
| Adapter | The component translates a core contract to MCP or Spring AI types |
| Facade | The component exposes a smaller application API over multiple subsystems |
| Registry/catalog | The component resolves installed extensions or definitions |

## 2. Relationships between concepts

### 2.1 Dependency direction

The central relationship is that an application-owned Agent is executed through one Spring AI boundary.
That boundary translates the Agent's Tools into Spring AI declarations, but all actual Tool calls return
to the application-owned Tool execution boundary.

```text
Chat request
    |
    v
ChatFacade -----------------------> ConversationService
    |                                  | begin fence
    |                                  | load full history only on handoff
    v                                  v
PreparedChatTurn ----------------> LocalTurnGuardrailChain
                                       | REFUSE -> policy-controlled ChatResponse
                                       | ALLOW / REWRITE
                                       v
                         eligible Gateway turn?
                              | yes                 | no
                              v                     |
                  GatewayAgent (light Model,       |
                  prompt, empty ToolSet)            |
                              |                     |
                  +-----------+-----------+         |
                  |           |           |         |
               DIRECT       REVIEW      HANDOFF <---+
                  |           |           |
          fast output      enhanced       v
           Guardrails       policy   ModelSelector
                  |                      |
                  v                      v
          fenced commit       AgentFactory -- Model + Instruction + ToolSet --> ResolvedAgent
                                         |
                                         v
                            WorkflowResolver -- installed WorkflowId set
                                         |
                                         v
                            WorkflowExecutor -- composes Agent runs
                                         |
                                         v
                               AgentExecutionService
                                         |
                                         v
                            SpringAiAgentExecutionService
                                         |
                 +-----------------------+-----------------------+
                 |                       |                       |
       ModelAgentInputGuardrailChain  SpringAiModelCatalog  SpringAiToolAdapter
                                                            |
                                                            v
                                                   ToolExecutionGateway
                                                            |
                                                   Tool middleware chain
                                                            |
                                                   ToolInputGuardrailChain
                                                            |
                                                            v
                                                          AiTool
                                                            |
                                                   ToolOutputGuardrailChain

Candidate AgentRunResult --> AgentOutputGuardrailChain
                                | REWRITE / REFUSE / bounded RETRY
                                v
Validated AgentRunResult --> public stream + fenced conversation commit
       |
       +--> ExecutionObserver composite --> trajectory / UI events / metrics
```

Dependencies point toward the core contracts inside the `ai_management` package tree. Spring AI
integration, Workflow, Tool provider, and optional feature packages depend on the core packages; the
core packages never import MCP, HTTP payload/controller, persistence-adapter, or provider-specific SDK
types.

### 2.2 Guardrails and Agent execution

The local stage of the `TURN` Agent Input Guardrail chain is evaluated once for a normalized user turn
before any model or Agent sees that turn. If local policy allows model processing, the remaining
model-assisted `TURN` policies may be evaluated by the Gateway Agent or by the normal Guardrail path.
Their decision IDs are carried in `ExecutionScope`, so every derived planner, worker, evaluator, and
synthesizer run can be traced to the decisions. A later boundary that introduces new user-controlled
content must evaluate that new content before it enters an Agent run.

The `MODEL` Agent Input Guardrail chain runs immediately before every model call against the final assembled
messages. This second scope protects model-bound data that did not exist at turn ingress. Internal runs
such as compaction have explicit execution purposes and direction-appropriate Guardrail policies.

An Agent Input Guardrail `REFUSE` short-circuits the applicable path. A local turn-level refusal performs
no model call, Workflow planning, Agent execution, Tool resolution, or Tool call. A model-assisted
turn-level refusal permits only the dedicated policy/Gateway inference that produced the decision; it
performs no general Agent, Workflow, or Tool work. A model-level refusal blocks that model call. Refused
content may be recorded only according to its retention directive and must not be copied into
model-facing history, observations, logs, or analytics when policy disallows retention.

The Agent Output Guardrail chain receives a candidate result before it is streamed, returned, added to
canonical history, or published to general observers. Redaction is an output `REWRITE`; complete
suppression is an output `REFUSE`. Raw rejected output remains transient and is visible only to the
minimum trusted policy components.

Tool Input and Output Guardrails run for every call through `ToolExecutionGateway`, including calls made
by planners, workers, and response-only Agents if they are ever given Tools. They are resolved from
baseline policy plus Tool-specific registrations. OpenAI-style decorators or Java annotations may be
used as registration syntax, but they are not the enforcement boundary; the gateway is.

An `ALLOW` decision only permits processing to continue. It does not grant Tool access, authorize a
mutation, select a route, or override any other execution policy.

### 2.3 Gateway and root Agent

The Gateway Agent and root Agent have different authority. The Gateway Agent receives only the current
normalized turn plus bounded policy/routing context, has an empty ToolSet, and can directly answer only
the configured closed intent set. The root Agent receives canonical conversation context and the
request-authorized ToolSet and is responsible for normal domain assistance.

`GatewayResult` contains two independently validated parts: the model-assisted `TURN` Guardrail
decision and the route decision. A valid `ALLOW + HANDOFF` is reused by the normal path. A missing,
malformed, or unavailable required policy classification must be completed by an approved evaluator on
the normal path and fails closed if none is available. A missing or invalid route after policy has safely
allowed the turn falls back to the normal path. Low confidence is `REVIEW`, never `DIRECT`.

The Gateway Agent is used for every eligible turn, including follow-ups. It receives only the current
normalized turn and directly handles only the closed context-free intent set; all contextual or domain
follow-ups immediately hand off to the normal path.

Fixed Agents resolve definitions from the common catalog through explicit Spring registration names.
Their Java classes own behavior; resource-defined IDs, names, descriptions, and instructions are not
duplicated as constants, and prompt metadata does not determine Tool access.

### 2.4 Agent and Model

The Model belongs to the Agent. `SpringAiModelCatalog` resolves each configured `ModelId` to exactly one
Spring AI `ChatModel` plus model metadata. Model selection never selects a separate execution mechanism.

`AgentFactory` resolves an `AgentDefinition`, selected Model, user/request-specific Tool permissions, and
the currently installed Tool providers into a concrete `ResolvedAgent`. Missing optional Tool providers
produce a smaller ToolSet rather than an invalid Agent.

### 2.5 Agent and Tool

An Agent owns core `ToolSpecification` values and callable core Tool handles. It never owns Spring AI
`ToolCallback` or MCP session types.

The single `SpringAiToolAdapter` presents core specifications as Spring AI callbacks. When Spring AI asks
to execute a Tool, the adapter delegates to the common `ToolExecutionGateway`. Consequently
confirmation, access policy, cancellation, output limits, and observations behave identically for every
configured model and provider.

### 2.6 Agent and Workflow

A Workflow does not contain hidden model calls. Every model call in a Workflow is an Agent run. The
existing execution responsibilities therefore become explicit Agents:

| Agent responsibility | Instruction resource | Tools | Purpose |
|---|---|---|---|
| Gateway | Simple-request policy and routing prompt | None | Answer a closed set of simple intents or hand off immediately |
| Root assistant | User-facing system instruction | User-authorized ToolSet | Produce the final conversational response |
| Planner | Workflow planning system instruction | Usually none | Produce a plan or route |
| Worker | Assignment-specific Agent instruction | Workflow- and request-authorized ToolSet | Complete one delegated task |
| Evaluator | Evaluation system instruction | Usually none | Judge and request improvement |
| Synthesizer | Synthesis instruction | Workflow-specific | Combine intermediate results |
| Compactor | Compaction system instruction | None | Replace long context with concise memory |

This makes Model, prompt, and Tool assignment visible and testable for every inference, including
planning, evaluation, synthesis, and compaction.

Every Planner-produced `guideMessage`, `activeVerb`, `completedVerb`, and corresponding synthesis or
task field uses the language of the current User request. The Planner determines that language from the
current request only; recent conversation, prior assistant text, and earlier guide messages are context,
not a language selector. Internal Worker assignments remain English because they are Agent-to-Agent
instructions rather than user-facing UI text.
No deterministic language resolver rewrites Planner output. If planning fails or omits presentation
text, the fallback emits no guide sentence and uses only language-neutral progress markers; it never
invents an English guide, label, or verb for a non-English request.

Worker Tool authority is authored per task by the Workflow plan, not by the Worker definition. A task
selects `NONE`, `READ_ONLY`, or `FULL`; an omitted value defaults to `READ_ONLY` whenever the task needs
Tools. The executor intersects that request with the parent request's Tool authority, so a plan can
reduce authority but cannot increase it. `FULL` is valid only for an assignment that explicitly requires
mutation, and the normal approval barrier still governs every data-changing Tool call. This keeps one
Worker reusable for research, analysis, and mutation assignments without encoding a permanent role or
capability policy in its prompt.

Synthesis never repeats a mutation completed by a Worker. If any child was assigned `FULL`, the
subsequent parent synthesis is bounded to `READ_ONLY` (or `NONE` when no Tools are needed), treats the
completed mutation as already executed, and may only verify/read back the result. A parent synthesis may
retain `FULL` only when mutation work remains assigned to the parent rather than to a child.

### 2.7 Workflow availability

Direct execution belongs to `AgentExecutionService` and is always available when a selected Spring AI
model is configured. Advanced Workflow implementations are zero-or-more application extensions indexed
only by `WorkflowId`.

Workflow planning receives the installed Workflow descriptor set. An explicit unsupported choice fails
before inference; an automatic choice falls back to `direct` when no suitable advanced Workflow is
installed.

### 2.8 Conversation and execution

Conversation state is loaded before Agent execution and committed afterward. The execution service
receives an immutable history snapshot; it never queries or mutates a conversation repository directly.

Final history, transcript, memory, and conversation settings are committed under the existing request
generation/cancellation fence. A stale or cancelled run may be observed for diagnostics, but cannot
overwrite the current conversation.

Changing the selected Model between turns does not lose history. A provider-native conversation handle
is only a cache and may be discarded whenever the model changes or the handle becomes unusable.

### 2.9 Cross-cutting features

- Local Turn Agent Input Guardrails terminate prohibited user turns before any model; completed Turn
  policy decisions precede Workflow or general Agent execution.
- Model Agent Input Guardrails validate final model-bound messages.
- Agent Output Guardrails validate, redact, regenerate, or suppress candidate results before disclosure.
- Tool Input/Output Guardrails protect every Tool call at the common execution gateway.
- Compaction invokes a dedicated no-Tool Agent through the standard execution service.
- Mutation confirmation participates only in the Tool middleware chain.
- Trajectory subscribes only to execution observations.
- Usage collection is optional data returned through Spring AI, not a prerequisite for conversation
  flow.
- UI progress is emitted from observations and is not persisted as canonical execution state.

Removing one of these consumers or middleware entries must not create a null branch in core execution;
the composition root supplies an empty composite, an empty chain, or no policy registration.

## 3. Implementation design

### 3.1 Core Agent API

Keep reusable configuration separate from a fully resolved Agent and from a single run:

```java
public record AgentDefinition(
        AgentId id,
        String name,
        String description,
        InstructionTemplate instruction) {
}

public interface AgentFactory {
    ResolvedAgent create(AgentDefinition definition,
                         AiModel model,
                         ToolSet availableTools);
}

public record AgentInvocation(
        AgentRunId runId,
        ResolvedAgent agent,
        AiUserMessage request,
        List<AiMessage> history,
        ExecutionScope scope,
        ToolExecutionGateway tools) {
}

public record AgentRunResult(
        AiAssistantMessage response,
        List<AiMessage> generatedMessages,
        Optional<Usage> usage,
        RunMetadata metadata) {
}
```

`AgentExecutionService` is the single application entry point for running an Agent. It validates the
selected Model, builds and invokes the Spring AI request, and returns a result. It does not load or save
conversation data.

The Gateway, user-facing assistant, planner, evaluator, workers, synthesizer, and compactor all use this
API. Statically declared pipeline Agents implement `Agent`; request-resolved workers and other dynamic
subjects are represented by `ResolvedAgent`, which implements the same contract.
There must be no remaining inference path that constructs a Spring AI request directly outside the
Spring AI integration boundary.

### 3.2 Guardrail API

Guardrails return structured results; they do not throw exceptions for ordinary policy refusals and do
not generate arbitrary user-facing prose:

```java
public interface AgentInputGuardrail {
    AgentInputGuardrailResult evaluate(AgentInputGuardrailRequest request);
}

public sealed interface AgentInputGuardrailResult {
    record Allow(GuardrailDecision metadata) implements AgentInputGuardrailResult {}
    record Rewrite(AiInput safeInput, GuardrailDecision metadata)
            implements AgentInputGuardrailResult {}
    record Refuse(GuardrailRefusal refusal) implements AgentInputGuardrailResult {}
}

public interface AgentOutputGuardrail {
    AgentOutputGuardrailResult evaluate(AgentOutputGuardrailRequest request);
}

public sealed interface AgentOutputGuardrailResult {
    record Allow(AiAssistantMessage output, GuardrailDecision metadata)
            implements AgentOutputGuardrailResult {}
    record Rewrite(AiAssistantMessage safeOutput, GuardrailDecision metadata)
            implements AgentOutputGuardrailResult {}
    record Retry(GuardrailFeedback feedback) implements AgentOutputGuardrailResult {}
    record Refuse(GuardrailRefusal refusal) implements AgentOutputGuardrailResult {}
}

public interface ToolInputGuardrail {
    ToolInputGuardrailResult evaluate(ToolInputGuardrailRequest request);
}

public sealed interface ToolInputGuardrailResult {
    record Allow(ToolArguments arguments, GuardrailDecision metadata)
            implements ToolInputGuardrailResult {}
    record Rewrite(ToolArguments safeArguments, GuardrailDecision metadata)
            implements ToolInputGuardrailResult {}
    record Refuse(SafeToolResult replacement, GuardrailDecision metadata)
            implements ToolInputGuardrailResult {}
}

public interface ToolOutputGuardrail {
    ToolOutputGuardrailResult evaluate(ToolOutputGuardrailRequest request);
}

public sealed interface ToolOutputGuardrailResult {
    record Allow(ToolResult output, GuardrailDecision metadata)
            implements ToolOutputGuardrailResult {}
    record Rewrite(ToolResult safeOutput, GuardrailDecision metadata)
            implements ToolOutputGuardrailResult {}
    record Refuse(SafeToolResult replacement, GuardrailDecision metadata)
            implements ToolOutputGuardrailResult {}
}
```

`AgentInputGuardrailRequest` identifies `TURN` or `MODEL` scope. Turn scope contains the normalized user
request, attachment descriptors, requester/tenant policy context, and only the bounded conversation
context required by policy. Model scope contains the final assembled messages and their provenance.
`AgentOutputGuardrailRequest` identifies `INTERNAL` or `PUBLIC` scope and contains the candidate output,
execution scope, and only the evidence needed to validate it. Baseline secret-handling policies apply
before internal output is observed or passed onward; public scope adds disclosure and presentation
policies before the final response leaves the application.

Tool Guardrail requests contain `ToolId`, normalized arguments, execution scope, effect metadata, and
the output when applicable. `SafeToolResult` is an application-owned model-facing replacement that says
only what policy permits. It never embeds the rejected arguments, raw Tool output, or internal policy
rationale.

`GuardrailRefusal` contains a decision ID, policy version, internal policy code, reviewed public message
key, and retention directive. The public message is selected by the application; classifier text and
internal policy codes are never rendered directly. The retention directive controls whether rejected
input or output may be kept in transcripts, model-facing history, or diagnostics.

Guardrails execute in deterministic order. `REWRITE` feeds the safe value to the next Guardrail;
`REFUSE` is terminal; and all required Guardrails must complete successfully. An empty required chain,
timeout, unavailable required classifier, malformed result, or unknown decision fails closed with a
generic policy-unavailable response unless deployment policy requires startup failure. It must never
silently become `ALLOW`.

A turn-level input refusal is distinct from `AgentRunResult` because no general Agent run occurred. An
output refusal suppresses the candidate result. Both map to a structured `ChatResponse` with a stable
finish reason such as `GUARDRAIL_REFUSAL`. Observations contain decision IDs, policy versions, action
types, and appropriately redacted policy codes, not prohibited content or classifier rationale.

Agent Output `RETRY` is bounded and cannot replay an entire Workflow or any Tool side effect. After a
Tool or mutation has executed, retry/reprompt may only run a response-only Agent with an empty ToolSet
against immutable existing evidence. If safe response-only regeneration is impossible or exhausts its
limit, the chain rewrites or refuses the output.

Agent Output Guardrails run before content is emitted. The safe default for protected responses is to
buffer the complete candidate, evaluate it, and then stream or return only the accepted/rewritten result.
An incremental Guardrail is permitted only when it can prove that sensitive data spanning chunk
boundaries cannot be disclosed; forwarding raw model tokens before validation is forbidden.

A Tool Output Guardrail cannot roll back an executed Tool. Its refusal replaces disclosure of the result
but preserves truthful mutation-completed state, cancellation fencing, and required post-mutation
read-back handling.

A model-assisted evaluator uses a dedicated `GuardrailAgentDefinition` with an empty ToolSet and
structured output. Its `ExecutionScope` uses `GUARDRAIL_EVALUATION`, which bypasses only recursive
application of the Guardrail currently being evaluated; it does not bypass other Guardrails, model
restrictions, logging redaction, context limits, or cancellation. This execution purpose is created only
by trusted application code and cannot be supplied through an HTTP payload, persisted preference,
prompt, or Tool result.

### 3.3 Gateway API

The Gateway result is a sealed type so a direct response cannot exist without an allowed, guarded turn:

```java
public enum DirectIntent {
    GREETING, THANKS, CAPABILITIES_HELP
}

public sealed interface GatewayResult {
    record Direct(
            GuardedTurn turn,
            DirectIntent intent,
            AiAssistantMessage candidate,
            double confidence) implements GatewayResult {}

    record Handoff(
            GuardedTurn turn,
            Optional<WorkflowId> suggestedWorkflow,
            double confidence) implements GatewayResult {}

    record Review(
            GuardedTurn turn,
            GuardrailReviewReason reason) implements GatewayResult {}

    record Refuse(GuardrailRefusal refusal) implements GatewayResult {}
}
```

`GuardedTurn` contains the locally validated/re-written `PreparedChatTurn` plus all completed `TURN`
Guardrail decision IDs. The shared `AiAgentCatalog` resolves the resource-backed Gateway definition, and
the common `AgentFactory` binds its configured lightweight Model and empty ToolSet. Its Spring AI
structured-output schema is versioned and rejects unknown intents, missing policy fields, invalid
confidence, and a `DIRECT` result outside the closed intent set.

The Gateway invocation uses trusted `GATEWAY_ROUTING` purpose. Like `GUARDRAIL_EVALUATION`, this purpose
can be created only by application code and bypasses only recursion into the model-assisted `TURN`
policy fields being evaluated. All other model restrictions, logging redaction, cancellation, and output
validation remain active. The gateway has a small input/token budget and receives no broad history or
Tool declarations.

Gateway routing failure is not an implicit policy allow. If routing fails before a valid policy decision
is available, the request enters the normal path at its remaining `TURN` Guardrail evaluation; that path
still fails closed if a required evaluator is unavailable. If policy has safely allowed the turn but the
route is invalid, the route defaults to `HANDOFF`. Only a valid, sufficiently confident `Direct` may use
the fast response path.

### 3.4 Spring AI integration

There is one production execution implementation behind the required application port. This separation
is a library boundary, not a pluggable execution strategy:

```java
@Component
public final class SpringAiAgentExecutionService implements AgentExecutionService {
    private final SpringAiModelCatalog models;
    private final SpringAiToolAdapter toolAdapter;
    private final SpringAiChatOptionsFactory optionsFactory;

    public AgentRunResult execute(AgentInvocation invocation) {
        // resolve ChatModel, adapt Tools, invoke ChatClient, map the result
    }
}
```

The Spring AI integration owns only these translations:

- `ModelId` to configured Spring AI `ChatModel`;
- model settings to `ChatOptions`;
- canonical messages to Spring AI messages;
- core Tool specifications and calls to `ToolCallback` values; and
- Spring AI responses and usage metadata to `AgentRunResult`.

Streaming is a transport enhancement over the same semantic call, not a second execution path. It emits
only output already accepted or rewritten by the applicable Agent Output Guardrail chain; protected
routes may therefore buffer the candidate before emitting deltas. Usage is optional; callers use
reported usage when present and an estimator or unknown value when absent.

`SpringAiModelCatalog` contains only configured and available model beans. Model selection rules are
deterministic:

1. Use an explicitly requested available Model.
2. Otherwise use the conversation's selected Model if it remains available.
3. Otherwise use the configured default Model.
4. Reject the request when no configured Model is available.

An explicitly unknown Model is an error and must not be silently changed. A stale stored preference may
fall back because model configuration can legitimately change between deployments.

### 3.5 Workflow SPI

Direct execution is not registered as an optional Workflow implementation; it is guaranteed by
`AgentExecutionService`. Advanced Workflows use a simple global registry:

```java
public interface WorkflowExecutor {
    WorkflowId workflowId();
    WorkflowResult execute(WorkflowInvocation invocation);
}

public interface WorkflowExecutorRegistry {
    Optional<WorkflowExecutor> find(WorkflowId workflowId);
    Set<WorkflowId> installed();
}
```

The registry is the source of truth for both validation and planner input; a hard-coded Workflow list is
not. Removing an advanced executor removes only that Workflow.

`WorkflowInvocation` contains Agents, immutable history, execution scope, and an Agent-run function. It
must not expose Spring `ChatClient`, MCP, repository, or HTTP types.

### 3.6 Tool SPI and provider boundary

A Tool provider opens any request-scoped resources and returns core Tools:

```java
public interface ToolProvider {
    ToolProviderId id();
    ToolSession open(ToolResolutionContext context);
}

public interface ToolSession extends AutoCloseable {
    ToolSet tools();
}

public final class SpringAiToolAdapter {
    ToolCallbackProvider adapt(ToolSet tools, ToolExecutionGateway gateway);
}

public interface ToolGuardrailRegistry {
    ToolGuardrailSet resolve(ToolId toolId, ExecutionScope scope);
}
```

`McpToolProvider` owns MCP connection/session details and converts MCP metadata and results to core Tool
contracts. Future `WebSearchToolProvider`, `BashToolProvider`, and local providers use the same boundary.
Agent and Workflow code must not branch on the provider protocol.

`SpringAiToolAdapter` converts core specifications to Spring AI declarations, but every callback
delegates back to `ToolExecutionGateway`; the adapter never invokes provider Tools directly.
`ToolGuardrailRegistry` combines mandatory baseline policies with Tool-specific registrations. A Java
annotation or explicit decorator may contribute registrations, but an undecorated Tool still receives
the mandatory baseline set at the gateway.

### 3.7 Tool execution middleware

The gateway builds one ordered chain around every Tool call:

```text
normalize and validate arguments
  -> enforce Agent/user Tool access policy
  -> run PRE_AUTHORIZATION Tool Input Guardrails
  -> authorize effects (optional mutation confirmation)
  -> run PRE_EXECUTION Tool Input Guardrails on exact approved arguments
  -> verify request/cancellation fence
  -> publish invocation-start observation
  -> invoke provider Tool
  -> capture output within the mandatory raw-size limit
  -> run Tool Output Guardrails
  -> normalize the safe/replacement output
  -> publish completion/failure observation
```

The required chain works with zero optional authorization policies and zero observers. Confirmation is
implemented as a `ToolAuthorizationPolicy` or `ToolExecutionMiddleware`, not as a Spring AI callback
wrapper and not as prompt-only behavior.

Security policies that must prevent forbidden arguments from appearing in an approval prompt or pending
record run in `PRE_AUTHORIZATION`. Every call is checked again in `PRE_EXECUTION`, after approval and any
revision, to prevent time-of-check/time-of-use bypass. If a pre-execution rewrite changes arguments
covered by a mutation grant, the grant is invalidated and the call returns to authorization; changed
arguments never execute under an earlier approval. A Tool Output Guardrail runs after the side effect and
before the result is exposed; refusal suppresses disclosure but never reports that an executed mutation
did not happen.

Python decorators such as OpenAI Agents SDK's `@tool_input_guardrail` and
`@tool_output_guardrail`, or equivalent Java annotations/decorator objects, are optional registration
conveniences. Enforcement remains centralized in `ToolExecutionGateway`, and architecture tests must
prove that no Tool callback bypasses it. Unlike the OpenAI Agents SDK pipeline, which documents Tool
Guardrails only for its custom function tools, this application applies them to every core `AiTool`,
including MCP and future local/provider implementations.

Tool effects must be declared in core metadata. When a provider cannot state an effect reliably, a
deployment policy classifies or rejects it. Mutation read-back and stale-result protection remain
separate mandatory consistency policies, so removing confirmation cannot remove them accidentally.

### 3.8 Conversation ports

Split durable concerns into application-owned ports even if one jOOQ adapter initially implements all of
them:

```java
public interface ConversationStateStore { /* selection and durable settings */ }
public interface ConversationHistoryStore { /* canonical model-facing messages */ }
public interface ConversationTranscriptStore { /* user-visible turns */ }
public interface ConversationMemoryStore { /* compacted durable memory */ }
public interface ProviderConversationHandleStore { /* optional optimization */ }
```

The request fence is established before Gateway routing. The per-turn fast path does not load transcript,
trajectory, Tool state, or broad model-facing history. On `HANDOFF`, `ConversationService` loads one
immutable `ConversationSnapshot` for root Agent/Workflow execution at the same fenced generation.
`ConversationResultCommitter` commits the accepted result under
`AiRequestRegistry.commitResult(...)` or its extracted equivalent. Agent and Workflow execution receive
the snapshot but never the stores.

History replacement after compaction should be one store operation rather than a sequence of clear/add
calls. The existing memory repository's bulk save capability can be adapted to this atomic replacement
contract.

### 3.9 Optional features

Compaction consists of three replaceable parts:

- `CompactionPolicy`: decides whether automatic compaction is needed;
- `CompactionAgentFactory`: binds the selected Model to a no-Tool compaction definition; and
- `ConversationCompactor`: runs the Agent and replaces history/memory.

Manual and automatic compaction call the same service. Hard context-window validation belongs to core
execution safety and remains present if the optional compaction feature is absent.

Mutation confirmation contributes a Tool authorization middleware, grant store, commands/endpoints, and
optional UI. With the feature registration absent, the authorization chain simply has no confirmation
policy.

Trajectory contributes an `ExecutionObserver` and projection repository. Mandatory live execution state
such as retry limits, counters, cancellation generation, and commit eligibility must move to
`ExecutionState`; it cannot remain hidden inside the optional recorder.

UI progress and metrics are separate observer implementations. Failure in an optional observer is
isolated and reported; it cannot fail a successful Agent run or conversation commit.

### 3.10 Chat facade

`ChatService.prepare(...)` and `ChatService.chat(...)` become thin compatibility methods over explicit
use cases. Preparation returns an internal `PreparedChatTurn`, not another mutable transport request.

```text
prepare
  = normalize command + load conversation + apply history policy

chat
  = begin fenced turn + apply local TURN Guardrails + optionally invoke the per-turn Gateway Agent
    + either validate/commit DIRECT output or hand off to root Agent/Workflow execution
    + apply remaining Guardrails + commit + map response
```

Command parsing, Guardrail enforcement, model selection, compaction, Agent construction, execution, and
result commit are delegated to separate collaborators. `ChatService` coordinates them in order and
retains no MCP, `ChatClient`, Tool callback, or feature-specific branches.

## 4. Current code compared with the target

| Current code | Problem exposed by the new definitions | Target change |
|---|---|---|
| `AiAgentDefinition` contains only id, display metadata, and instruction; role and Tool policy are absent from prompt metadata | Completed: definitions no longer constrain runtime autonomy | Keep Tool access request- and Workflow-scoped, with mutations enforced by Guardrails and approval coordination |
| `AiAgentCatalog` registers fixed Agents from `system-prompt-*`, dynamic Workers from `agent-prompt-*`, Workflow templates from `workflow/*`, and execution templates from `execution/*` | Completed: every static inference identity and every reusable orchestration/execution instruction resolves through the common catalog | Keep new inference paths on the catalog-backed `Agent` contract; render templates only within an identified Root, Worker, Synthesizer, or recovery Agent run |
| `ChatRequest.agent` remains in the transport request while responses now use the actually executed Agent identity | The unused client-supplied selection field is ambiguous | Remove the transport field after compatibility migration; keep Agent selection server-authoritative |
| `AiChatExecutor.Context` imports `ChatRequest`, `ScoreUser`, and concrete `AiTrajectoryRecorder` | Model execution is coupled to HTTP/application state and an optional feature | Replace it with `AgentInvocation`, `ExecutionScope`, `ToolExecutionGateway`, and observer-neutral results |
| `AiChatExecutor` resolves models, opens MCP sessions, builds `ChatClient`, wraps Tools, retries, streams, and enforces read-back | Spring AI translation, Tool protocol, policy, state, and recovery are difficult to vary and test independently | Keep Spring AI request construction in the execution boundary; move Tool resolution/policy to the gateway and mandatory state/recovery to dedicated collaborators |
| Safety rules are expressed only in the assistant prompt | The general Agent may begin planning or call Tools before a reliable application policy refuses the request | Add required `TURN` and `MODEL` Agent Input Guardrail chains; keep prompt rules only as defense in depth |
| Simple turns use the same root Model, context, and orchestration as domain requests | Greetings and basic help pay unnecessary latency and load | Add a no-Tool lightweight Gateway Agent that can answer only a closed simple-intent set and otherwise hands off immediately |
| `AiChatExecutor` publishes streamed content deltas while the Model is still producing the candidate response | A post-generation check cannot redact or suppress information that has already reached the client | Buffer protected responses until Agent Output Guardrails accept/rewrite them, or use a proven streaming-safe Guardrail before emission |
| `ConnectCenterMcpClientFactory` exposes Spring AI `ToolCallbackProvider` directly | MCP protocol handling and Spring AI callback representation are fused | Return core `ToolSession`/`ToolSet` values from `McpToolProvider` and convert once in `SpringAiToolAdapter` |
| `AiMutationToolGuard` implements confirmation directly around Spring AI callbacks | Confirmation is tied to one callback representation | Re-express it as core Tool authorization middleware and a separate optional grant store |
| Tool argument checks, mutation guards, output limiting, and sensitive-data redaction are implemented by separate callback wrappers/helpers | A Tool may miss a policy when it is not wrapped in exactly the same way | Resolve Tool Input/Output Guardrails and other mandatory middleware centrally in `ToolExecutionGateway` |
| `AiTrajectoryRecorder` records, emits UI events, counts/retries, wraps Tools, and limits output | Removing recording would remove required execution behavior | Split `ExecutionState`, Tool middleware, event publisher, and optional trajectory observer |
| `WorkflowContext` embeds `AiChatExecutor.Context` | Workflow composition depends on a concrete executor and transport-oriented request state | Pass Agents, immutable history, execution scope, and an Agent-run function through `WorkflowInvocation` |
| `ScoreAiModelRegistry` mixes availability metadata, default selection, Spring AI client construction, and configuration lookup | Model catalog concerns and Spring AI construction are difficult to test separately | Keep one configured Model catalog and extract focused model selection, client factory, and options mapping collaborators |
| Conversation settings/history are reconstructed through broad conversation/trajectory persistence | Optional diagnostics risk becoming authoritative state | Split state, history, transcript, memory, and optional trajectory projections |
| `ChatService.prepare` and `chat` parse commands, resolve policy, compact, execute, persist, and map responses | Control flow and feature ownership are difficult to see and test | Retain a thin facade over preparation, execution, compaction, and commit use cases |

### 4.1 Behavior that must be preserved during extraction

- `AiRequestRegistry.commitResult(...)` is the final stale/cancelled-result fence; all durable success
  writes must remain inside it or an equivalently atomic abstraction.
- Tool access modes such as none/read-only/full must remain request- and task-scoped and continue
  independently of confirmation. Agent prompt metadata must never grant or restrict Tool authority.
- Post-mutation consistency/read-back and Tool output limits must not move into optional feature
  packages.
- The canonical conversation remains reconstructible after restart and after changing the selected
  Model.
- Existing progress events and trajectory payloads may be adapted, but neither may determine whether a
  run is valid.
- Current Spring AI behavior is the compatibility baseline while boundaries are extracted.
- Provider retry must remain fenced after a data-changing Tool call so a transient failure cannot replay
  a completed mutation.
- A turn Agent Input Guardrail refusal must cause zero general Agent, Workflow planner, and Tool invocations.
  Its public response must not expose internal policy rules or classifier rationale.
- Rejected Agent Output Guardrail candidates must not be streamed, committed, or published to general
  observers. Output retry must not replay completed Tool calls or mutations.
- Every Tool call must run Tool Input Guardrails before execution and Tool Output Guardrails before its
  result reaches the Model, UI, transcript, or observers.

## 5. Composition and removal semantics

Optionality is proven by running the application and contract tests without the implementation, not by
checking nullable collaborators throughout the code.

| Removed or unavailable component | Required resulting behavior |
|---|---|
| Compaction feature | No automatic compaction and no compact command registration; all other chat behavior works. Core context-limit validation still produces a clear limit error. |
| Trajectory feature | No trajectory persistence/export. Agent execution, history, Tool calls, progress, counters, retries, cancellation, and commit fencing still work. |
| Mutation confirmation feature | No approval state or approval-required response. Authorized mutation Tools execute through the remaining access, fence, observation, and output middleware. |
| Required Agent or Tool Guardrail evaluator unavailable | The applicable path fails closed with the configured generic refusal, or the application fails startup when policy requires eager availability. It never silently allows input, invokes a prohibited Tool, or discloses unvalidated output. |
| Gateway Model or routing unavailable | The direct fast path is skipped and the request enters the normal flow, including any remaining required `TURN` Guardrails. Required policy evaluation still fails closed if no approved evaluator is available. |
| One configured Model/provider | That Model is absent from the available model catalog. Other Spring AI-backed Models continue to work. An explicit request for the removed Model reports unavailable. |
| MCP Tool provider | MCP Tools are absent from Tool resolution. Agents with local, web-search, Bash, or no Tools continue to work. |
| Advanced Workflow executor | That Workflow is not advertised. Automatic planning selects another installed Workflow or direct; explicit selection reports unsupported. |
| Usage metadata from a model | The result contains unknown/estimated usage. Response generation and history persistence continue. |
| Tool-calling capability for a model | The Agent receives an empty model-visible ToolSet. Direct text generation works; a Tool-required operation reports a capability error. |
| Optional observer | Its projection/event stream is absent. Execution result is unchanged. |

The required Agent/Tool Guardrail chains, Spring AI itself, and at least one configured `ChatModel` are
required infrastructure. Their absence is a startup/configuration failure, not an optional-removal case.

### 5.1 Composition rules

Use collection injection and registries for zero-or-more extensions:

```java
new CompositeExecutionObserver(List<ExecutionObserver> observers);
new ToolExecutionPipeline(List<ToolExecutionMiddleware> middleware);
new ToolProviderRegistry(List<ToolProvider> providers);
new WorkflowExecutorRegistry(List<WorkflowExecutor> executors);
new AgentInputGuardrailChain(List<AgentInputGuardrail> guardrails);
new AgentOutputGuardrailChain(List<AgentOutputGuardrail> guardrails);
new ToolGuardrailRegistry(List<ToolGuardrailRegistration> registrations);
```

Empty observer, Tool middleware, Tool provider, and advanced Workflow collections are valid. Required
Guardrail chains must contain their configured baseline policies; an empty required chain is invalid.
Required core services and the Spring AI integration have normal non-null constructor dependencies and
fail application startup if missing. Avoid
`Optional<FeatureService>` branches inside `ChatService`; optional feature packages contribute commands,
policies, middleware, observers, and registry entries at the composition root.

### 5.2 Package target inside `score-http`

No new Gradle/Maven module or independently bootable AI application is required. All implementation
remains in `score-http`, primarily below:

```text
score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management
```

Package boundaries, not build artifacts, provide separation:

| Package boundary | Responsibility |
|---|---|
| `ai_management.agent` | Agent definitions/runs, `AgentFactory`, Gateway Agent types and closed direct-intent set |
| `ai_management.guardrail` | Agent/Tool Input and Output policies, chains, redaction, classifiers, refusal mapping, retention directives |
| `ai_management.execution` | `AgentExecutionService`, Spring AI implementation, `ChatModel` catalog, options/message mapping, Tool callback adapter |
| `ai_management.tool` | Protocol-neutral Tools, sessions, execution gateway, effects, and middleware; protocol adapters live below `tool.provider.*`, such as `tool.provider.mcp` |
| `ai_management.workflow` | Direct/advanced Workflow contracts, registry, and installed executors |
| `ai_management.conversation` | Snapshot, history/state/memory ports, fenced result commit |
| `ai_management.service` | Chat facade and application use-case orchestration |
| `ai_management.repository` | jOOQ and other persistence adapters |
| `ai_management.controller` | HTTP payload mapping, endpoints, and streaming transport |

These are target responsibilities, not a requirement to move every existing class in one change. The
current `model`, `service`, `provider`, `memory`, `repository`, and `workflow` packages can evolve
incrementally while ArchUnit prevents new boundary violations. Existing Spring composition under
`org.oagi.score.gateway.http.configuration.ai` may remain the application composition root; it contains
wiring only, not an independent execution implementation.

The package dependency direction is `controller/configuration/adapters/features -> application/domain`.
Feature packages do not construct `ChatClient` calls or bypass required Guardrail chains or
`AgentExecutionService`. Provider-specific SDKs remain behind Spring AI configuration and do not enter
Agent, Workflow, Guardrail, or conversation contracts. Physical module extraction can be reconsidered
only if a concrete independent deployment or reuse requirement appears; it is not part of this plan.

## 6. Target execution flows

### 6.1 Chat turn with Gateway fast path

```text
1. ChatFacade receives transport-neutral ChatCommand.
2. ConversationService normalizes the turn and begins the request fence. Full canonical history is
   deferred until a handoff needs it.
3. Mandatory local TURN Agent Input Guardrails evaluate the PreparedChatTurn before any model call.
4. A local REFUSE returns a policy-controlled response and terminates with zero AI/Workflow/Tool work.
5. On every eligible turn, GatewayAgent receives only the safe current turn and bounded routing context.
6. GatewayAgent uses a lightweight configured ChatModel, empty ToolSet, strict structured output, and
   one call to produce any model-assisted TURN policy decision plus DIRECT/HANDOFF/REVIEW routing. Its
   minimal assembled messages pass the applicable MODEL Agent Input Guardrails before that call.
7. A Gateway policy REFUSE returns an application-owned refusal. Missing/invalid policy classification
   falls back to step 10 for the remaining approved TURN evaluation and fails closed if that evaluation
   is unavailable. A route-only failure becomes HANDOFF, never an implicit policy ALLOW.
8. A valid DIRECT for the closed simple-intent set passes the fast Agent Output Guardrail baseline,
   fenced commit, and response mapping. It never creates a root Agent, Workflow, or Tool session.
9. REVIEW runs the configured enhanced Guardrail review. It either refuses or produces a guarded turn
   that must use the normal path; REVIEW never returns a direct answer.
10. For HANDOFF, an ineligible turn, or routing fallback, ConversationService loads one immutable
    ConversationSnapshot and the remaining TURN Guardrails run only if they were not already satisfied
    by a valid Gateway decision.
11. ModelSelector resolves an available configured Model for the root Agent.
12. AgentFactory resolves the root definition and available/user-authorized Tools.
13. WorkflowResolver validates the requested Workflow against installed executors.
14. The Workflow composes Agent runs; every inference calls AgentExecutionService.
15. The MODEL Agent Input Guardrail chain validates the assembled messages for each model call.
16. AgentExecutionService builds and invokes the Spring AI ChatClient request.
17. Spring AI Tool callbacks route through ToolExecutionGateway and both Tool Guardrail directions.
18. AgentExecutionService returns a candidate AgentRunResult.
19. The Agent Output Guardrail chain accepts, rewrites, retries, or refuses the candidate.
20. Only validated output is streamed and passed to ConversationResultCommitter.
21. ConversationResultCommitter checks the request fence and commits canonical state atomically.
22. ChatFacade maps the committed or refused result to ChatResponse.
```

Observers receive safe facts during steps 3–21 but are not on the decision path. Raw rejected content is
available only to the minimum trusted Guardrail components. Provider handles and trajectory projections
may be written after or alongside the canonical commit according to their consistency needs, but neither
can disclose rejected output or make stale results current.

### 6.2 Guardrail processing

```text
normalized PreparedChatTurn
  -> local TURN AgentInputGuardrailChain
       -> REFUSE -> policy-owned response; no model, Workflow, or Tool processing
       -> ALLOW/REWRITE
            -> eligible turn: Gateway Agent
                 -> model-assisted TURN policy REFUSE -> policy-owned response
                 -> DIRECT -> fast output validation and fenced commit
                 -> HANDOFF -> normal Workflow and Agent execution
                 -> REVIEW -> enhanced policy evaluation, then refuse or hand off
            -> ineligible turn: normal Workflow and Agent execution

assembled model messages
  -> MODEL AgentInputGuardrailChain
       -> ALLOW/REWRITE -> Spring AI ChatClient
       -> REFUSE -> block that model call

candidate AgentRunResult
  -> AgentOutputGuardrailChain
       -> ALLOW -> publish validated output
       -> REWRITE -> publish redacted/replaced output
       -> RETRY -> bounded no-Tool response-only regeneration -> revalidate
       -> REFUSE -> suppress candidate and publish policy-owned response

normalized Tool arguments
  -> ToolInputGuardrailChain(PRE_AUTHORIZATION)
  -> authorization/confirmation
  -> ToolInputGuardrailChain(PRE_EXECUTION)
  -> invoke Tool
  -> ToolOutputGuardrailChain
       -> ALLOW/REWRITE -> safe model-facing Tool result
       -> REFUSE -> safe replacement result; preserve actual side-effect state
```

A dedicated model-assisted Guardrail is recorded with `GUARDRAIL_EVALUATION` purpose; a batched Gateway
decision is recorded with `GATEWAY_ROUTING` purpose. Neither can be confused with the general Agent whose
input or output is being protected. Redacted or suppressed candidates never become public stream events
or canonical assistant messages.

### 6.3 Latency and load rules

- A simple eligible turn performs local Guardrails, one lightweight Gateway inference, local/fast output
  validation, and one fenced commit. It creates no root Agent, Workflow, Tool session, RAG request, or
  broad history payload.
- A non-trivial eligible turn pays for one lightweight Gateway inference before immediate handoff. The
  completed `TURN` decisions are reused rather than evaluated again.
- An eligible follow-up also uses the Gateway fast path. Contextual or domain follow-ups immediately
  hand off; history remains deferred until that decision.
- The Gateway instruction, schema, and maximum tokens are deliberately small. `DIRECT` is permitted only when
  the configured output policy can validate that closed intent without another general-purpose model
  call; otherwise the result is `REVIEW` or `HANDOFF`.
- Do not run a required Guardrail concurrently with content disclosure or a side-effecting operation.
  Routing may degrade to the normal path, but policy validation never degrades to implicit allow.
- Measure Gateway latency, `DIRECT/HANDOFF/REVIEW/REFUSE` counts, normal-flow fallback, and fast-path
  history/Tool-session avoidance without recording prohibited request or candidate content.

### 6.4 Compaction

```text
CompactionPolicy/command
  -> load canonical history
  -> build Compactor Agent(selected Model, compact prompt, empty ToolSet)
  -> AgentExecutionService.execute
  -> invoke Spring AI ChatClient
  -> validate compacted memory
  -> fenced atomic history/memory replacement
```

Compaction uses the same execution service as all other inference and does not depend on advanced
Workflow registration.

### 6.5 Mutation Tool call

```text
Spring AI requests Tool(name, arguments)
  -> SpringAiToolAdapter
  -> ToolExecutionGateway
  -> normalize + access policies
  -> Tool Input Guardrails (PRE_AUTHORIZATION)
  -> confirmation policy, only if installed
  -> Tool Input Guardrails (PRE_EXECUTION)
  -> request fence
  -> actual Tool
  -> Tool Output Guardrails
  -> safe output normalization and observations
  -> Spring AI Tool result
```

Approval grants identify the exact conversation, requester, Tool, normalized arguments/effect, and
request generation. They must not authorize a different invocation after retry or cancellation.
Only truly concurrent `parallel` and `orchestrator-workers` branches participate in one batch approval
barrier. Sequential `chain`, single-route `routing`, and other non-concurrent Worker executions use an
individual approval scope and resume only that exact Worker.

### 6.6 Model or Workflow unavailable

An explicitly selected unavailable Model or Workflow returns a structured capability error before
executing inference. A stale stored Model preference may fall back to the configured default, and
automatic Workflow selection may fall back to `direct`. These decisions are exposed in result metadata
and observations rather than hidden.

## 7. Migration plan

Each phase is intentionally small, keeps current Spring AI behavior working, and adds characterization
or contract tests before deleting the old path.

### Phase 0 — Characterize current behavior

- Add tests around `prepare`, `chat`, commands, compaction, confirmation, workflows, cancellation, final
  commit, Guardrail allow/rewrite/refuse outcomes, streaming disclosure, progress events, provider retry,
  and history reconstruction.
- Capture the current Model/provider matrix and Tool access behavior.
- Mark `AiRequestRegistry.commitResult(...)`, mutation replay fencing, and mutation consistency behavior
  as protected invariants.

**Exit:** the current observable behavior and intentional exceptions are test-defined.

### Phase 1 — Introduce first-class Agent

- Add core `AiModel`, `AgentDefinition`, `Agent`, `ToolSet`, `AgentInvocation`, and `AgentRunResult` types.
- Extend definitions so `AgentFactory` binds the selected Model and concrete ToolSet.
- Represent root, Guardrail classifier, planner, evaluator, worker, synthesizer, and compactor inference
  as Agent runs.
- Replace the hard-coded response agent id with the executed root Agent id.

**Exit:** every inference can be traced to one resolved Agent containing Model, Instruction, and ToolSet;
behavior still executes through the existing `AiChatExecutor` Spring AI path.

### Phase 2 — Add Agent Input and Output Guardrails

- Add the Agent direction-specific Guardrail requests, results, chains, refusal metadata, and retention
  directives.
- Split `TURN` evaluation into a mandatory local stage before any model and a model-assisted stage only
  for content that the local stage permits a policy-approved Model to inspect.
- Place the `MODEL` Agent Input Guardrail chain immediately before each Spring AI call.
- Place the Agent Output Guardrail chain before streaming, response mapping, canonical persistence, and
  general observations.
- Add deterministic output redaction plus structured refusal using reviewed public message keys.
- Add bounded response-only retry/reprompt without replaying Tool calls or mutations.
- Add `GUARDRAIL_EVALUATION` scope for optional approved no-Tool Guardrail Agents without recursive
  evaluation.
- Fail closed when a required evaluator is unavailable or returns an invalid decision.
- Add spies proving a refused turn invokes no general Agent, Workflow planner, or Tool and proving raw
  rejected output is never emitted or committed.

**Exit:** sensitive input is refused before prohibited processing, unsafe output is redacted or
suppressed before disclosure, and persistence, observations, and logs honor policy retention rules.

### Phase 3 — Extract Tool core and gateway

- Introduce SDK-independent Tool specifications, provider sessions, gateway, effects, and middleware.
- Add Tool Input/Output Guardrail contracts and registrations resolved by `ToolId`.
- Run security-sensitive Tool Input Guardrails before authorization and re-run exact arguments immediately
  before execution; run Tool Output Guardrails before result disclosure.
- Adapt the existing MCP factory behind `McpToolProvider`.
- Put Spring `ToolCallback` conversion behind `SpringAiToolAdapter`.
- Move access filtering, output limiting, cancellation fencing, and observation around the gateway.
- Port mutation confirmation as optional authorization middleware.

**Exit:** Agent/application code has no MCP or Spring AI Tool types, and Tool policy contract tests pass
with fake providers.

### Phase 4 — Consolidate Spring AI execution and add the Gateway fast path

- Replace `AiChatExecutor.Context` with `AgentInvocation` and observer-neutral results.
- Separate model selection, `ChatClient` construction, option mapping, retry policy, and result mapping
  into focused collaborators behind `AgentExecutionService`.
- Ensure every inference path calls the same service.
- Register a no-Tool Gateway definition and instruction in the shared Agent catalog, and bind it to a
  separately configurable lightweight Model with a strict structured result.
- Invoke the Gateway Agent on every eligible turn; allow `DIRECT` only for the
  closed simple-intent set, otherwise immediately `HANDOFF` or `REVIEW`.
- Reuse completed Gateway `TURN` decisions during handoff and keep broad history out of the Gateway
  request on every turn.
- Delete obsolete SDK-selection properties, APIs, persisted fields, and compatibility branches.

**Exit:** one Agent execution contract covers Gateway, root, worker, planner, evaluator, synthesis, and
compaction; a simple greeting completes through one lightweight inference without Workflow or Tools;
the application contains no alternate model-call path or execution-selector concept.

### Phase 5 — Make Workflow support globally composable

- Add the `WorkflowId` executor registry and installed-capability query.
- Port existing advanced Workflows as normal registrations.
- Give the Workflow planner only installed Workflow descriptors.
- Implement explicit-error and automatic-direct-fallback rules.
- Replace `WorkflowContext`'s dependency on `AiChatExecutor.Context` with `WorkflowInvocation`.

**Exit:** the support set is discovered from registrations; removing one executor removes only that
Workflow.

### Phase 6 — Separate conversation state and commit

- Introduce `ConversationSnapshot` and the state/history/transcript/memory ports.
- Move repository access out of Agent and Workflow execution code.
- Extract `ConversationResultCommitter` while preserving the request-generation fence.
- Make compaction replacement and normal result persistence explicit atomic store operations.

**Exit:** the same conversation continues across Model changes, and stale runs cannot alter canonical
state.

### Phase 7 — Split cross-cutting behavior

- Extract mandatory `ExecutionState` from `AiTrajectoryRecorder`.
- Publish immutable observations from Guardrail, Agent, Workflow, Spring AI, and Tool boundaries.
- Rebuild trajectory, UI progress, metrics, and audit as independent observers.
- Ensure optional observer failures are isolated.

**Exit:** all core tests pass with an empty observer composite and no trajectory repository.

### Phase 8 — Rebuild compaction as an Agent feature

- Implement the no-Tool Compactor Agent and shared manual/automatic service.
- Keep context-window hard limits in core.
- Register compact commands and policies only when the feature is installed.

**Exit:** compaction contract tests pass against each configured Spring AI-backed Model and a fake
`ChatModel`; the no-compaction composition passes normal chat tests.

### Phase 9 — Thin the facade and enforce package boundaries

- Replace mutable request-to-request preparation with `PreparedChatTurn` internally.
- Reduce `ChatService` to orchestration and transport mapping.
- Enforce the `ai_management` package dependencies with ArchUnit and move classes only when needed to
  make a responsibility boundary explicit.
- Publish installed Models and Workflows to API/UI from the same catalogs used by execution.
- Delete compatibility adapters only after old behavior has no callers.

**Exit:** optional feature-registration removal tests pass from clean application contexts, and
architecture tests prevent MCP, persistence, HTTP payload/controller, and provider-specific types from
entering domain or application use cases within `score-http`.

## 8. Verification strategy

### 8.1 Contract tests

- **Agent:** Model, Instruction, and ToolSet are always fully resolved; empty ToolSet is valid.
- **Gateway:** local `TURN` Guardrails precede its inference; only the closed intent set can return
  `DIRECT`; low confidence/unknown intent cannot return direct output; `HANDOFF` reuses completed policy
  decisions; repeated simple follow-ups remain on the fast path; routing failure enters the guarded
  normal path.
- **Agent Input Guardrails:** TURN refusal produces the approved response with zero general Agent,
  Workflow planner, or Tool invocations; MODEL refusal blocks the model call; rewrites feed the next
  Guardrail; failures follow configured fail-closed behavior.
- **Agent Output Guardrails:** redaction/rewrite occurs before streaming or persistence; refused
  candidates are never disclosed; retries are bounded and cannot replay Tools or mutations; untrusted
  input cannot request `GUARDRAIL_EVALUATION` or `GATEWAY_ROUTING` purpose.
- **Spring AI execution:** history input is immutable; option mapping is deterministic; streaming and
  non-streaming produce equivalent accepted results; usage may be absent.
- **Model catalog:** only configured `ChatModel` beans are advertised; explicit unknown selection fails;
  stale stored selection falls back deterministically.
- **Tool:** every Spring AI callback routes through the common gateway; both Tool Guardrail directions,
  middleware ordering, argument rewrites, safe replacement results, and exact confirmation grants are
  deterministic; output refusal never erases truthful mutation-completed state.
- **Workflow:** the supported set is globally registration-driven; explicit unsupported selection fails;
  automatic selection can use direct.
- **Conversation:** canonical history survives Model switching and restart; stale/cancelled commits are
  rejected.
- **Compaction:** output is validated and replacement is fenced; it requires no Tool or advanced
  Workflow.
- **Observer:** zero observers and failing optional observers do not alter the Agent result.

Run the Agent execution suite against fake Spring AI `ChatModel` implementations representing normal,
streaming, no-usage, Tool-calling, and failure responses. Run every Tool provider against the same Tool
gateway suite. Provider-specific integration tests supplement rather than replace those suites.

### 8.2 Composition tests

At minimum, boot and run a direct chat turn for these application contexts:

1. Spring AI with one configured Model and no optional features or Tool providers.
2. Spring AI plus compaction.
3. Spring AI plus confirmation but no trajectory.
4. Spring AI plus trajectory but no confirmation.
5. Spring AI plus MCP and all features.
6. Multiple Spring AI-backed Models/providers, then the same build with one Model/provider removed.
7. Spring AI with each advanced Workflow removed independently.
8. A fake Spring AI `ChatModel` with no usage metadata or Tool calling.
9. Agent Input Guardrail ALLOW, REWRITE, and REFUSE decisions with downstream invocation spies.
10. Agent Output Guardrail ALLOW, REDACT/REWRITE, RETRY, and REFUSE decisions with stream/persistence spies.
11. Tool Input/Output Guardrail ALLOW, REWRITE, and REFUSE decisions before/after approval and execution.
12. A required Guardrail classifier that is unavailable, times out, or returns malformed output.
13. Repeated simple greetings in one conversation use the lightweight Gateway Model with no root Agent, Workflow,
    Tool/RAG session, or broad history load.
14. Gateway `HANDOFF`, `REVIEW`, malformed routing, and unavailable Model cases, proving that completed
    policy decisions are reused and required policy evaluation never becomes implicit allow.

Also test removal of the MCP provider independently. These are the tests that prove composability;
mocks of optional services inside one monolithic context are insufficient.

### 8.3 Architecture tests

Use ArchUnit and build dependencies to enforce:

- core imports no Spring AI, MCP, HTTP, jOOQ, provider-specific SDK, or feature implementation;
- application use cases import ports, not MCP, HTTP, repository adapters, or provider-specific SDKs;
- Spring AI `ChatClient`, message, option, and Tool callback types remain in the
  `ai_management.execution` package and composition configuration;
- every normalized user turn passes mandatory local TURN Agent Input Guardrails before Gateway or any
  other model call, and completes all required TURN policies before Workflow planning, general Agent
  execution, RAG, or Tool resolution;
- every assembled model request passes the MODEL Agent Input Guardrail chain before `ChatClient` invocation;
- every candidate public result passes the Agent Output Guardrail chain before streaming, response mapping,
  canonical persistence, or general observation;
- every Tool callback passes Tool Input Guardrails before execution and Tool Output Guardrails before
  result disclosure, regardless of decorator or annotation presence;
- only a no-Tool Agent with trusted `GUARDRAIL_EVALUATION` or `GATEWAY_ROUTING` purpose may execute as
  part of model-assisted Guardrail classification;
- transport payloads, persisted preferences, prompts, and Tool results cannot set or restore
  `GUARDRAIL_EVALUATION` or `GATEWAY_ROUTING` purpose;
- transport input cannot select the Gateway Model, lower its confidence threshold, expand the direct
  intent set, or force a `DIRECT` result;
- all model inference enters through `AgentExecutionService`;
- Workflow implementations declare only their `WorkflowId` and do not construct model clients;
- optional feature packages depend inward and domain/application packages never depend outward on
  them; and
- only the composition root constructs catalogs, registries, and ordered extension collections.

### 8.4 Regression focus

Preserve and extend the existing `ChatService`, `AiChatExecutor`, model catalog, Agent catalog, Workflow,
trajectory, request registry, memory repository, and jOOQ conversation tests while moving assertions to
the new contracts. Pay special attention to concurrent cancellation, provider retry, approval replay,
Tool output limits, Guardrail fail-closed behavior, output disclosure during streaming, refusal
retention/redaction, post-mutation read-back, and partial persistence failures.

## 9. Architectural decisions and acceptance criteria

### 9.1 Decisions

1. `Agent` is the common identity contract for every AI Agent; `ResolvedAgent` is exactly Model +
   Instruction + ToolSet and is the immutable subject of one inference invocation.
2. Every model invocation, including Gateway/planner/evaluator/compactor calls, executes an Agent.
3. Guardrails are the umbrella policy concept and include Agent Input/Output and Tool Input/Output
   Guardrail chains.
4. Mandatory local TURN Agent Input Guardrails run before any model; completed model-assisted TURN
   decisions run before general Agent, Workflow, RAG, or Tool processing.
5. MODEL Agent Input Guardrails run against final assembled messages before each model call.
6. Agent Output Guardrails run before streaming or persistence and may allow, redact/rewrite, safely
   retry, or suppress a candidate result.
7. Tool Input and Output Guardrails run around every Tool invocation through `ToolExecutionGateway`;
   decorators or annotations are registration conveniences, not enforcement.
8. Guardrail refusal is a terminal application outcome rendered from an application-owned message; it
   is neither an HTTP interceptor exception nor prompt-only behavior.
9. A no-Tool lightweight Gateway Agent handles only a closed simple-intent set on every eligible turn;
   all other input is handed off or reviewed.
10. Gateway `DIRECT` output is still guarded and fenced; routing failure falls back to the guarded normal
    path and never converts an unavailable policy decision into allow.
11. Spring AI is the only model-execution stack; there is no SDK-selection SPI, selector, registry, ID,
   or compatibility matrix.
12. Model/provider variation is expressed through configured Spring AI `ChatModel` beans and model
   metadata.
13. Advanced Workflow support is globally registration-driven by `WorkflowId`; direct is always present.
14. Conversation history is canonical application data, never provider-owned state.
15. Tool is protocol-neutral; MCP is one provider implementation.
16. All Spring AI Tool callbacks return through one core Tool execution gateway.
17. Mutation confirmation is optional Tool authorization middleware.
18. Trajectory is an optional observer/projection; mandatory execution state is separate.
19. Compaction is a no-Tool Agent run through the standard Spring AI path plus an optional trigger policy.
20. The architecture remains one `score-http` build module; separation is enforced through
    `ai_management` package boundaries.
21. Optionality is implemented through registrations and empty composites, not nullable feature
    branches.

### 9.2 Definition of done

The redesign is complete when:

- `ChatService.prepare` and `chat` read as short use-case orchestration without Spring AI, MCP, Tool
  callback, trajectory, or feature-specific implementation logic;
- every inference record identifies the actual Agent, Model, and Workflow;
- every user turn has a mandatory local TURN Guardrail decision before any model call and all required
  TURN decision IDs and policy versions before general AI processing;
- refused turns invoke no general Agent, Workflow planner, RAG, or Tool and expose only reviewed text;
- repeated simple greetings complete through the no-Tool lightweight Gateway Agent without loading
  broad history or creating a root Agent, Workflow, RAG, or Tool session;
- only the configured closed intent set can take `DIRECT`; `HANDOFF`, `REVIEW`, timeout, malformed output,
  and low-confidence behavior satisfy the Guardrail and fallback rules in Section 6;
- every model call passes MODEL Agent Input Guardrails and every public candidate passes Agent Output
  Guardrails;
- every Tool invocation passes Tool Input and Output Guardrails through the common gateway;
- rejected output is never streamed or persisted, and redaction occurs before disclosure;
- Guardrail failures cannot silently allow input or disclose unvalidated output;
- Model and Workflow capability responses exactly match configured or installed implementations;
- no execution-selection concept remains in Java APIs, configuration, persistence, HTTP payloads, or UI
  state;
- conversation history works unchanged when a conversation changes Model;
- removing compaction, confirmation, trajectory, MCP, one configured Model/provider, or an advanced
  Workflow satisfies the removal matrix in Section 5;
- the minimal Spring AI composition with one configured Model remains a valid production configuration;
- all AI chat implementation remains inside `score-http`, with ArchUnit enforcing the
  `ai_management` package boundaries in Section 5.2;
- fake-`ChatModel` and empty-Tool Agent contract tests pass; and
- request cancellation, provider retry fencing, post-mutation read-back, and final commit fencing remain
  correct under concurrency tests.

### 9.3 Recommended first implementation slice

Do not create new physical modules. The smallest high-leverage slice is:

1. Add SDK-independent `Agent`, `AgentDefinition`, `ToolSet`, `AgentInvocation`, and `AgentRunResult`.
2. Add `AgentFactory` and make the current root assistant and no-Tool Gateway two explicit Agent
   definitions.
3. Add mandatory local/model-assisted TURN Guardrails, MODEL Agent Input Guardrails, and Agent Output
   Guardrails around the Gateway and first root Agent path.
4. Refactor `AiChatExecutor` to accept `AgentInvocation` and return `AgentRunResult` while retaining its
   current Spring AI call internally.
5. Add the per-turn Gateway structured result, closed `DIRECT` intent set, and immediate `HANDOFF` to
   the existing root path.
6. Keep existing Tool, history, Workflow, confirmation, and trajectory internals temporarily behind it.
7. Change `ChatService` to apply Guardrails, select the Gateway/direct or root/handoff path, and return
   the actual Agent id, safe rewrite, or structured refusal.
8. Add characterization tests proving greeting latency/load behavior, unchanged allowed behavior,
   refusal side-effect isolation, and that rejected output cannot reach the stream or stores.

This slice establishes the domain center without simultaneously changing persistence, Tool execution,
or Workflow semantics. The Tool gateway is the next slice because it separates MCP, confirmation,
observation, and Spring AI callback conversion.
