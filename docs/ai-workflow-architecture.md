# AI Workflow architecture

The assistant uses one recursive call-flow model. An `AiWorkflowPlan.WorkflowDefinition` is the
Workflow graph: Agent or child-Workflow members are vertices and explicit directed edges are data
dependencies. A `WorkflowRunner` schedules that graph; the Workflow definition itself does not
execute an Agent. Ready vertices fan out on virtual threads, and each dependency layer joins in
member declaration order. The graph remains the execution model; `DIRECT`, `SEQUENTIAL`, and
`PARALLEL` are server-derived presentation types, not planner-authored execution classes.

Every `workflow_started` event carries `workflow_type`. The implicit depth-zero main flow is
`direct` and does not open a Workflow box. A planned graph is `sequential` when every scheduler
layer has one ready member, and `parallel` when any layer has multiple ready members. The UI renders
sequential items with checkbox characters and one active spinner, and parallel items with a spinner
on every concurrently active item. The type name itself is not displayed. An unknown future type is
shown as an ordinary chat message. Nested Workflow is not a type: child graphs are represented by
the dependency graph and opened recursively from their parent item.

## Main call flow

```text
Main Agent call chain
  Gateway Agent
    COMPLETE -> return the bounded response
    HANDOFF  -> Assistant Agent
                  COMPLETE -> return the response
                  HANDOFF  -> Planner Agent
                                DELEGATE -> child Workflow DAG
                                              ready Agent/Workflow vertices
                                              dependency joins
                                              Synthesizer Agent
                                Evaluator Agent
                                  COMPLETE -> return
                                  HANDOFF  -> Planner Agent (bounded feedback)
```

The single shared `AgentRunner` owns the addressable Agent definitions and returns one
declarative `AgentDecision` for the definition selected by the Workflow:

- `Complete`: the Agent finished its assigned unit.
- `Handoff`: select the next independent Agent in the main call chain.
- `Delegate`: schedule a recursively executable child Workflow graph.

One request-global call budget is shared by the main call chain, every child Workflow, handoff,
Planner, Evaluator, worker, and Synthesizer call. It is bounded to 128 charged operations, child Workflow
depth to 8, Workflow members to 32, Agent calls in each plan to the request's configured maximum,
and plan/evaluate iterations to
`score.ai.multi-agent.maximum-workflow-iterations` (default 3). The request stop fence is checked
before each charged operation. Every Agent invocation has an independent inactivity lease configured
by `score.ai.multi-agent.specialist-inactivity-timeout` (default 2 minutes). Request preparation,
model completion, Tool admission/completion, response handling, policy checks, and retries renew the
lease. Polling does not. A child member that makes no observable progress is interrupted and recorded
as a failed member while active siblings and partial-result synthesis continue. A stalled root or
control-plane Agent terminates the request. The deprecated
`SCORE_AI_MULTI_AGENT_SPECIALIST_TIMEOUT` environment variable remains a fallback for deployments
that have not moved to `SCORE_AI_MULTI_AGENT_SPECIALIST_INACTIVITY_TIMEOUT`.
The request registry has its own rolling inactivity lease configured by
`score.ai.request-inactivity-timeout` (default 10 minutes). It reviews live requests periodically,
publishes a renewed deadline when work has progressed, and times out only after a complete lease
window with no observable progress. Agent progress, trajectory events, Tool admission/completion,
and change execution renew the request lease; review polling does not. An in-flight change is
treated as active work and cannot be interrupted by the request lease. Provider SDK call deadlines
are disabled because a fixed wall-clock limit would terminate a healthy stream; raw provider chunks
remain subject to the Agent inactivity lease above.

User interaction waits are independent: `score.ai.elicitation-timeout` and
`score.ai.change-approval-timeout` default to 10 minutes, while
`score.ai.mcp.request-timeout` controls one MCP operation. Because the MCP SDK uses one session-wide
request timeout for both Tool calls and elicitation round trips, the effective MCP client timeout is
the greater of `mcp.request-timeout` and `elicitation-timeout`; the elicitation service still enforces
its own interaction deadline. Requester-scoped broker JWTs are refreshed on every MCP HTTP request,
so a rolling Agent session does not retain an expired token. The deprecated
`SCORE_AI_REQUEST_TIMEOUT` value remains a fallback for the three new request/interaction settings
until deployments migrate to `SCORE_AI_REQUEST_INACTIVITY_TIMEOUT`, `SCORE_AI_ELICITATION_TIMEOUT`,
and `SCORE_AI_CHANGE_APPROVAL_TIMEOUT`.

## Recursive plan contract

`AiWorkflowPlan` contains a root `WorkflowDefinition`. Each member contains exactly one `AgentTask`
or one child `WorkflowDefinition`; edges reference member IDs and form an acyclic dependency graph.
A member never contains an execution-pattern name. Root vertices receive inherited evidence,
dependent vertices receive successful direct-predecessor results as untrusted reference evidence,
and independent ready vertices execute concurrently. Omitting the edge list creates a sequential
member-order graph; an explicit empty edge list creates independent roots. The Planner never emits
`workflow_type`; the Runner derives it from the validated graph at execution time.

The Planner Agent may create semantic sub-workflows to group a coherent subproblem. The same
Workflow engine runs every level. A one-member Workflow returns that member's result directly;
otherwise the Synthesizer Agent combines the declaration-ordered joined results for its parent. At the main
level, the Evaluator Agent either accepts the candidate or hands bounded feedback and one next
objective back to the Planner Agent.

## Independent Agents

Gateway, Assistant, Planner, Evaluator, Synthesizer, and request-scoped assigned workers are
definition-only `Agent` implementations. Their `AgentDefinition` owns the instruction, request
preparation, Tool policy, response interpretation, and Agent-specific guardrails. None of those
classes executes a model or implements `AgentRunner`.

The one shared `AgentRunner` indexes all `Agent` beans, resolves the selected definition, invokes
its request and Tool handlers, binds the request-scoped model/provider/history/memory/Tool session,
lets the response handler form the declarative decision, and applies output guardrails to that final
content. `AssignedAgent` is a request-scoped
definition binding for one planner assignment and request-bounded Tool authority (`NONE`,
`READ_ONLY`, or `FULL`). The lower-level `AgentExecutionService` remains the single provider/model-and-Chat
port used by the runner; it is not an Agent implementation or a second per-Agent runner.

`AgentExecutionService` keeps model invocation as its required functional operation and provides an
unsupported-by-default Chat hook for model-only integrations. The production adapter explicitly
implements both execution modes. The application-facing Chat seam is
`AgentExecutionContext`; `ChatExecutionContext` stores canonical messages and policies without a
provider delegate, and `SpringAiExecutionContextMapper` creates Spring AI state only inside the execution adapter. Application
services therefore depend on `AgentRunner` and the narrow `AgentIdentityProvider`, never on
`AiChatExecutor`.

Standalone model-assisted features such as compaction and definition/name generation also submit
their already prepared Agent invocation to this same runner. A feature service may own the use-case
method, but it does not call `AgentExecutionService` or `AiChatExecutor` directly.

### Migration from the self-executing API

This boundary intentionally replaces the former internal Java contracts that encoded the invalid
ownership model. `WorkflowAgent` becomes a definition-only `Agent`; `ResolvedAgent` becomes an
`AgentSession` that deliberately does not implement `Agent`; and direct `GatewayAgent.route(...)`
calls become `AgentRunner.run(gatewayAgent, context)`. `AgentFactory` now binds an `Agent` to an
`AgentSession`. These types are application-internal architecture seams rather than a versioned client
library. The model-only `AgentExecutionService` SAM remains lambda-compatible, while its optional Chat
operation is unsupported by default.

Gateway, Assistant, Planner, Evaluator, and Synthesizer are control-plane addresses and cannot be
selected as model-authored workers. A custom Agent must explicitly opt into assignment.

The Workflow converts the guardrail-accepted user turn once into a protocol-neutral request snapshot. Routing,
planning, evaluation, and synthesis use that snapshot instead of depending on controller payloads.
Model calls enter through the Agent execution port. The assigned-worker adapter retains the existing
tool, change-approval, guardrail, trajectory, and requester-scoped MCP mechanics.

## Safety and failure semantics

- Model-authored Agent IDs must resolve before execution to an explicitly assignable installed
  Agent or a worker-only catalog definition.
- A member can carry exactly one Agent or child Workflow; malformed plans use a bounded fallback.
- Agent instructions, prior outputs, original user messages, and Tool results re-enter later calls as
  untrusted data, never as system authority.
- Tool visibility and execution authority are returned together by the Agent definition's
  `AgentToolHandler` as an `AgentToolBinding`. The binding explicitly distinguishes no Tools,
  definition-owned Tools, and inheritance of the already-authorized transport session; it does not
  use a null sentinel. A Planner assignment can only reduce the parent request's policy before the
  assigned definition is invoked; the shared Runner passes the paired ToolSet and
  `ToolExecutionGateway` to the provider boundary.
- A failed member is retained in the result tree. Other schedulable members continue; a Workflow
  fails only when all members fail. A partially successful response always receives an engine-authored
  warning that successful changes may already have taken effect; disclosure never depends on the
  Synthesizer Agent following its prompt. Descendant failures are counted and propagated through every
  parent Workflow so an outer Synthesizer cannot erase the warning or its metadata.
- Evaluator feedback cannot change Tool authority, Agent limits, depth, graph, or iteration bounds.
- If a later evaluator-requested iteration fails, the most recent successful candidate is retained
  with explicit `evaluation_status=iteration_failed` evidence.
- Every root or assigned Chat registers one cumulative usage source with the Runner and reuses its
  recorder across output-policy attempts. The source reconciles per-attempt deltas with trajectory
  usage without adding both; shared root recorders use invocation-start baselines so sequential
  Agents cannot double-count cumulative totals. Each source then settles exactly once on success, failure, refusal, timeout, or
  cancellation. An admitted provider call may settle after terminal disclosure closes; the root
  recorder accepts that accounting-only write without reopening content callbacks.
- Timeout and cancellation close the root disclosure fence immediately. A child recorder for an
  already-admitted provider call retains a bounded accounting-only grace window. Completion settles
  it immediately; a provider that never returns is forcibly snapshotted and fenced after one second.
- A Tool-capable turn is never replayed after an output-policy retry. The Runner emits an explicit
  handoff carrying the private candidate and safe feedback; `ChatService` invokes the no-Tool
  `ResponseOnlyAgent` through the same Runner.
- Runner metadata records `agent_output_guardrail_applied` and its `PUBLIC` or `INTERNAL` scope for
  diagnostics only. Trust uses opaque evidence held by the exact final `AgentOutput`; callers cannot
  manufacture it by copying metadata. The shared public disclosure gate skips reevaluation only for
  valid `PUBLIC` evidence and evaluates missing or internal-only evidence exactly once. Manual
  compaction declares `PUBLIC` scope, while automatic/model-switch compaction declares `INTERNAL` scope.
- Definition-owned Tool side effects check the request-global run control inside `callWhileActive`,
  which shares the recorder's terminal-seal lock. Deadline/cancellation either wins at admission or
  an already-admitted side effect is allowed to finish; there is no check-then-act gap at the Tool
  boundary.
- Transport-inherited Tool callbacks enter through `AiRequestRegistry.admitToolExecution`; admission
  and cancellation have one linear order, while an already-admitted side effect is allowed to finish.

## Middleware lifecycle

The shared Runner and Tool gateway expose a provider-neutral middleware lifecycle around the existing
typed Agent, model, and Tool contracts. Middleware is implemented as a Java bean with a stable ID;
configuration may select only those registered IDs and never a Java class name or expression.

The lifecycle order is:

1. `beforeAgent` in profile order.
2. `beforeModel` in profile order.
3. `wrapModelCall` as nested wrappers, followed by `afterModel` in reverse order.
4. `wrapToolCall` immediately around the authorized Tool invocation. Every returned or replacement
   value still passes the mandatory Tool output Guardrails.
5. `afterAgent` in reverse order after the definition response handler and output policy complete.

`score.ai.middleware.profiles` selects ordered middleware IDs. `profile-by-purpose` may select a
different profile for a trusted `ExecutionScope.Purpose`; policy conditions accept typed purposes and
Tool effects only. Policy-specific values are a string map delivered to the registered bean at startup.
Required registrations are automatically inserted into every profile and must use `ENFORCE` mode.
They cannot declare `purposes` or `tool-effects`; startup rejects either condition because it could
remove a required policy from part of the execution graph.
`SHADOW` hooks may observe execution, but the engine ignores their rewrites, replacements, failures,
and short circuits. Each shadow registration receives one isolated state reused across its lifecycle,
so it can correlate observations without its state writes influencing an enforced policy or the
actual execution path.

Each wrapper receives a synchronous, same-thread, single-use continuation that is revoked when the
wrapper returns. Calling it more than once, from another thread, or after return fails before another
provider or Tool invocation. Tool wrappers run after authorization and therefore cannot rewrite Tool
arguments; argument normalization and rewriting remain in the pre-authorization input Guardrails.
This preserves the change replay and authorization invariants while still allowing a wrapper to
short-circuit with a safe result. Existing Agent and Tool Guardrail chains remain mandatory execution
boundaries and can be migrated behind registered adapters incrementally.

```yaml
score:
  ai:
    middleware:
      profiles:
        default: [secret-redactor, change-approval, output-safety]
        compactor: [secret-redactor]
      profile-by-purpose:
        COMPACTION: compactor
      policies:
        change-approval:
          mode: ENFORCE
          purposes: [USER_RESPONSE]
          tool-effects: [CHANGE]
        output-safety:
          mode: SHADOW
          settings:
            model-name: claude-haiku-4_5
```

## Observability

A turn's entrypoint span is `invoke_workflow <execution-kind>` (`assistant`,
`context_compaction`, `name_generation`). The entrypoint groups the Agent invocations a turn makes
rather than being one of them, which is the case the GenAI semantic conventions reserve
`invoke_workflow` for; its `gen_ai.workflow.name` is the low-cardinality execution kind, not an
Agent name.

Each main or child Workflow emits `workflow_started` and one terminal
`workflow_completed|failed|cancelled|refused` lifecycle event. OpenTelemetry maps these to
`invoke_workflow <workflow-id>` spans, except for the implicit depth-zero queue every turn runs:
that queue *is* the turn, so the entrypoint span already reports it and emitting a second span
would duplicate the entrypoint. Until a Workflow is actually planned, Agent invocations therefore
line up as siblings under the entrypoint instead of nesting inside an `invoke_workflow main`
wrapper, and no extra Workflow metric series is recorded for them. A planned child span uses
`parent_node_id` to nest under its parent Workflow span, falling back to the turn when that parent
is the implicit queue; Agent/model/tool spans use the active Workflow node when available.

Planned Workflows always run inside the entrypoint, so they carry `gen_ai.workflow.nested=true` on
both the span and the `gen_ai.workflow.duration` measurement, while the entrypoint omits the
attribute. Dashboards separate turn latency from planned-Workflow latency on that attribute alone.

Span attributes describe actual runtime identity rather than a preselected pattern:

- `gen_ai.workflow.name`: model-authored or main Workflow ID
- `gen_ai.workflow.nested`: `true` on planned Workflows; absent on the turn entrypoint
- `score.ai.workflow.run_id`: request/iteration/node execution ID
- `score.ai.workflow.type`: bounded UI presentation semantics from event `workflow_type`
  (`direct`, `sequential`, or `parallel`)
- `score.ai.workflow.kind`: `workflow`
- `score.ai.workflow.partial_failure`: whether some members failed
- `member_count`, `completed`, and `failed`: bounded direct structural outcomes
- `failure_count`: recursively aggregated descendant failure count

This makes the trace tree match the recursive call flow directly.
