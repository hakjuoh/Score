# AI workflow architecture

The assistant separates deterministic workflow composition from the bounded agent loop that
decides whether more work is required.

## Execution model

```text
request
  -> planner -> recursive workflow specification
  -> compiler -> Workflow object graph
  -> workflow.process(context)
  -> evaluator -> COMPLETE
               -> CONTINUE -> planner (with bounded feedback and prior result)
```

`Workflow` is the common synchronous interface. Every node receives an immutable
`WorkflowContext` and returns a `WorkflowResult`, so nodes can be nested without depending on the
chat controller or a particular model provider.

- `DirectWorkflow`: one application-defined model or worker operation.
- `ChainWorkflow`: ordered children; the previous result becomes the next child's upstream input.
- `ParallelizationWorkflow`: independent children on virtual threads, one shared deadline, partial
  failure support, and an explicit aggregator.
- `RoutingWorkflow`: selects exactly one named child.
- `OrchestratorWorkersWorkflow`: obtains workers for the current execution, runs them concurrently,
  and synthesizes their outputs.
- `EvaluatorOptimizerWorkflow`: wraps planning and execution in a bounded evaluate/replan loop.

The model-authored `AiWorkflowNode` is data, not executable code. `AiWorkflowPlanner` normalizes and
validates the recursive tree before `AiWorkflowCompiler` creates the object graph. Validation bounds
depth, total nodes, registered workers, worker count, route selection, and the minimum branch count
for parallel workflows. Plain (non-worker) branches of parallel and orchestrator containers count
against the same worker limit, because each one consumes a concurrent model execution.

Concurrency is a property of the execution context, not of a node: parallelization and
orchestrator-workers mark the `WorkflowContext` they hand to branches as concurrent. A direct leaf
that executes concurrently is not the exclusive lead, so it is forced to read-only tool policy and
must pass the same admission semaphores as a registered worker. The same applies to the synthesis
call of a container nested inside a concurrent branch: it runs while outer siblings are still
executing, so it is read-only, admission-controlled, and recorded at branch depth. Only the
sequential lead path (chain steps, routing selections, root aggregation) may perform mutations.

`AiWorkflowCompiler` is a registry rather than a type switch. Its five built-in node compilers use a
restricted `CompilationContext` for recursive children, routes, execution resources, leaf calls, and
aggregation. A new node implementation can therefore be registered through `WorkflowNodeCompiler`
without changing the compiler or the existing workflow classes. Planner grammar and validation must
still opt into a new model-authored type deliberately; compiler extensibility does not weaken the
untrusted-plan boundary.

## Composition semantics

A chain may contain any other workflow type. For example:

```text
Chain
  Routing
    evidence -> Direct(evidence-researcher)
    review   -> Direct(critical-reviewer)
  Parallelization
    Direct(scope A)
    Direct(scope B)
  OrchestratorWorkers
    Direct(dynamic worker 1)
    Direct(dynamic worker 2)
```

Composition follows data dependencies rather than a fixed list of pattern names. Routing executes
one route, parallelization executes all branches, and orchestrator-workers owns lead synthesis.
Chain outputs are marked as untrusted reference data before entering the next model call.

## Evaluate and replan

After one compiled graph finishes, `AiWorkflowEvaluator` returns a structured decision:

```json
{
  "decision": "COMPLETE|CONTINUE",
  "feedback": "observable gap or null",
  "nextObjective": "one concrete remaining objective or null"
}
```

`CONTINUE` is allowed only when another bounded workflow can make progress without new user input.
The prior output, evaluator feedback, and next objective are supplied to the next planner iteration,
and the user sees a visible "continuing with the remaining objective" narration for every iteration
that actually continues (a `CONTINUE` verdict on the final iteration runs nothing further, so it is
not narrated). The evaluator judges against structured execution evidence — request-wide counts of
successfully executed domain tool calls (shared across all forked worker and lead recorders) and of
data changes blocked awaiting approval, plus the executed graph's trace metadata — not only the
narrated result text. Intermediate answers are not streamed as final answers. The loop is capped by
`score.ai.multi-agent.maximum-workflow-iterations` (default `3`) and observes the existing request
cancellation fence on every iteration. When a later iteration fails after an earlier one succeeded,
the most recent successful attempt is returned with `evaluation_status=iteration_failed` instead of
discarding completed work.

## Model-directed deferred tool search

MCP callback schemas are not placed in the model context up front. The application keeps the full
callback registry server-side and initially gives each executing workflow model only:

- `toolSearchTool`'s schema; and
- an alphabetized, names-only `<available-deferred-tools>` catalog.

For an exact selection, the model calls `toolSearchTool` with a query such as
`select:create_context_scheme,get_context_scheme`. `ScoreToolIndex` resolves those names without
interpreting model text as a regular expression. Natural-language retrieval remains as a safe,
entity-weighted fallback. Search results accumulate, and only the selected tools' complete schemas
are exposed on the next model step. The limit is ten results per search, so a workflow can issue
multiple searches when it genuinely needs a broader capability set.

This is an application-level deferred mechanism rather than a provider-specific feature, so the same
workflow contract works with Anthropic and OpenAI runtimes. Every direct leaf, delegated worker, and
lead synthesis call passes through the same advisor; the model executing that workflow temporarily
acts as its tool-search agent before using the selected tools.

The design combines the useful parts of the frontier clients inspected during implementation:

| Implementation | Initial exposure | Selection | Score adaptation |
|---|---|---|---|
| Claude Code | Deferred tool names; full schema after `ToolSearchTool`, including exact `select:` | Model-directed | Same exact-selection protocol and names-only catalog |
| Codex | MCP tools deferred behind search; ranked search returns loadable specifications | Search/ranking | Server-side private registry and natural-language ranked fallback |
| OpenAI tool search | Deferred functions/namespaces; relevant tools added to context | Provider-managed model search | Equivalent behavior across both configured providers |

This also follows the official recommendation to keep the initial tool set small because function
definitions consume input context. See [OpenAI tool search](https://developers.openai.com/api/docs/guides/function-calling#tool-search),
[Anthropic's workflow patterns](https://www.anthropic.com/engineering/building-effective-agents), and
[Spring AI effective agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html).

## Approval and failure surfacing

- The mutation guard intercepts unapproved data-changing calls; intercepted calls are recorded and
  displayed as `blocked` ("awaiting approval"), never as completed. A call intercepted because the
  user is stopping the request is recorded as `cancelled` ("stopped before execution") — nothing is
  pending and no approval control will appear.
- Approval is granted only through the structured approval controls; the assistant prompt forbids
  "reply to approve" phrasing and instructs one data-changing call per turn. When a model still
  raises several confirmations in one request, the client keeps the first notice (each notice is
  bound server-side to one exact tool + arguments digest) and later turns regenerate the rest.
- Terminal failures map known classes (MCP transport/authorization, provider client errors,
  deadline) to actionable user messages, note that already-completed data changes remain applied,
  and persist the failure class in the error step for diagnosis.
- Transient model-provider failures are retried by one application-level loop around every model
  call (`AiProviderRetryExecutor`; `score.ai.provider-retry.*`, default 10 attempts, exponential
  backoff from 2s capped at 60s, honoring `Retry-After`/`Retry-After-Ms`). Each provider expresses
  errors differently, so `AiProviderFailureClassifier` maps the Anthropic and OpenAI Java SDK
  exception hierarchies, Spring retry markers, HTTP-client exceptions, and network failures to one
  shape carrying the provider's own human-readable message. Retryable = 408/409/429/5xx (including
  Anthropic's 529 overload), an explicit `X-Should-Retry: true`, and connection drops; request,
  authentication, and entitlement errors fail immediately. SDK-internal retries are disabled
  (`maxRetries(0)`) so this loop is the single narrator: every wait emits a live `provider_retry`
  event (attempt, max attempts, delay, provider reason, status code) that the chat panel renders
  as a reconnecting countdown, and the wait aborts when the user stops the request.
- An attempt that executed a non-read-only tool is never replayed even when the failure is
  transient — re-running the model could repeat the data change; the provider's message surfaces
  instead. When retries are exhausted the persisted error row shows the provider's original
  message ("… (failed after N attempts)"), not a generic notice.

## Efficiency profile

- Tool-less model calls (planner, evaluator, no-tool leaves) skip the MCP session handshake
  entirely.
- Internal calls that carry their own leading system prompt (planner, evaluator, workers) do not
  re-send the assistant persona prompt or the volatile page context.
- When the planner's tools-needed classification proves wrong, the direct path degrades to the
  model's answer with `required_tool_unfulfilled` metadata after one recovery attempt instead of
  failing the request.

## Safety invariants

- Model output is normalized before execution; unknown workflow types and malformed graphs fail to
  the existing safe fallback.
- Full deferred-tool callbacks never come from the model: exact names are resolved only against the
  requester-scoped server registry, and session indexes are fingerprinted, isolated, and evicted.
- Worker leaves can use only registered agents and are forced to read-only tool policy; concurrent
  plain branches are likewise read-only and admission-controlled.
- The sequential lead remains the only component allowed to perform mutations and read-back.
- Worker and chain outputs re-enter model calls as untrusted user-role reference data, never with
  system-role authority.
- Existing mutation confirmation, MCP guard, context budget, trajectory, timeout, and usage controls
  remain below the workflow layer.
- Evaluator feedback cannot expand permissions, worker limits, or iteration limits.
- The original flat `AiWorkflowPlan` constructor and execution path remain supported for stored
  clients and focused tests while recursive plans use the new compiler.
- Workflow type names live in `WorkflowTypes`; planner grammar, validation, compiler registry, and
  dispatch all reference the same constants, and `WorkflowNodeCompiler` extensions are collected
  from the Spring context in production.
