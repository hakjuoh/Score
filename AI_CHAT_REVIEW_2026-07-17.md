# AI Chat 현황 및 개선 검토

- 검토일: 2026-07-17
- 수정·재검증 완료: 2026-07-17
- 기준 브랜치/커밋: `develop_spring_ai_chat` / `1785b0d93`
- 현재 상태: BUG 7건, RISK 6건, 후속 P1 4건과 Claude 재검토 잔여 5건 수정 완료 · 전체 회귀·적대적 검증 통과
- 범위: `score-web` AI Chat 패널, `score-http` Chat API·런타임·MCP·메모리·trajectory, 관련 DB migration과 테스트
- 제외: 별도 Ollama 기반 definition/name 생성 API의 품질, 실제 외부 모델의 응답 품질·가용성, 운영 인프라 부하 시험

## 1. 결론

최초 검토에서 확인한 기능 버그 7건과 보안·신뢰성 위험 6건은 모두 수정했다. 이후 `31d4542a3`에 대한 독립 재검증에서 partial로 판정된 3건과 새 P1 회귀 4건도 후속 수정했다. mutation은 이제 서버가 발급한 10분 TTL의 one-time grant를 owner·conversation·tool·canonical arguments에 결합해 소비하며, `get_*`와 `who_am_i` 외 MCP 도구는 fail-closed 방식으로 승인을 요구한다. WebSocket뿐 아니라 첨부 turn과 승인 후 follow-up이 사용하는 REST 응답도 strict confirmation event를 전달한다.

요청 deadline은 설정값을 기준으로 scheduler가 집행한다. deadline과 stop-watchdog 작업은 단일 scheduler에서 즉시 가상 스레드로 분리되므로 한 entry의 final DB persistence lock이 다른 요청의 deadline dispatch를 막지 않는다. 최종 결과 저장과 취소의 registry lock 원자성은 유지하며, interrupt를 무시하는 worker는 30초 뒤 `UNKNOWN_RECONCILIATION_REQUIRED`로 terminal 처리되어 active slot을 계속 점유하지 못하고 이후 commit도 거부된다.

첨부·page context·`/compact` 계약, registry/history/trajectory bound와 90일 retention, reasoning/tool/error redaction도 함께 수정했다. 안전한 첨부 validation 오류만 HTTP 400과 정확한 메시지 패턴으로 표시하며, `cookie`·`session`·`credential`·단독 `token`·`auth`를 audit와 승인 알림의 문자열 leaf까지 공통 규칙으로 가린다. camelCase 키를 논리적으로 분리해 `authorId`·`isAuthenticated`·`sessionCount` 같은 일반 필드는 보존한다. 전체 회귀, 패키지, 빌드·lint와 적대적 경쟁 조건 검증을 통과했다.

### 1.1 수정 결과 요약

| ID | 상태 | 적용 결과 |
|---|---|---|
| BUG-01 | 완료 | DB-backed confirmation 상태 기계, one-time grant, WebSocket 및 REST attachment/approved-follow-up confirmation event 구현 |
| BUG-02 | 완료 | 설정 기반 전체 요청 deadline scheduler와 `TIMED_OUT` terminal 구현 |
| BUG-03 | 완료 | `CANCELLING`과 terminal 분리, 실제 worker 종료 확인, final persistence fence, reconciliation 상태 구현 |
| BUG-04 | 완료 | archive 제거, MIME/spoof 검사 정렬, allowlist된 실제 HTTP 400 attachment 검증 메시지 표시 |
| BUG-05 | 완료 | current page snapshot을 매 turn 전송하고 untrusted data로 표시 |
| BUG-06 | 완료 | tool-free 요약 실행, bounded assistant reference memory 교체, `compacted` 저장 구현 |
| BUG-07 | 완료 | conversation/generation cancel fence와 stale disposition 구현; generation은 요청마다 새 safe-integer |
| RISK-01 | 완료 | page/attachment/tool result prompt 경계와 server-side mutation guard 적용 |
| RISK-02 | 완료 | terminal 30분 TTL cleanup, registry 10,000건·사용자별 active 8건 상한 적용 |
| RISK-03 | 완료 | conversation별 single active request 강제 및 model/delete 변경 경쟁 차단 |
| RISK-04 | 완료 | list 100, restore 500, trajectory 5,000 step bound와 90일 retention 적용 |
| RISK-05 | 완료 | raw reasoning 미저장, 32 KiB 제한, audit/승인 UI 공통 민감 키·문자열 leaf redaction, 안전한 사용자 오류 적용 |
| RISK-06 | 완료 | 10개/파일당 8 MiB/총 20 MiB 제한 정렬, encoded precheck와 FileReader generation fence 구현 |

### 1.2 해석 주의

§3과 §4의 상세 본문은 원인과 재현 근거를 남기기 위한 **수정 전 진단 기록**이다. 각 제목의 상태와 위 표, §7의 재검증 결과가 현재 구현 상태를 나타낸다.

## 2. 현재 구조

### 2.1 정상 요청 흐름

1. 프론트 패널이 prompt, 모델, reasoning effort, runtime option, 페이지 context를 조합한다.
2. 첨부가 없으면 STOMP `/app/ai/chat`, 첨부가 있으면 HTTP `POST /api/ai/chat`을 사용한다.
3. 서버 `prepare()`가 대화를 생성/잠그고 설정 snapshot을 append한다.
4. `AiRequestRegistry`가 실행 ID, 임의 generation, deadline과 수명주기 상태를 등록하고 conversation 단일 active turn을 강제한다.
5. 선택된 Java runtime이 requester-scoped MCP client와 Spring AI `ChatClient`를 연다. MCP callback은 mutation guard와 trajectory recorder로 감싼 뒤 model/tool loop에 제공한다.
6. WebSocket 요청은 assistant delta와 tool 상태를 스트리밍한다. HTTP 첨부 요청은 마지막 응답만 반환한다.
7. bounded model context는 `ai_chat_memory`, 전체 UI/audit trajectory는 `ai_chat_step`에 저장한다.
8. final persistence는 registry와 원자적으로 fence된다. 완료 후 프론트는 대화 목록을 다시 조회하고, 새로고침 시 retention/cap이 적용된 in-memory request registry를 polling하여 실행을 복구한다.

주요 구현:

- 프론트 orchestration: [`ai-chat-panel.component.ts`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-panel.component.ts)
- 프론트 API/transport: [`ai-chat-api.service.ts`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-api.service.ts), [`ai-chat-transport.service.ts`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-transport.service.ts)
- HTTP/STOMP controller: [`AiChatController.java`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java)
- 요청 준비·저장·실행: [`ChatService.java`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java)
- runtime loop: [`AbstractSpringAIRuntime.java`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/runtime/AbstractSpringAIRuntime.java)
- 실행 상태: [`AiRequestRegistry.java`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java)
- 대화/trajectory 저장: [`ScoreChatMemoryRepository.java`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ScoreChatMemoryRepository.java)

### 2.2 잘 되어 있는 부분

- 모든 대화 조회·변경·삭제가 `app_user_id` 소유권을 검사한다.
- MCP bearer token을 현재 `ScoreUser`로 발급하여 도구 권한을 requester 범위에 묶는다.
- model/runtime option을 서버에서 다시 정규화하고 허용되지 않은 option을 거부한다.
- 모델 context와 완전한 audit trajectory를 분리해 model window를 제한하면서도 실행 기록을 보존한다.
- 대화 설정 변경을 append-only `settings_change` step으로 남긴다.
- WebSocket restore에 token과 sequence를 사용하고, 프론트가 오래된 restore event를 거부한다.
- 도구 호출 시작/완료/실패와 provider usage를 trajectory에 연결한다.
- 전체 백엔드·프론트 단위/통합 테스트와 application context, 실제 MariaDB migration 검증이 통과한다. 실제 외부 provider/MCP 브라우저 E2E 한계는 §7.4에 기록했다.

## 3. 확인된 버그

### BUG-01 — mutation repeat confirmation 계약이 서버에 없음 · 수정 완료

- 우선순위: P0
- 분류: 기능 불능 + 안전 경계 누락
- 확실성: 확인됨

프론트는 `mutation_confirmation_required` event를 받아 승인/거부 dialog를 열고, 다음 API를 호출하도록 구현되어 있다.

```text
POST /api/ai/chat/conversations/{conversationId}/mutation-confirmations/{confirmationRequestId}/decision
```

승인 후에는 one-time `confirmationGrant`를 다음 chat request의 `mutationConfirmation`에 넣는다. 근거는 [`ai-chat-api.service.ts:48`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-api.service.ts#L48)과 [`ai-chat-panel.component.ts:1047`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-panel.component.ts#L1047)이다.

하지만 현재 서버에는 해당 decision endpoint, confirmation 저장소, grant 검증/소비 로직, `mutation_confirmation_required` event 생성 로직이 없다. 서버가 `ChatRequest.mutationConfirmation`을 전달받기는 하지만 [`ChatService.java:109`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L109)부터 runtime 실행까지 한 번도 읽지 않는다. 현재 controller의 전체 `/ai/chat` route에도 decision endpoint가 없다.

영향:

- 프론트의 repeat confirmation UI는 정상 서버 응답만으로 진입할 수 없다.
- endpoint를 직접 호출하면 404가 된다.
- grant를 임의로 chat request에 넣어도 서버 동작은 grant 유무와 동일하다.
- 데이터 변경 도구를 어떤 조건에서 재실행해도 되는지에 대한 서버 측 권위(authority)가 없다.

재현:

1. 기존 요청에서 `mutation_confirmation_required`를 기다린다. 현재 서버는 이 event를 만들지 않는다.
2. decision endpoint를 직접 호출한다. 매핑된 controller가 없어 404가 발생한다.
3. 같은 prompt를 `mutationConfirmation` 포함/미포함으로 각각 전송한다. 서버 실행 경로가 동일하다.

권고:

- 기능을 유지한다면 confirmation을 서버가 생성·저장·승인·1회 소비하는 완전한 상태 기계로 구현한다.
- MCP tool metadata에서 read/write를 분류하고, write tool 실행 직전에 grant를 검증해야 한다. prompt만으로 제어하면 안 된다.
- 아직 제품 요구사항이 아니라면 프론트의 dead workflow와 타입을 제거해 실제 지원 기능처럼 보이지 않게 한다.
- 최소 계약 테스트로 “notice → approve → 1회 실행 → 재사용 거부”를 브라우저부터 서버까지 검증한다.

### BUG-02 — 표시되는 10분 deadline이 집행되지 않음 · 수정 완료

- 우선순위: P0
- 분류: 실행 수명주기
- 확실성: 확인됨

controller는 `REQUEST_TIMEOUT = 10분`과 deadline을 registry에 넣고 accepted event로 보낸다([`AiChatController.java:55`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L55), [`AiChatController.java:183`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L183)). 그러나 `orTimeout`, scheduler, deadline 검사, `TIMED_OUT` 전환은 어디에도 없다. `AiRequestRegistry`는 `TIMED_OUT`을 terminal 상태로 인식만 할 뿐 그 상태를 만드는 코드가 없다([`AiRequestRegistry.java:126`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java#L126)).

provider HTTP timeout은 별도로 설정되지만 전체 agent/tool loop deadline과 같지 않다. 또한 controller의 10분은 `SCORE_AI_REQUEST_TIMEOUT` 설정과 독립적인 hard-code다.

영향:

- 여러 model/tool iteration을 거치는 전체 요청은 accepted deadline 이후에도 `RUNNING`일 수 있다.
- UI와 status API의 `deadline`이 실제 서버 보장을 나타내지 않는다.
- hanging provider/MCP 호출이 virtual thread와 registry entry를 장시간 점유할 수 있다.

권고:

- 하나의 설정값을 전체 요청 deadline의 source of truth로 사용한다.
- deadline scheduler에서 runtime subscription과 MCP session을 취소하고 registry를 원자적으로 `TIMED_OUT`으로 전환한다.
- model call timeout, MCP call timeout, 전체 turn timeout을 서로 구분해 status reason과 telemetry에 남긴다.
- never-completing fake runtime을 사용한 timeout 통합 테스트를 추가한다.

### BUG-03 — 취소가 실제 작업 종료보다 먼저 terminal로 확정됨 · 수정 완료

- 우선순위: P0
- 분류: 데이터 변경 안전성
- 확실성: 코드상 확인됨, 외부 client의 interrupt 반응에 따라 실제 잔여 실행 범위는 달라짐

취소 시 registry는 즉시 상태를 `CANCELLED`로 바꾸고 terminal timestamp를 기록한 뒤 thread interrupt와 `future.cancel(true)`를 호출한다([`AiRequestRegistry.java:74`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java#L74)). 즉 “취소 요청 수락”과 “작업이 실제로 멈춤”이 같은 상태로 처리된다.

Java interrupt와 `CompletableFuture.cancel(true)`는 이미 실행 중인 provider SDK 또는 동기 MCP tool의 중단을 보장하지 않는다. 특히 데이터 변경 tool이 이미 서버에 요청을 전달했다면 side effect가 완료될 수 있다. 그럼에도 UI는 즉시 `Request cancelled.`로 종료한다.

추가로 controller의 `whenComplete`는 취소 throwable에도 `recordFailure()`를 호출한다([`AiChatController.java:91`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L91), [`AiChatController.java:199`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L199)). 따라서 사용자가 정상 취소한 대화를 복원하면 `The assistant request failed.`가 visible error로 남을 수 있다.

권고:

- `CANCELLING`과 `CANCELLED`를 구분한다. 실제 runtime 종료/ack 이후에만 `CANCELLED`로 전환한다.
- provider reactive subscription과 MCP client를 명시적으로 dispose/close하는 cancellation hook을 runtime contract에 추가한다.
- tool이 mutation인지 추적하고, 취소 시 mutation 결과가 불명확하면 `UNKNOWN_RECONCILIATION_REQUIRED`로 끝낸다.
- `CancellationException`은 `recordFailure()` 대상에서 제외하고 별도 cancellation audit step을 남긴다.
- “취소 직전 mutation tool이 시작된 경우”를 포함한 경쟁 조건 테스트를 추가한다.

### BUG-04 — UI가 허용하는 archive 첨부를 서버가 항상 거부함 · 수정 완료

- 우선순위: P1
- 분류: 프론트/서버 계약 불일치
- 확실성: 확인됨

프론트는 ZIP, TAR, TAR.GZ, TGZ, GZ를 허용하고 file picker에도 표시한다([`ai-chat-panel.constants.ts:12`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-panel.constants.ts#L12), [`ai-chat-composer.component.html:4`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-composer.component.html#L4)).

서버는 text, `image/*`, `application/pdf`만 처리하고 그 외에는 `Unsupported AI attachment type`을 던진다([`ChatService.java:271`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L271)). 따라서 archive는 선택·Base64 변환·업로드까지 진행된 뒤 항상 실패한다. 프론트는 서버의 구체적 오류를 버리고 generic “attachment request could not be completed”만 보여준다([`ai-chat-panel.component.ts:407`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-panel.component.ts#L407)).

재현: 8 MB 미만 ZIP 파일을 첨부해 전송하면 HTTP 요청 후 서버가 400 계열 오류를 반환한다.

권고:

- archive 처리를 구현할 계획이 없다면 UI accept 목록과 지원 상수에서 즉시 제거한다.
- 구현한다면 압축 해제 크기·파일 수·중첩 depth·경로 순회·압축 폭탄 제한을 서버에 둔다.
- 서버가 반환한 안전한 validation message를 UI에 표시한다.
- 각 MIME/확장자 조합을 동일한 공유 fixture로 검증하는 계약 테스트를 추가한다.

### BUG-05 — 같은 페이지의 후속 turn에서 page context가 사라짐 · 수정 완료

- 우선순위: P1
- 분류: 대화 정확도
- 확실성: 확인됨

프론트는 route registry는 대화당 한 번, page snapshot은 path가 바뀔 때만 보낸다([`ai-chat-context.service.ts:21`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-context.service.ts#L21), [`ai-chat-panel.component.ts:2827`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-panel.component.ts#L2827)). 성공 후 `routeRegistrySent`와 `lastPageContextPath`를 갱신하므로 같은 path의 다음 요청에는 `pageContext`가 `undefined`다.

서버 runtime은 매 turn 새 system message를 만들며 pageContext가 없으면 `Not provided`를 넣는다([`AbstractSpringAIRuntime.java:51`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/runtime/AbstractSpringAIRuntime.java#L51)). `ChatService`가 model memory에 추가하는 것은 user/assistant message뿐이고 page context/system message는 저장하지 않는다([`ChatService.java:126`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L126), [`ChatService.java:137`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L137)).

영향:

- 첫 질문 “이 페이지의 항목을 설명해줘”는 context를 받지만, 같은 페이지에서 “그중 두 번째 것은?” 같은 후속 질문은 현재 snapshot과 route registry 없이 실행된다.
- route registry를 요구하는 markdown link 생성 품질도 두 번째 turn부터 떨어질 수 있다.

권고:

- 작은 current-page snapshot은 매 turn 전송한다.
- 큰 route registry는 서버의 trusted system context에 두거나, conversation-level context로 명시적으로 저장·재사용한다.
- “전송 최적화 상태”와 “model이 실제로 보유한 context”를 같은 개념으로 사용하지 않는다.
- 동일 path에서 연속 두 turn을 수행하는 테스트를 추가한다.

### BUG-06 — `/compact` 명령은 실제 compact를 수행하지 않음 · 수정 완료

- 우선순위: P1
- 분류: 미구현 UI 기능
- 확실성: 확인됨

프론트는 `/compact`를 “backend가 conversation context를 요약”하는 명령으로 안내한다([`ai-chat-panel.constants.ts:9`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-panel.constants.ts#L9)). 실제로는 다른 prompt와 동일하게 문자열 `/compact`를 모델에 보낼 뿐, 서버에는 command handler나 summary 저장·memory 교체 로직이 없다. 대화 목록의 `compacted`도 항상 `false`다([`ScoreChatMemoryRepository.java:133`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ScoreChatMemoryRepository.java#L133)).

현재 memory 제한은 오래된 message를 window에서 탈락시키는 기능이지 semantic summary가 아니다.

권고:

- 서버 command endpoint/handler에서 기존 context를 요약하고 summary message와 audit metadata를 원자적으로 저장한다.
- 또는 실제 구현 전까지 명령을 제거한다.
- compact 전후 memory와 history, `compacted` flag를 검증한다.

### BUG-07 — 취소 identity 계약을 서버가 검증하지 않음 · 수정 완료

- 우선순위: P2
- 분류: protocol 일관성
- 확실성: 확인됨

프론트는 cancel 요청에 `conversationId`와 `expectedGeneration`을 함께 요구하지만([`ai-chat-api.service.ts:64`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-api.service.ts#L64)), 서버 REST cancel은 path의 `requestId`와 cancellation ID만 사용하고 두 identity field를 무시한다([`AiChatController.java:145`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L145)). generation도 모든 entry에서 항상 `1`이다([`AiRequestRegistry.java:131`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java#L131)).

현재 request ID를 재사용하지 않으므로 즉시 exploit으로 이어지지는 않지만, 프론트가 stale-generation 방어가 있다고 가정하는 반면 서버에는 그 보장이 없다.

권고: status API처럼 cancel도 conversation/generation을 원자적으로 비교하고 불일치 시 명시적 `STALE_GENERATION` 또는 `COMMAND_MISMATCH`를 반환한다.

## 4. 보안·신뢰성 위험

### RISK-01 — untrusted page/attachment text가 write-capable agent context로 직접 들어감 · 수정 완료

- 우선순위: P0
- 분류: indirect prompt injection / 과도한 agent 권한

페이지 snapshot은 DOM의 제목, 버튼, label text를 수집한다([`ai-page-snapshot.service.ts:14`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-page-snapshot.service.ts#L14)). 이 값은 별도 escaping이나 “untrusted data” 경계 없이 system prompt의 `{pageContext}`에 삽입된다. text attachment도 XML 비슷한 문자열로 user message에 붙지만 파일 내용 안의 `</attachment>` 등을 escape하지 않는다([`ChatService.java:251`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L251)).

동시에 requester-scoped MCP의 read/write 도구가 model에 제공되고([`AbstractSpringAIRuntime.java:44`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/runtime/AbstractSpringAIRuntime.java#L44)), system prompt에는 “도구 결과를 확인하라”는 규칙은 있지만 untrusted content의 지시를 무시하거나 mutation에 별도 승인을 요구하는 규칙이 없다.

페이지 제목·첨부 파일처럼 사용자가 단순히 “분석할 데이터”라고 생각한 내용이 agent에게 도구 실행 지시로 해석될 수 있다. 역할 기반 MCP 권한은 피해 범위를 사용자 권한으로 제한하지만, 사용자의 의사 확인을 대신하지는 않는다.

권고:

- page snapshot과 attachment를 명시적인 untrusted-data block으로 전달하고 system prompt에 그 안의 명령을 따르지 않도록 선언한다.
- write tool은 prompt 수준이 아니라 tool execution wrapper 수준에서 분류·차단·승인한다.
- 변경 전 대상/요약을 보여 주고 backend-issued confirmation을 요구한다.
- prompt-injection fixture가 create/update/delete tool을 호출하지 못하는 보안 테스트를 추가한다.

### RISK-02 — terminal request registry가 무기한 증가함 · 수정 완료

- 우선순위: P1
- 분류: 메모리/운영 안정성

`AiRequestRegistry`는 모든 요청을 singleton `ConcurrentHashMap`에 넣지만 제거·TTL cleanup·최대 크기가 없다([`AiRequestRegistry.java:21`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java#L21)). 완료/실패/취소는 상태만 바꾼다.

영향:

- 프로세스가 오래 실행될수록 entry와 throwable/status metadata가 누적된다.
- 한 번 사용한 request ID는 프로세스 재시작 전까지 다시 사용할 수 없다.
- registry가 in-memory라 재시작 후에는 반대로 모든 recovery 정보가 즉시 사라진다.

권고: terminal retention TTL과 주기 cleanup을 두거나 durable request table로 이전한다. active recovery가 제품 요구사항이면 DB/Redis 같은 공유 저장소가 더 적합하다.

### RISK-03 — 동일 사용자/대화의 동시 요청이 허용됨 · 수정 완료

- 우선순위: P1
- 분류: 동시성·대화 정합성

registry는 request ID 중복만 막고 사용자 또는 conversation 단위 active 요청은 막지 않는다. `active()`는 여러 실행 중 가장 최근 하나만 반환한다([`AiRequestRegistry.java:109`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiRequestRegistry.java#L109)). 여러 탭이나 REST 직접 호출로 동일 conversation에 동시 요청을 보낼 수 있다.

각 요청은 실행 시작 전에 현재 memory snapshot을 복사하므로 서로의 in-flight user message를 보지 못한다([`ChatService.java:120`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L120)). 완료 시 `MessageWindowChatMemory`가 전체 bounded memory를 다시 저장하므로 완료 순서에 따라 context 순서/내용이 뒤섞이거나 한 요청의 memory update가 덮일 위험이 있다. DB trajectory append 자체는 conversation row lock으로 순번 충돌을 방지하지만 model context의 의미적 순서는 보장하지 않는다.

권고:

- conversation별 단일 active turn을 서버에서 강제하거나 명시적 queue를 둔다.
- 새 요청은 expected conversation version을 보내고 optimistic locking으로 검사한다.
- multi-tab 동시 요청과 역순 완료 테스트를 추가한다.

### RISK-04 — 대화/trajectory가 무제한이며 restore가 전체 이력을 한 번에 읽음 · 수정 완료

- 우선순위: P1
- 분류: 저장소·응답 크기

model memory는 최대 message 수가 있지만 `ai_chat_step`은 제한이나 보존 정책이 없다. 대화 상세 조회는 모든 step을 읽고([`ScoreChatMemoryRepository.java:150`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ScoreChatMemoryRepository.java#L150)), WebSocket restore는 이를 event 하나씩 전부 전송한다([`AiChatController.java:215`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L215)). trajectory endpoint도 전체 tool input/output, reasoning, token metadata를 한 번에 반환한다.

도구 output은 model-call observation과 별도 tool-call detail에 중복 저장될 수 있어 큰 조회 결과에서 증가폭이 크다.

권고:

- history pagination/virtual scroll, trajectory 별도 download, tool output truncation 또는 blob reference를 사용한다.
- tenant/user별 quota와 retention 정책을 정의한다.
- conversation summary와 최근 N개 visible message를 우선 복원한다.

### RISK-05 — raw reasoning·tool output·근본 예외가 노출·영구 저장됨 · 수정 완료

- 우선순위: P1
- 분류: 정보 노출·감사 데이터 관리

system prompt는 hidden prompt/reasoning을 노출하지 말라고 하지만([`connect-center-assistant-system-prompt.md:15`](score-http/src/main/resources/prompts/connect-center-assistant-system-prompt.md#L15)), recorder는 reasoning을 `reasoning_content`에 저장하고 debug event로 브라우저에 전송한다([`AiTrajectoryRecorder.java:97`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiTrajectoryRecorder.java#L97)). trajectory에는 tool arguments와 결과도 포함된다.

또한 controller `safeMessage()`는 가장 안쪽 root cause의 원문을 그대로 UI와 visible error history에 보낸다([`AiChatController.java:283`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L283)). provider/MCP/DB 예외가 endpoint, 내부 구조 또는 민감한 payload를 포함할 수 있다.

권고:

- reasoning 저장 필요성을 별도 감사 요구사항으로 승인받고 기본 UI에는 노출하지 않는다.
- tool input/output에 크기 제한과 secret/PII redaction을 적용한다.
- 사용자에게는 안정된 error code와 안전한 문구만 반환하고 상세 root cause는 correlation ID와 함께 서버 log에 둔다.
- trajectory 조회 권한, 보존 기간, 삭제 정책을 문서화한다.

### RISK-06 — 첨부 제한이 요청 전체에서 일관되지 않음 · 수정 완료

- 우선순위: P2
- 분류: UX·자원 사용

프론트는 파일 하나당 8 MB만 검사하고 총합·파일 수는 제한하지 않는다([`ai-chat-panel.constants.ts:39`](score-web/src/app/ai-management/ai-chat-panel/domain/ai-chat-panel.constants.ts#L39)). 서버는 Base64를 모두 수신한 뒤 decoded 총합 20 MB를 검사한다([`ChatService.java:255`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/ChatService.java#L255)). 예를 들어 8 MB 파일 3개는 UI를 통과한 뒤 서버에서 실패한다. Base64 JSON payload는 decoded 크기보다 약 33% 더 크고 브라우저/서버에 중복된다.

또한 FileReader가 비동기인데 pending read 상태를 추적하지 않는다([`ai-chat-panel.component.ts:663`](score-web/src/app/ai-management/ai-chat-panel/ai-chat-panel.component.ts#L663)). 파일 읽기 완료 전에 send/new chat을 누르면 attachment가 이번 요청에서 빠지거나 다음 상태에 늦게 추가될 수 있다.

권고: 개수·총 decoded/encoded 크기를 프론트에서 선검사하고, file-read pending 동안 send를 막거나 작업 generation을 확인한다. 장기적으로 multipart/streaming upload와 서버-side content inspection을 고려한다.

## 5. 기능·운영 개선점

### IMP-01 — availability를 실제 health로 만들기

현재 “available”은 model/provider의 key와 URL이 설정되었는지만 검사한다([`ScoreAiModelRegistry.java:120`](score-http/src/main/java/org/oagi/score/gateway/http/configuration/ai/ScoreAiModelRegistry.java#L120)). `/availability/revalidate`도 같은 boolean을 다시 반환할 뿐 provider나 MCP에 요청하지 않는다([`AiChatController.java:140`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/controller/AiChatController.java#L140)). 프론트가 기대하는 `MODEL_PROVIDER_TEMPORARILY_UNAVAILABLE` reason을 현재 서버는 반환하지 않는다.

개선안:

- configured/healthy/degraded를 구분한다.
- provider와 MCP를 짧은 timeout으로 비파괴 probe하고 결과를 잠시 cache한다.
- 모델별 상태를 `/models`에 포함해 일부 provider 장애 시 사용 가능한 모델만 노출한다.
- revalidate endpoint가 실제 probe를 시작하지 않는다면 이름을 단순 `availability` 조회로 바꾼다.

### IMP-02 — event protocol을 축소하고 schema로 고정하기

프론트는 `tool_group`, `UI_FORMATTED`, mutation confirmation, authentication failure, request error, reconciliation, model fallback 등 많은 event를 처리하지만 현재 서버가 chat 실행에서 생성하는 것은 accepted/progress/assistant update/tool call/detail/final/error/cancelled뿐이다. 반대로 cancellation DTO에는 서버가 사용하지 않는 상태와 identity 계약이 많다.

개선안:

- 실제 지원 event를 discriminated union/JSON Schema로 정의한다.
- Java DTO와 TypeScript 타입을 schema에서 생성하거나 최소한 contract fixture를 공유한다.
- 호환성 목적의 legacy event는 별도 adapter에 격리하고 제거 시점을 정한다.
- unknown event, 순서 역전, duplicate event의 정책을 protocol 문서에 명시한다.

### IMP-03 — 2,847줄 panel component 분리

`AiChatPanelComponent`는 전송, restore, cancellation, mutation confirmation, 모델 설정, attachment, scrolling, resize, history, navigation을 모두 소유한다. 테스트가 많아도 상태 조합을 추론하기 어렵고, 현재처럼 서버에서 사라진 protocol이 남기 쉽다.

개선안:

- request lifecycle을 reducer/state machine으로 분리한다.
- transport, restore, cancellation, mutation, attachment orchestration을 facade로 나눈다.
- component는 view event와 state projection만 담당한다.
- boolean 조합 대신 `idle | connecting | running | cancelling | restoring | terminal | reconciliation` 같은 명시적 상태를 사용한다.

### IMP-04 — 관측성과 비용 지표 보강

현재 trajectory의 token 합계는 유용하지만 request registry와 운영 metric이 연결되지 않는다. provider에 따라 cached token이 prompt token의 부분집합일 수 있으므로 `prompt_tokens + cache_read + cache_write` 계산도 provider semantics를 확인해야 한다([`AiTrajectoryRecorder.java:222`](score-http/src/main/java/org/oagi/score/gateway/http/api/ai_management/service/AiTrajectoryRecorder.java#L222)).

권장 metric:

- request 전체/첫 token/model call/MCP tool별 latency
- active/queued/cancelled/timed-out request 수
- prompt/completion/cache token과 추정 비용
- tool 성공률, mutation 수, reconciliation-required 수
- attachment bytes, history/trajectory payload bytes
- provider/MCP별 circuit-breaker 상태

## 6. 권장 수정 순서와 완료 조건

### P0 — 운영 전 필수 · 완료

1. `AiMutationToolGuard`가 MCP callback을 감싸며 현재 read 도구인 `get_*`, `who_am_i`만 allowlist한다. 미래/미분류 도구는 자동으로 mutation으로 취급한다.
2. `AiMutationConfirmationService`와 `ai_chat_mutation_confirmation`이 `REQUESTED → APPROVED/DENIED → CONSUMED/EXPIRED` 상태를 관리한다. grant 원문은 응답에서 한 번만 전달되고 DB에는 SHA-256 digest만 저장한다.
3. grant는 owner, conversation, tool name, canonical JSON arguments에 묶인다. 승인 후 follow-up은 의도적으로 새 request ID를 사용하므로 원래 request ID가 아니라 256-bit one-time grant로 재실행을 결합한다.
4. `AiRequestRegistry` scheduler가 설정된 deadline을 집행한다. queued work는 시작되지 않고, running work는 interrupt 후 실제 종료까지 `CANCELLING`을 유지한다.
5. final memory/trajectory 저장은 `commitResult()`로 cancellation/deadline과 원자적으로 fence한다. mutation이 관찰된 요청은 취소·timeout 시 reconciliation terminal이 된다.
6. page context, attachment, tool result를 untrusted data로 선언했고 attachment는 JSON data block으로 직렬화한다. `/compact` summary도 system instruction이 아닌 assistant reference memory로 저장한다.

### P1 — 안정화 항목 · 이번 범위 완료

1. archive MIME 지원 표기를 제거하고 명시 MIME가 unsupported인 파일은 확장자로 우회하지 못하게 했다.
2. page snapshot은 매 turn 제공한다.
3. `/compact`는 tool callback을 노출하지 않는 요약 turn으로 구현했다.
4. registry TTL/cap과 conversation single-active rule을 추가했다.
5. history/trajectory 응답 상한, 90일 conversation retention, 만료 grant cleanup을 추가했다.
6. 사용자 오류는 allowlist된 validation만 노출하고 provider/MCP/DB root cause는 server log에만 남긴다.

남은 운영 항목은 §5의 IMP-01 provider/MCP 실 health probe, IMP-02 schema 공유, IMP-03 panel 분리, IMP-04 telemetry다. 이는 이번에 요청한 BUG/RISK 13건의 blocker는 아니다.

### P2 — 유지보수성과 UX

1. event schema 공유와 dead protocol 제거
2. panel state machine/facade 분리
3. attachment read 상태와 validation 개선
4. 비용·latency·tool telemetry dashboard
5. 설정·복원·취소·첨부에 대한 접근성/E2E 시나리오 추가

## 7. 테스트 결과와 누락

### 7.1 최종 실행 결과

모든 명령은 최종 변경분에서 통과했다.

```text
score-http 전체: 583 tests, failures 0, errors 0, skipped 0
score-web 전체: 162 files / 854 tests, failures 0
score-http package: 성공
score-web production build: 성공
score-web lint: 성공
git diff --check: 성공
MariaDB 10.11 migration/제약조건 검증: 성공
Claude 독립 재검토 §8의 partial 2건·P2 3건: 후속 수정 완료
```

주요 명령:

```bash
cd score-http && ./mvnw -q test
cd score-http && ./mvnw -q -DskipTests package
cd score-web && npm test
cd score-web && npm run build
cd score-web && npm run lint
```

MariaDB 10.11 임시 컨테이너에는 최소 `app_user` 선행 테이블을 만든 뒤 `V3_6_0__upgrade_from_3_5_2.sql` 전체를 적용했다. confirmation insert, `ORDER BY ... LIMIT 1 FOR UPDATE`, status `CHECK`의 invalid-state 거부까지 확인했다. 애플리케이션 전체 context test는 실제 로컬 MariaDB 11.8과 Redis에서 Flyway 3.6.0 상태 및 모든 Spring bean 생성을 확인했다.

### 7.2 적대적 검증에서 추가로 발견·수정한 항목

1. 동사 allowlist에 없던 실제 MCP mutation `change_*`, `cancel_*`, `revise_or_amend_*` 우회를 발견했다. write 동사 열거를 폐기하고 read-only allowlist 기반 fail-closed 분류로 변경했다.
2. 승인 dialog가 변경 대상 없이 generic 문구만 표시하던 문제를 발견했다. exact tool name과 bounded/redacted canonical arguments summary를 추가했다.
3. 취소가 final response 검사 직후 도착하면 assistant memory가 저장될 수 있는 TOCTOU를 발견했다. `commitResult()`가 terminal 전환과 final persistence를 같은 registry lock으로 묶도록 수정했다.
4. 사용자가 먼저 cancel한 요청이 나중 deadline에 의해 `TIMED_OUT`으로 재분류될 수 있는 경쟁 조건을 막았다.
5. `/compact`의 model-generated summary를 system message로 저장하면 간접 지시가 system 권한을 얻는 문제를 발견했다. assistant reference message로 낮췄다.
6. JSON의 `Authorization: Bearer ...` 값에서 기존 regex가 첫 단어만 가릴 수 있는 문제를 수정하고 structured JSON redaction과 전체 크기 상한을 적용했다.
7. 전체 context test가 runtime의 다중 생성자 때문에 Spring bean을 만들지 못하는 회귀를 발견했다. production 생성자에 명시적 `@Autowired`를 추가한 뒤 전체 테스트를 재통과했다.
8. Claude 리뷰가 지적한 stale approval row는 scheduled cleanup에서 `EXPIRED` 처리하고 grant digest를 제거하도록 보강했다.
9. 후속 독립 검증이 발견한 REST confirmation event 유실을 응답 계약과 strict 프론트 파서로 수정했다. 첨부가 포함된 승인 repeat도 원래 draft를 정확히 보존한다.
10. REST `TIMED_OUT`도 WebSocket과 동일한 terminal message로 trajectory에 기록한다.
11. deadline 작업을 scheduler 밖 가상 스레드로 dispatch하고, interrupt 무시 worker를 30초 뒤 보수적으로 reconciliation terminal 처리하는 watchdog을 추가했다.
12. History 대화 전환 시 draft prompt와 attachments를 attachment-read generation과 함께 초기화한다.
13. 승인 알림과 trajectory가 동일한 민감정보 redactor를 사용하도록 통합하고 문자열 leaf 회귀 테스트를 추가했다.
14. Claude 재검토 §8의 Base64 빈 이름과 과다 redaction을 수정하고, REST terminal 오류 body에도 strict confirmation event를 보존했다.
15. watchdog의 stale worker 참조를 제거하고 lifecycle task 예외를 기록하는 데 그치지 않고 보수적 reconciliation terminal로 전환한다.
16. 인접 적대 검토에서 발견한 duplicate start, scheduler 등록 실패 누수, 즉시 deadline future 경쟁, 과도한 첨부명, 잘못된 image MIME 오류 계약을 추가로 수정했다.

### 7.3 Claude 리뷰 상태

전용 `claude_review` 스킬은 환경에 없어 최초 수정에서는 로컬 Claude CLI를 read-only reviewer로 실행했고 **“No blocker remains”** 판정을 받았다. 하지만 이후 더 넓은 독립 재검증은 이 판정을 뒤집고 partial 3건과 신규 P1 4건을 발견했다. 세부 근거는 `AI_CHAT_REVIEW_VERIFICATION_2026-07-17.md`에 보존했다.

7건을 후속 수정한 diff에 대해 로컬 Claude CLI 재리뷰는 인증 401로 실패했지만, 이후 Claude Code 세션의 영역별 리뷰 + 적대적 재검증 파이프라인(18개 에이전트)이 실제 테스트, 코드 되돌리기, `jstack` 재현을 포함해 검토했다. 결과는 blocker 없음, BUG-04·RISK-05 partial과 P2 3건이었다. 이 다섯 항목은 추가 수정했고 결과를 `AI_CHAT_REVIEW_VERIFICATION_2026-07-17.md` §9에 기록했다.

Claude가 남긴 세 가지 medium/follow-up 검토 결과:

- grant가 원래 request ID가 아니라 conversation/tool/arguments에 결합됨: 승인 후 follow-up이 새 request ID를 쓰는 프론트 계약상 의도된 설계다. 코드 주석으로 명시했다.
- 새 turn이 동일 tool/arguments confirmation row를 재사용하지 않음: 각 새 시도에 새 승인이 필요하므로 의도된 fail-closed 동작이다. 만료 row 상태는 scheduled cleanup한다.
- cancel identity 한쪽만 제공하면 stale로 거부됨: 프론트도 두 값을 함께 강제하며 서버가 partial legacy command를 fail-closed로 거부하도록 명시했다.

### 7.4 남은 검증 한계

- 실제 외부 Anthropic/OpenAI provider와 실제 connect-center MCP를 사용한 브라우저 E2E는 수행하지 않았다.
- Java interrupt가 외부 SDK 내부 작업을 강제로 종료한다고 가정하지 않는다. worker가 30초 안에 멈추지 않으면 watchdog이 reconciliation-required로 terminal 처리하고 이후 persistence/mutation 시작을 fence한다. 외부 호출 스레드 자체는 JVM이 강제 종료할 수 없으므로 provider별 socket/operation timeout은 여전히 필요하다.
- request lifecycle의 transport-visible 상태, 사용자/대화별 admission, generation, deadline, 취소, terminal, mutation fence는 Redis 공유 상태와 분산 락으로 관리한다. 프로세스 로컬에는 다른 인스턴스로 이전할 수 없는 실행 핸들(`Thread`, `ScheduledFuture`)만 남고, 원격 취소는 Redis pub/sub으로 소유 인스턴스에 전달된다. Redis 자체의 HA·백업·운영 모니터링은 배포 환경에서 보장해야 한다.
- history/trajectory는 응답 크기를 제한했지만 UI의 “더 불러오기” pagination은 별도 UX 과제다.

## 8. 최종 판단

최초 검토에서 운영 blocker로 분류한 mutation server authority 부재, 표시뿐인 deadline, 조기 terminal cancellation, indirect prompt injection/write-tool 결합은 해소됐다. 후속 독립 검증의 partial 3건과 신규 P1 4건도 코드·단위/통합 테스트·전체 애플리케이션 context로 재검증했다.

현재 자체 검증과 수정 후 Claude 독립 재검토에서 확인된 blocker는 없고, 검증 문서 §8~§10에 열거된 partial/P2 잔여도 모두 닫았다. 실제 provider/MCP E2E, history pagination UI, health/telemetry와 Redis HA 운영 검증은 운영 규모에 맞춰 이어서 진행해야 한다.

## 9. P2 및 다중 인스턴스 후속 수정

검증 문서의 P2 5~16을 모두 코드와 회귀 테스트로 수정했다. 실제 DB를 사용하는 mutation 승인 상태 전이, 승인 만료 정리 주기, fail-closed tool 분류, controller admission 경쟁, 실제 registry persistence fence, 안전한 tool 오류 기록, compacted 플래그 복원, 결정적 retention cutoff, FileReader/recovery 경쟁, attachment accept 단일 소스, 최소 이벤트 계약, 취소 enum 정합성을 포함한다.

AI request registry에서 공유해야 하는 수명주기 상태는 모두 Redis로 이전했다. 분산 전역/요청 락으로 request ID·사용자 cap·대화 단일 활성 요청·maintenance admission을 원자적으로 보호하고, 실제 Redis codec/pub-sub 통합 테스트와 두 registry 인스턴스의 원격 조회·취소·owner 소실 reconciliation 테스트를 추가했다. 시작 시 무조건 실행되던 `FLUSHALL`은 공유 상태를 파괴하지 않도록 기본 비활성화하고 명시적 maintenance 설정에서만 허용한다.

추가 전역 검색에서 확인한 GitHub Projects refs의 프로세스 로컬 캐시도 Redis `RBucket` 30분 TTL과 좌표별 Redisson 분산 락으로 이전했다. 실제 Redis에서 서로 다른 서비스 인스턴스가 동일 결과를 공유하고 GitHub 조회를 한 번만 수행하는 통합 테스트를 추가했다.

최종 검증은 `score-http` 583 tests, `score-web` 162 files/854 tests, backend package, frontend production build/lint, `git diff --check`까지 모두 통과했다. 상세 항목은 `AI_CHAT_REVIEW_VERIFICATION_2026-07-17.md` §10에 기록한다.
