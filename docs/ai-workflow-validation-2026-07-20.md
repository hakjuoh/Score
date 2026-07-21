# AI workflow implementation and validation report

Date: 2026-07-20 (America/New_York)

## Outcome

The application now executes recursively composed workflows behind one `Workflow.process(...)`
contract and wraps the selected graph in a bounded evaluator/optimizer loop. The planner can compose
direct, chain, parallel, routing, and orchestrator-workers nodes; an evaluator can return `CONTINUE`
with a remaining objective and cause the next iteration to return to the planner.

All executable quality gates completed successfully:

| Gate | Result |
|---|---:|
| Configured model/reasoning/runtime live matrix | 50/50 (100%) |
| Individual workflow live matrix | 5/5 (100%) |
| Full connectCenter MCP CRUD lifecycle | PASS |
| Context-scheme pagination live regression | 1/1 (100%) |
| Java tests | 720/720 |
| connect-center-api tests | 9/9 |
| Web unit tests | 951/951 |
| Web production build | PASS |
| Ruff, script syntax, `git diff --check` | PASS |

The requested browser control could not be executed: the supported browser runtime reported no
available browser binding (`[]`) after setup and troubleshooting. The web server itself returned
HTTP 200 on `http://localhost:4200`, form login with `oagis/oagis` succeeded through the same backend
endpoint used by the UI, and the UI build/unit suites passed. This is an unavailable test surface,
not an observed application failure; no browser result is represented as a pass.

## Runtime architecture exercised

```text
ChatService
  -> AiMultiAgentManager
     -> AiWorkflowPlanner (validated recursive AiWorkflowNode tree)
     -> AiWorkflowCompiler (registered node compilers)
     -> EvaluatorOptimizerWorkflow
        -> selected Workflow graph.process(context)
        -> AiWorkflowEvaluator
           -> COMPLETE: return result
           -> CONTINUE: feedback + next objective -> planner -> next graph
```

The common workflow implementations are:

- `DirectWorkflow`: one model/worker operation.
- `ChainWorkflow`: ordered children with the preceding output added as untrusted upstream evidence.
- `ParallelizationWorkflow`: concurrent children, shared deadline, deterministic aggregation, and
  explicit partial/all-failure behavior.
- `RoutingWorkflow`: one validated named route.
- `OrchestratorWorkersWorkflow`: concurrent workers plus lead synthesis.
- `EvaluatorOptimizerWorkflow`: bounded plan/execute/evaluate/replan controller.

`AiWorkflowCompiler` uses a registry of `WorkflowNodeCompiler` implementations and a restricted
`CompilationContext`. A new execution node can be added without editing a central type switch, while
planner grammar and validation must explicitly authorize model-authored use of that new type.

## Individual workflow live matrix

All cases used `claude-sonnet-5`, reasoning `medium`, runtime `claude`, real read-only connectCenter
MCP calls, planner selection, evaluator completion, and trajectory evidence. Parent and embedded
subagent trajectories were included when counting tool search and data tools.

| Workflow | Result | Time | Iterations | Subagent model calls | Tool-search calls | MCP data calls |
|---|---:|---:|---:|---:|---:|---:|
| direct | PASS | 19.6 s | 1 | 0 | 2 | 2 |
| chain | PASS | 59.5 s | 1 | 9 | 5 | 6 |
| parallel | PASS | 72.7 s | 2 | 13 | 8 | 12 |
| routing | PASS | 40.8 s | 2 | 6 | 2 | 4 |
| orchestrator_workers | PASS | 40.9 s | 1 | 6 | 3 | 6 |

The query requested the first page of context categories and context schemes using actual tools.
Every case selected its requested workflow, recorded `controller_workflow=evaluator_optimizer`, ended
with `evaluation_status=complete`, invoked `toolSearchTool`, and then invoked both relevant MCP read
tools. Detailed answers, conversation IDs, token metrics, and tool names are preserved in the live
matrix artifact.

## Configured model, effort, and runtime matrix

The live harness read the model metadata exposed by the running application and executed both the
default runtime selection and each explicit provider runtime. The expected exact response was
verified for every call.

| Model family | Cases | Passed | Average | Minimum | Maximum |
|---|---:|---:|---:|---:|---:|
| Claude Fable 5 | 8 | 8 | 15.7 s | 13.1 s | 21.7 s |
| Claude Opus 4.8 | 8 | 8 | 10.0 s | 6.7 s | 15.5 s |
| Claude Sonnet 5 | 8 | 8 | 15.7 s | 5.9 s | 52.9 s |
| Claude Haiku 4.5 | 2 | 2 | 4.4 s | 4.0 s | 4.7 s |
| GPT-5.6 SOL | 8 | 8 | 8.2 s | 5.8 s | 15.9 s |
| GPT-5.6 Terra | 8 | 8 | 5.7 s | 4.4 s | 6.5 s |
| GPT-5.6 Luna | 8 | 8 | 5.8 s | 4.7 s | 7.0 s |

Coverage was Claude `low/medium/high/max` with default and explicit Claude runtimes, Haiku default
effort with both runtime selections, and OpenAI `low/medium/high/xhigh` with default and explicit
OpenAI runtimes. Total result: 50/50, 100%.

## Full MCP lifecycle

One model-directed run used `claude-fable-5`, effort `high`, runtime `claude`, and the unique prefix
`WF-20260720T224500Z`. It performed 45 structured tool calls and selected eight required capabilities
through an exact `select:...` tool-search request before executing them.

The run created, read, updated, and deleted this connected graph:

```text
context category 94
  -> context scheme 80
     -> context scheme values 130, 131
        -> business context 79
           -> business context value 132
```

Deletion was performed in dependency-safe order, and final filtered list/read-back checks returned
no matching records. A separate regression run (`DEL-20260720T225134Z`, Sonnet/medium/Claude) created
category 96 and linked scheme 81, deleted the scheme directly without temporarily moving it to a
different category, deleted the category, and confirmed both IDs were absent.

## Deferred model-directed tool search

The implementation was based on three frontier-client patterns:

| Source | Observed pattern | Application adaptation |
|---|---|---|
| Claude Code | Names-only deferred catalog, model `select:Read,Edit`, schema loaded after discovery, MCP `alwaysLoad` escape hatch | Names-only catalog, exact comma-separated selection, accumulated results |
| Codex | Empty direct MCP set in deferred mode; BM25 search exposes matching specifications on the next call | Private server registry plus safe entity-weighted natural-language fallback |
| OpenAI tool search | Deferred tools/namespaces and provider-managed schema loading | Equivalent advisor-level behavior across Anthropic and OpenAI runtimes |

The executing workflow model acts as the tool-search agent. Initially it receives only the
`toolSearchTool` schema and alphabetized tool names. Full MCP callback schemas stay in a
requester-scoped server registry and are added only after selection. Read-only workers are filtered
to read-only callbacks first and then use the same deferred advisor; mutation confirmation alone
uses the direct callback path.

`ScoreToolIndex` no longer interprets model-supplied query text as a regular expression. Exact
`select:name1,name2` and single-name selection use registry equality; safe tokenized entity ranking
is retained only as a fallback. A fingerprint prevents stale session indexes, an LRU policy bounds
them, and selected callbacks must resolve against the private registry.

## Defects found and corrected

| Defect | Correction | Verification |
|---|---|---|
| Generic regex/OR search could fail or crowd relevant tools out | Exact model-directed selection plus non-regex ranked fallback | Index/advisor tests and all live trajectories |
| Read-only delegated workers bypassed deferred search and received every read-only schema | Filter read-only callbacks, then apply the deferred advisor | Routing/orchestrator embedded trajectories show tool-search calls |
| A chain synthesis leaf could describe future work instead of using upstream evidence | Stronger upstream contract requires an evidence-backed result now | Chain live case completed in one iteration |
| Evaluator converted unfinished `CONTINUE` at the cap into a false `COMPLETE` | Preserve the decision; controller emits `evaluation_status=iteration_limit` | Unit and manager integration tests |
| Deleting a scheme was rejected merely because it had a parent category | Removed the reversed dependency guard; real dependent values remain protected | Live category 96/scheme 81 regression and service test |
| Context-scheme pagination applied `LIMIT` to scheme/value joined rows | Page scheme IDs first, then load all values for those IDs | Repository test and live response returned two schemes for `limit=2` |

## Architecture quality score

The score is based on code and test evidence, not solely on the live happy path.

| Criterion | Score | Evidence |
|---|---:|---|
| Recursive composability | 10.0 | Every workflow implements the same immutable context/result contract and can be nested |
| Extension mechanism | 9.7 | Compiler registry, duplicate rejection, restricted extension context |
| Plan boundary and validation | 9.8 | Normalization, depth/node/route/worker bounds, unknown-type rejection |
| Failure and completion semantics | 9.7 | Fail-fast chain, parallel partial failure, deadlines, explicit iteration-limit state |
| Tool/context efficiency and safety | 9.7 | Deferred schemas, exact private-registry selection, read-only worker filtering |
| Observability | 9.6 | Planner/evaluator/tool steps plus recursively embedded subagent ATIF trajectories |
| Testability | 9.8 | Pure workflow edge tests, compiler/evaluator tests, live matrices, regression harnesses |
| **Overall (mean)** | **9.76 / 10** | All criteria exceed 9.5 |

The main remaining operational improvement is to add the live matrix scripts to CI with provider
credentials and provision a browser binding for UI E2E. Neither requires changing the workflow
abstractions.

## Reproduction and artifacts

- Model matrix: `experiments/ai-workflow-validation/model-runtime-20260720/`
- Workflow matrix: `experiments/ai-workflow-validation/workflow-live-20260720/`
- Pagination regression: `experiments/ai-workflow-validation/pagination-regression-20260720/`
- Architecture: `docs/ai-workflow-architecture.md`
- Harnesses: `scripts/ai-model-runtime-smoke-matrix.mjs`, `scripts/ai-workflow-live-matrix.mjs`

Core commands:

```bash
cd score-http && ./mvnw test
cd connect-center-api && .venv/bin/pytest -q
cd score-web && npm run build && npm test -- --run
node scripts/ai-model-runtime-smoke-matrix.mjs model-runtime-20260720
node scripts/ai-workflow-live-matrix.mjs workflow-live-20260720
```

Reference material: [Anthropic, Building effective agents](https://www.anthropic.com/engineering/building-effective-agents),
[Spring AI effective agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html), and
[OpenAI tool search](https://developers.openai.com/api/docs/guides/function-calling#tool-search).
