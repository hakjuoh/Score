# AI Workflow architecture

The assistant uses one recursive call-flow model. A Workflow owns a queue whose members are either
an Agent call or another Workflow. There are no `direct`, `chain`, `parallel`, `routing`, or
`orchestrator-workers` execution classes and no node compiler registry.

## Main call flow

```text
Main Workflow queue
  Gateway Agent
    COMPLETE -> return the bounded response
    HANDOFF  -> Assistant Agent
                  COMPLETE -> return the response
                  HANDOFF  -> Planner Agent
                                DELEGATE -> child Workflow queue
                                              Agent or child Workflow ...
                                              Synthesizer Agent
                                Evaluator Agent
                                  COMPLETE -> return
                                  HANDOFF  -> Planner Agent (bounded feedback)
```

Every `WorkflowAgent` returns one declarative `AgentDecision`:

- `Complete`: the Agent finished its assigned unit.
- `Handoff`: append another independent Agent to the current queue.
- `Delegate`: append a recursively executable child Workflow.

One request-global budget is shared by the main queue, every child Workflow, handoff, Planner,
Evaluator, worker, and Synthesizer call. It is bounded to 128 charged operations, child Workflow
depth to 8, Workflow members to 32, Agent calls in each plan to the request's configured maximum,
and plan/evaluate iterations to
`score.ai.multi-agent.maximum-workflow-iterations` (default 3). The request stop fence is checked
before each charged operation. `score.ai.multi-agent.specialist-timeout` is one absolute deadline
for the complete recursive run; blocked Agent calls are interrupted when it expires.

## Recursive plan contract

`AiWorkflowPlan` contains a root `WorkflowDefinition`. Each ordered member contains exactly one
`AgentTask` or one child `WorkflowDefinition`. A member never contains an execution-pattern name.
Member order expresses data dependency: successful earlier results enter later Agent calls as
untrusted reference evidence.

The Planner Agent may create semantic sub-workflows to group a coherent subproblem. The same
Workflow engine runs every level. A one-member Workflow returns that member's result directly;
otherwise the Synthesizer Agent combines the queue into one result for its parent. At the main
level, the Evaluator Agent either accepts the candidate or hands bounded feedback and one next
objective back to the Planner Agent.

## Independent Agents

Gateway, Assistant, Planner, Evaluator, Synthesizer, and request-scoped assigned workers implement
the common `WorkflowAgent` contract in the `agent` package. Workflow scheduling is not embedded in
their implementations. Agent definitions remain catalog data; `AssignedAgent` binds a definition to
one planner assignment and to request-bounded Tool authority (`NONE`, `READ_ONLY`, or `FULL`).
Gateway, Assistant, Planner, Evaluator, and Synthesizer are control-plane addresses and cannot be
selected as model-authored workers. A custom Agent must explicitly opt into assignment.

The Workflow converts the guardrail-accepted user turn once into a protocol-neutral request snapshot. Routing,
planning, evaluation, and synthesis use that snapshot instead of depending on controller payloads.
Model calls enter through the Agent execution port. The assigned-worker adapter retains the existing
tool, mutation-approval, guardrail, trajectory, and requester-scoped MCP mechanics.

## Safety and failure semantics

- Model-authored Agent IDs must resolve before execution to an explicitly assignable installed
  Agent or a worker-only catalog definition.
- A member can carry exactly one Agent or child Workflow; malformed plans use a bounded fallback.
- Agent instructions, prior outputs, original user messages, and Tool results re-enter later calls as
  untrusted data, never as system authority.
- Tool authority belongs to an assignment, not an Agent definition. Parent request policy can only
  reduce it, and the engine applies that reduction before any assigned Agent is invoked.
- A failed member is retained in the result tree. Remaining queued members continue; a Workflow
  fails only when all members fail. A partially successful response always receives an engine-authored
  warning that successful mutations may already have taken effect; disclosure never depends on the
  Synthesizer Agent following its prompt. Descendant failures are counted and propagated through every
  parent Workflow so an outer Synthesizer cannot erase the warning or its metadata.
- Evaluator feedback cannot change Tool authority, Agent limits, depth, queue, or iteration bounds.
- If a later evaluator-requested iteration fails, the most recent successful candidate is retained
  with explicit `evaluation_status=iteration_failed` evidence.
- Child model usage is settled exactly once onto the still-open root trajectory on success,
  failure, refusal, timeout, or cancellation. Workflow terminal events seal only forked recorders,
  so response output guardrails and visible content delivery remain recordable.

## Observability

Each main or child Workflow emits `workflow_started` and one terminal
`workflow_completed|failed|cancelled|refused` lifecycle event. OpenTelemetry maps these to
`invoke_workflow <workflow-id>` spans. A child span uses `parent_node_id` to nest under its parent
Workflow span; Agent/model/tool spans use the active Workflow node when available.

Span attributes describe actual runtime identity rather than a preselected pattern:

- `gen_ai.workflow.name`: model-authored or main Workflow ID
- `score.ai.workflow.run_id`: request/iteration/node execution ID
- `score.ai.workflow.kind`: `workflow`
- `score.ai.workflow.partial_failure`: whether some members failed
- `member_count`, `completed`, and `failed`: bounded direct structural outcomes
- `failure_count`: recursively aggregated descendant failure count

This makes the trace tree match the recursive call flow directly.
