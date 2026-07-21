# Live workflow matrix

- Run: `workflow-live-20260720`
- Model: `claude-sonnet-5`, effort: `medium`, runtime: `claude`
- Cases: 5; passed: 5; failed: 0

| Workflow | Result | ms | Iterations | Planner | Evaluator | Subagent calls | Tool search | Data tools | Error |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| direct | PASS | 19597 | 1 | 1 | 1 | 0 | 2 | get_context_categories, get_context_schemes |  |
| chain | PASS | 59490 | 1 | 1 | 1 | 9 | 5 | get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes |  |
| parallel | PASS | 72744 | 2 | 2 | 2 | 13 | 8 | get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes |  |
| routing | PASS | 40805 | 2 | 2 | 2 | 6 | 2 | get_context_categories, get_context_schemes, get_context_schemes, get_context_categories |  |
| orchestrator_workers | PASS | 40867 | 1 | 1 | 1 | 6 | 3 | get_context_categories, get_context_schemes, get_context_categories, get_context_schemes, get_context_categories, get_context_schemes |  |
