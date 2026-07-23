# AI Workflow validation note

The fixed workflow-type implementation documented on 2026-07-20 has been superseded by the
recursive Agent call-flow architecture in [ai-workflow-architecture.md](ai-workflow-architecture.md).
Its `AiWorkflowExecutionCoordinator`, compiler registry, and pattern-specific Workflow classes no
longer exist.

Current verification is source-controlled beside the implementation:

- `WorkflowTest`: Gateway termination, Agent handoff, recursive child Workflow execution,
  synthesis, evaluator-driven replanning and failed-replan retention, global call/deadline bounds,
  control-plane assignment rejection, Tool-authority reduction, accepted-turn propagation,
  usage settlement, recorder lifetime, cancellation, and failure semantics.
- `PlannerAgentTest` and `AssignedAgentTest`: recursive plan validation, fallback/cancellation,
  durable child binding, Tool-authority reduction, and Workflow observation context.
- `ChatServiceTest`: service integration and durable Workflow preference behavior.
- `AiModularArchitectureTest`: provider/transport dependency boundaries.
- `ScoreAiObservabilityTest`: Agent, model, tool, and Workflow span behavior, including refusal
  outcomes and rejection of diagnostic events as phantom Workflow spans.

Historical live-matrix numbers from the removed execution model must not be treated as validation
of the current design. A new live matrix should be recorded before production rollout.
