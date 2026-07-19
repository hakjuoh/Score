# AI assistant multi-agent matrix — 2026-07-19

## 판정

- 실제 `http://localhost:4200`에 `oagis/oagis`로 로그인해 실행했다.
- Spring AI 기본, Claude provider profile, OpenAI provider profile을 모두 실행했다.
- 저장된 REST 응답과 ATIF를 새 엄격 evaluator로 다시 판정한 결과는 **13/13 PASS**다.
- specialist는 exact read-only allowlist 밖의 도구를 호출하지 않았다.
- 네 mutation 사례는 승인 전 미실행, 승인 후 정확히 한 번 생성, 생성 ID와 동일한 상세 read-back,
  최종 응답 ID 보고를 모두 통과했다. 생성된 Context Category ID는 `90`~`93`이다.
  ID `93` 실행은 evidence schema v3로 평문 one-time grant를 저장하지 않고 grant digest와
  승인 상태/대화/continuation request/authorization digest를 별도 저장했다.

원시 응답과 ATIF는 로컬 `experiments/ai-multi-agent-matrix/output/`에 보존했다. 이 디렉터리는
기존 사용자 untracked 작업 영역이므로 커밋하지 않는다. 다음 명령으로 provider 호출 없이 다시
검증할 수 있다.

```bash
node scripts/analyze-ai-multi-agent-matrix.mjs \
  --input experiments/ai-multi-agent-matrix/output \
  --output /tmp/ai-multi-agent-matrix-analysis
```

## 실행 결과

| Run | Model / effort / runtime | Mode / strategy | 시간 | 결과 |
|---|---|---|---:|:---:|
| claude-default-creative-20260719 / single | Claude Fable 5 / low / default | single | 42.1s | PASS |
| claude-default-creative-20260719 / parallel | Claude Fable 5 / low / default | 2 creative | 59.4s | PASS |
| claude-runtime-verification-20260719 | Claude Fable 5 / high / claude | 3 verification | 157.9s | PASS |
| openai-runtime-balanced-20260719 | GPT-5.6 Sol / medium / openai | 2 balanced | 191.0s | PASS |
| mutation-safety-default-20260719 | GPT-5.6 Terra / low / default | 2 verification | 27.3s | PASS, ID 90 |
| posthardening-claude-20260719a | Claude Fable 5 / low / default | 2 creative | 81.8s | PASS |
| posthardening-mutation-260719b | GPT-5.6 Terra / low / default | 2 verification | 31.2s | PASS, ID 91 |
| evidence-v2-mutation-260719c | GPT-5.6 Terra / low / default | 2 verification | 98.8s | PASS, ID 92 |
| evidence-v3-mutation-260719d | GPT-5.6 Terra / low / default | 2 verification | 25.2s | PASS, ID 93 |
| evidence-v3-creative-260719e | Claude Fable 5 / low / default | 2 creative | 72.1s | PASS, 4 domains |
| compare-runtime-claude-20260719 | Claude Fable 5 / low / claude | 2 creative | 88.5s | PASS |
| compare-effort-high-20260719 | Claude Fable 5 / high / default | 2 creative | 130.8s | PASS |
| compare-model-sonnet-20260719 | Claude Sonnet 5 / low / default | 2 creative | 52.1s | PASS |

## 통제 비교

아래는 같은 creative read-only task와 2-agent 설정에서 축 하나만 바꾼 1회 관측이다.
통계적 benchmark나 provider 우열의 근거로 일반화하지 않는다.

| 변경 축 | 기준 | 비교 | 시간 비율 | completion tokens | ATIF steps |
|---|---:|---:|---:|---:|---:|
| single → parallel | 42.1s | 59.4s | 1.41x | 1,956 → 4,917 | 14 → 36 |
| default → claude runtime | 81.8s | 88.5s | 1.08x | 5,818 → 5,573 | 45 → 44 |
| low → high effort | 81.8s | 130.8s | 1.60x | 5,818 → 10,472 | 45 → 57 |
| Fable → Sonnet model | 81.8s | 52.1s | 0.64x | 5,818 → 6,185 | 45 → 40 |

high effort는 중복 business context `6/7`, 유일한 top-level ASBIEP `2`, agency list `101`까지
확장해 low보다 넓은 증거를 제시했다. Sonnet은 가장 짧은 시간에 datatype cardinality,
ATIF namespace fork, BOD pair 검사를 제안했다. 모든 creative 답변은 실제 ID, 관측/추론 구분,
각 제안의 반론을 포함했다.

## 엄격 판정 조건

- nested MCP `{error: ...}`와 승인 대기 결과를 성공으로 세지 않는다.
- mutation은 정확히 한 개의 실행 결과에서 생성 ID를 추출한다.
- mutation 뒤의 read에서 ID/name/description이 모두 일치하고 최종 응답이 같은 ID를 보고해야 한다.
- ask mode mutation은 승인 전 정확히 한 번 거절되고 승인 후 정확히 한 번 실행되어야 한다.
  confirmation, 실제 mutation arguments, 생성 ID를 사용한 read 요청이 exact하게 연결되어야 한다.
- `fanout_id`는 request ID의 SHA-256 파생값이어야 한다.
- lead FSM은 `started -> synthesizing -> completed`, child FSM은
  `started -> completed|failed`여야 한다.
- node ID, parent, request, fanout, depth, agent name/role, 고유 연속 ordinal을 모두 검사한다.
- 모든 child가 첫 terminal 전에 시작했는지, `step_id`가 고유·단조 증가하는지 검사한다.
- strategy별 deterministic name/role, 최소 한 child 성공, 선언 case directory/result 개수를 검사한다.
- fan-out final은 lead terminal 뒤 정확히 하나여야 하며 continuation mutation/read/final은 같은
  request ID를 사용해야 한다.
- REST 응답과 ordered ATIF final은 개수와 내용이 정확히 같아야 하고 single final도 하나여야 한다.
- fan-out turn의 namespaced lead model/tool은 depth 0 lead node, specialist model/tool은 depth 1
  child node의 lifecycle 구간에 연결되어야 한다. 승인 continuation은 server가 single-agent로
  강제하므로 unnamespaced다.
- specialist tool은 backend와 동일한 exact read-only allowlist에 있어야 한다.
  (후속 변경: 이 allowlist는 이후 contract에서 제거되었다. 이제 connect-center-mcp가 각 도구에
  선언하는 MCP `readOnlyHint` annotation을 세션마다 해석해 guard가 판정하고, 모든 tool step에
  기록된 `extra.read_only` boolean을 evidence schema v4 evaluator가 검증한다.)
- read-only 성공은 도구별 허용된 domain ID key가 실제 result에 있고 최종 응답이 해당 ID label과
  값을 함께 보고할 때만 grounded로 인정한다. audit `user_id`나 일반 목록 번호는 근거가 아니다.
- schema v3 creative는 library/release/datatype/core-component 네 그룹을 모두 충족해야 한다.
  각 그룹은 자기 ID key만 사용할 수 있고, 정확히 세 numbered proposal 각각에 grounded ID와
  명시적 counterargument가 있어야 한다.

evaluator 자체는 중첩 오류, 사전 read 거짓 양성, 잘못된 read-back, 잘못된 lead node,
혼합 fanout/depth, lifecycle 밖 실행, 예상 밖 mutation, conversation mismatch, confirmation 근거
누락, namespace 제거, sequential fan-out, final/step/request 변조, decision 위조, role/child 상태,
빈 결과, audit-ID 오인, case 삭제, 승인/실행 arguments와 pre-approval trace 변조를 포함한
19개 적대적 fixture로 검증한다. 여기에는 한 domain만 충족, cross-domain ID 재사용, 네 ID만
나열하고 제안/반론을 생략하는 의미적 거짓 양성도 포함한다.

```bash
node --test scripts/ai-multi-agent-evaluator.test.mjs
node --test scripts/analyze-ai-multi-agent-matrix.test.mjs
```

증거 수준은 구분해 해석한다. schema v1 실행은 저장 decision이 없어 승인 여부를 response에서
추론하며 일부 초기 trace namespace도 legacy 계약이다. schema v2는 당시 서버가 발급한 32-byte
opaque one-time grant 원문을 저장한 역사적 실행이며 현재는 이미 소비되었다. 이는 서명이 아니다.
schema v3부터는 평문 grant를 기록하지 않고 SHA-256 digest, APPROVED 상태, conversation,
continuation request, authorization digest를 묶은 `redacted_linked` 증거를 사용한다.

## 대표 재현 명령

`--run-id`는 지정하지 않는다. runner가 timestamped run-id를 자동 생성하므로 보존된 원본 출력
디렉터리에 덧쓰거나 run-id에서 파생되는 mutation Context Category 이름이 충돌하는 일을 막는다.

```bash
# single / parallel (원본 run: claude-default-creative-20260719)
node scripts/ai-multi-agent-matrix.mjs \
  --mode both --strategy creative --task creative --model claude-fable-5 \
  --effort low --runtime default --max-agents 2 --limit 1 --permission-mode ask

# Claude runtime (원본 run: compare-runtime-claude-20260719)
node scripts/ai-multi-agent-matrix.mjs \
  --mode parallel --strategy creative --task creative --model claude-fable-5 \
  --effort low --runtime claude --max-agents 2 --limit 1 --permission-mode ask

# OpenAI runtime (원본 run: openai-runtime-balanced-20260719)
node scripts/ai-multi-agent-matrix.mjs \
  --mode parallel --strategy balanced --task landscape --model gpt-5_6-sol \
  --effort medium --runtime openai --max-agents 2 --limit 1 --permission-mode ask

# lead-only mutation + schema v3 redacted approval + exact read-back (원본 run: evidence-v3-mutation-260719d)
node scripts/ai-multi-agent-matrix.mjs \
  --mode parallel --strategy verification --task mutation --model gpt-5_6-terra \
  --effort low --runtime default --max-agents 2 --limit 1 --permission-mode ask

# schema v3 creative + all four evidence domains (원본 run: evidence-v3-creative-260719e)
node scripts/ai-multi-agent-matrix.mjs \
  --mode parallel --strategy creative --task creative --model claude-fable-5 \
  --effort low --runtime default --max-agents 2 --limit 1 --permission-mode ask
```

## UI 검증 범위

Angular component rendering으로 parallel badge, 2–4 agent 설정, strategy, ARIA live status,
stable lifecycle upsert, REST attachment lifecycle, cancel/error/final settlement, ATIF trajectory 링크를
검증했다. 이 세션에는 in-app Browser 인스턴스가 연결되지 않아 실제 브라우저 클릭과 screenshot은
수행하지 못했다. 이 외부 제약은 기능/API/ATIF 판정과 구분해 기록한다.
