# Local OpenTelemetry stacks

Both local observability stacks accept OTLP signals through the same loopback-only Collector endpoints:

- OTLP/gRPC: `127.0.0.1:4317`
- OTLP/HTTP: `http://127.0.0.1:4318`
- Collector health: <http://127.0.0.1:13133/>

The private AI observability SDK sends only AI request traces and metrics to the OTLP/HTTP
endpoints when the `dev` Spring profile is active. It uses `service.name=score-ai`; HTTP, JDBC,
scheduler, JVM, and other application-wide signals are intentionally excluded. Prometheus scrapes
AI metrics exposed by the Collector, and Grafana is pre-provisioned with the relevant trace
datasource plus Prometheus.

Grafana also provisions **SCORE AI / SCORE AI · GenAI OpenTelemetry** at
<http://127.0.0.1:3000/d/score-ai-genai-otel>. The dashboard uses the standard GenAI client,
agent, tool, and workflow metrics for latency and token panels, while retaining SCORE-specific
turn, queue, retry, and outcome panels for application-level signals.

Only the AI-specific enable switch and service name live under `score.ai.observability`. Shared
resource attributes, sampling, trace OTLP export, and metrics OTLP export use Spring Boot's
`management.opentelemetry`, `management.tracing`, and `management.otlp.metrics.export` property
names. A future application-wide SDK can therefore reuse the same Collector endpoints and export
policy without copying AI-specific configuration. The private AI SDK honors the shared OTel
enable switch, resource attributes, sampler/probability, trace export switches, OTLP endpoints,
headers, timeouts, and export cadence; application instrumentation and unrelated management
settings remain outside its scope.

Environment variables backing shared `management.*` settings use the `OTEL_` prefix (for example,
`OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG`, `OTEL_BSP_SCHEDULE_DELAY`, and
`OTEL_METRIC_EXPORT_INTERVAL`). Within the observability block, only the AI-private settings use
`SCORE_AI_`: `SCORE_AI_OBSERVABILITY_ENABLED` and `SCORE_AI_OBSERVABILITY_SERVICE_NAME`.
The former `SCORE_OTEL_*` aliases are no longer read.

Because Score builds a private SDK rather than using OpenTelemetry autoconfiguration, a few names
are Spring configuration shortcuts rather than OpenTelemetry SDK specification variables:
`OTEL_TRACES_EXPORT_ENABLED`, `OTEL_METRICS_EXPORT_ENABLED`, `OTEL_SERVICE_NAMESPACE`, and
`OTEL_DEPLOYMENT_ENVIRONMENT`. The sampler, sampler argument, batch processor, signal-specific
OTLP endpoint/timeout, and metric interval names follow the OpenTelemetry environment-variable
conventions. Set the documented Spring shortcuts instead of `OTEL_RESOURCE_ATTRIBUTES` or an
exporter value of `none` when configuring this private SDK.

OTLP export uses OpenTelemetry's JDK HTTP sender, keeping it independent of Spring AI's OkHttp
runtime and avoiding client-library version conflicts.

The AI spans cover Assistant turns, standalone `/ai/generate/...` requests, automatic context
compaction, agents, workflows, model calls, and tool/MCP calls. W3C trace context is continued
from inbound `traceparent` headers and propagated to MCP HTTP calls. Request,
conversation, user, tool-call, workflow-node, and agent-run IDs are trace/ATIF attributes only;
they are never metric labels. Prompt, tool content, exception messages, and stack traces are not
exported. The standard time-to-first-chunk metric observes the first emitted streaming chunk,
including metadata-only chunks; SCORE's custom TTFT remains gated on the first content/tool token.

GenAI telemetry is pinned to the OpenTelemetry `semantic-conventions-genai` development
specification at commit `2e994c6d59a93bb4fc1752c5378eedb9b8e14d6b` (2026-07-21). Span names follow
`invoke_agent <agent>`, `chat <model>`, `execute_tool <tool>`, and
`invoke_workflow <workflow>`. An `invoke_workflow` boundary is emitted only when execution
actually coordinates multiple agents or generative-AI operations (the `multi_agent_*` and
`parallel_workflow_*` lead lifecycles). A planner-selected single-agent path, including the
evaluator/optimizer control loop, does not become a workflow span merely because it has an
internal workflow name. Individual `subagent_*` and `parallel_task_*` lifecycles are represented
by their `invoke_agent` spans beneath the one coordinating workflow span. The planner model call is nested as
`invoke_agent workflow-planner` → `plan workflow-planner` → `chat <model>`, matching the agent
semantic conventions. Standard duration metrics use seconds, token usage is a histogram,
and cache-token span attributes use the nested `gen_ai.usage.cache_read.input_tokens` and
`gen_ai.usage.cache_creation.input_tokens` names. Anthropic input-token totals include both cache
categories. Standard model names are the exact identifiers sent to the provider; SCORE registry
aliases are retained only as `score.ai.model.alias`. MCP attributes enrich the existing internal
`execute_tool` span, while W3C context is
still propagated through MCP `params._meta`. When the development specification changes,
update the pin and the compatibility tests together.

SCORE does not emit `create_agent` for its in-process multi-agent execution. Catalog lookup,
ephemeral branch definitions, and invoking an existing worker are not discrete agent-creation
operations. Add the span only if SCORE gains an actual local or remote agent creation/provisioning
operation; a remote implementation must emit the specification's required provider and client
attributes.

Capacity signals include active requests, queue delay, and bounded admission-rejection reasons.
The AI executor uses an unbounded virtual-thread-per-task design, so a pool-saturation percentage
would be misleading and is intentionally not emitted. `score.ai.cost` is recorded only when a
provider reports `cost_usd` or `costUsd`; the application does not invent prices when the provider
does not supply cost data.

Run only one stack at a time because both publish the same local ports. Stop the active stack
before switching modes.

## Stack A: Jaeger storage with two trace UIs

This mode stores one copy of each trace in Jaeger's local in-memory storage. Both the native Jaeger
UI and Grafana query that same Jaeger backend.

```bash
docker compose -f docker-compose.otel.yml up -d
```

- Jaeger UI: <http://127.0.0.1:16686/>
- Grafana with Jaeger and Prometheus: <http://127.0.0.1:3000/>
- Prometheus UI: <http://127.0.0.1:9090/>

## Stack B: duplicate traces to Jaeger and Tempo

This comparison mode stores every trace independently in Jaeger and Tempo. Jaeger UI queries
Jaeger, while Grafana queries Tempo. Grafana also uses Prometheus for application metrics and for
Tempo-generated service graph and span metrics.

```bash
docker compose \
  -f docker-compose.otel.yml \
  -f docker-compose.otel-tempo.yml \
  up -d
```

- Jaeger UI: <http://127.0.0.1:16686/>
- Grafana with Tempo and Prometheus: <http://127.0.0.1:3000/>
- Tempo API: <http://127.0.0.1:3200/ready>
- Prometheus UI: <http://127.0.0.1:9090/>

Stop Stack A with `docker compose -f docker-compose.otel.yml down`. Stop Stack B with:

```bash
docker compose \
  -f docker-compose.otel.yml \
  -f docker-compose.otel-tempo.yml \
  down
```

Add `--volumes` only when the selected stack's named volumes should also be removed. This deletes
Grafana and Prometheus data in either stack, plus Tempo data in Stack B.

## Verification

For Stack B, check service state and Collector health with the same two Compose files used to
start it:

```bash
docker compose \
  -f docker-compose.otel.yml \
  -f docker-compose.otel-tempo.yml \
  ps
curl --fail http://127.0.0.1:13133/
```

After starting `score-http` and making an AI Assistant request, verify that both trace backends return its traces,
both Grafana datasources are healthy, and Prometheus is scraping the Collector:

```bash
curl --get --fail \
  --data-urlencode 'service=score-ai' \
  --data-urlencode 'limit=1' \
  'http://127.0.0.1:16686/api/traces'
curl --get --fail \
  --data-urlencode 'q={ resource.service.name = "score-ai" }' \
  --data-urlencode 'limit=1' \
  'http://127.0.0.1:3200/api/search'
curl --fail 'http://127.0.0.1:3000/api/datasources/uid/prometheus/health'
curl --fail 'http://127.0.0.1:3000/api/datasources/uid/tempo/health'
curl --fail 'http://127.0.0.1:9090/api/v1/targets'
```

Open the provisioned **SCORE AI · GenAI OpenTelemetry** dashboard, or in Grafana **Explore** select
**Prometheus** and query
`{service_name="score-ai"}` or `score_ai_turn_duration_milliseconds_count` for AI metrics, and
`traces_spanmetrics_calls_total` for
Tempo-derived span metrics. Tempo's service graph requires traces containing a client/server or
producer/consumer span relationship, so a single isolated span does not create an edge. Select
**Tempo** in Stack B to search traces; Jaeger UI remains available for comparing the independently
stored Jaeger copy.

## Overrides and local-only security

All published ports bind to loopback. Override them with `OTEL_COLLECTOR_GRPC_PORT`,
`OTEL_COLLECTOR_HTTP_PORT`, `OTEL_COLLECTOR_HEALTH_PORT`, `JAEGER_UI_PORT`, `GRAFANA_UI_PORT`,
`PROMETHEUS_UI_PORT`, or `TEMPO_HTTP_PORT`. Docker logs rotate at 10 MB with three files by
default; use `OTEL_STACK_LOG_MAX_SIZE` and `OTEL_STACK_LOG_MAX_FILES` to change those limits.

Grafana anonymous Admin access is intentionally enabled for this loopback-only development stack.
Do not expose these Compose services on a shared or public network without authentication and TLS.
Jaeger uses transient in-memory storage; Prometheus and Tempo retain local data for 24 hours by
default. The AI observability SDK exports traces and metrics only; it does not install an
OpenTelemetry logging appender.

Tempo 3.0.x currently logs an idle `no jobs found` backend-scheduler response at error level even
though the empty queue is healthy; this is tracked in
[grafana/tempo#7666](https://github.com/grafana/tempo/issues/7666). Use `/ready` and actual export
results when assessing this local stack.
