# AI Chat 하드닝 커밋(31d4542a3) 독립 재검증

> 이 문서의 §1~§6은 `31d4542a3` 검증 당시의 스냅샷이다. 발견 사항에 대한 후속 수정 상태는 §7에 기록한다.

- 검토일: 2026-07-17
- 대상 커밋: `31d4542a3` (`fix(ai): harden chat lifecycle and mutation safety`), 브랜치 `develop_spring_ai_chat`
- 대상 문서: 같은 커밋에 포함된 `AI_CHAT_REVIEW_2026-07-17.md` (BUG 7건 + RISK 6건, 전부 "완료" 표기, "Claude 독립 리뷰: blocker 없음")
- 방법론: 5개 서브시스템(§6 참고)에 대해 리뷰 에이전트가 각 claim을 실제 최신 소스(HEAD, 작업 트리와 동일)로 재검증하고 신규 버그도 자유 탐색 → 그 결과(claim 판정 13건 + 신규 발견 22건, 총 35건)를 각각 별도의 적대적 검증 에이전트가 "반박 시도"하는 2단계 구조. 총 41개 에이전트, 도구 호출 1,073회.
- 이 문서 자체도 하나의 검증 시점 스냅샷이다. `AI_CHAT_REVIEW_2026-07-17.md`의 "완료" 표기가 실제 코드와 어긋나는 지점, 그리고 그 문서가 다루지 않은 이 커밋의 신규 회귀를 기록하는 것이 목적이다.

## 1. 결론

13건의 claim 중 **10건은 재검증에서 그대로 "완료"로 확인**됐고, **3건(BUG-01, BUG-04, RISK-05)은 "완료"가 과장**되어 있다 — 핵심 메커니즘은 맞게 구현됐지만 문서가 닫혔다고 말한 구멍 중 최소 하나는 실제로 열려 있다. 또한 이번 커밋이 직접 만들어낸 **신규 P1급 회귀 4건**이 원 문서 어디에도 언급되지 않은 채 발견됐다. 반대로 검증 과정에서 제기됐던 P2급 의혹 2건(동시성 issue() 중복 행, cancel identity 우회)은 실제 MariaDB 동시성 테스트와 코드 추적으로 **반박**되어, 원 문서의 해당 부분이 오히려 필요 이상으로 보수적이었다는 것도 확인했다.

즉 "mutation은 서버 권위(server-authoritative)"라는 커밋의 핵심 주장은 **DB 상태 기계 자체는 사실**이지만, **HTTP(첨부 포함) 경로에서는 그 상태 기계로 가는 알림이 유실**되어 승인 UI가 뜨지 않는 구멍이 남아 있다. 이는 별도 보안 우회는 아니고(fail-closed는 유지) UX/신뢰성 결함이지만, 문서의 "완료" 판정과는 다르다.

### 1.1 Claim별 재검증 요약

| ID | 원 판정 | 재검증 | 핵심 근거 |
|---|---|---|---|
| BUG-01 | 완료 | **partial** | DB 상태 기계(REQUESTED→APPROVED/DENIED→CONSUMED/EXPIRED, one-time grant, owner+conversation+tool+args 결합)는 실제로 견고하다. 그러나 `AiChatController.java:100`의 REST `POST /ai/chat`이 `chatService.chat(prepared, requester, ignored -> {})`로 이벤트 consumer를 no-op으로 넘겨, 첨부가 있는 turn이나 승인 후 follow-up turn에서 `mutation_confirmation_required` 알림이 클라이언트에 전혀 도달하지 않는다. 도구 실행은 fail-closed로 계속 차단되지만, 사용자는 승인/거부 UI를 볼 방법이 없다. |
| RISK-01 | 완료 | **fixed** | `AiMutationToolGuard`가 모든 MCP tool을 예외 없이 감싸고, `get_*`/`who_am_i`만 read-only 허용. 실제 MCP 도구 카탈로그(`connect-center-api/app/tools/*.py`)와 대조해 우회 가능한 tool이 현재 없음을 확인. attachment/page context 모두 untrusted-data로 명시. |
| BUG-02 | 완료 | **fixed** | 설정 기반 단일 deadline이 실제 `ScheduledExecutorService`로 집행되고, provider 호출까지 실제로 interrupt됨을 Reactor 코드까지 추적해 확인. (다만 §2의 신규 발견 2건이 이 보장을 약화시키는 잔여 위험으로 남음) |
| BUG-03 | 완료 | **fixed** | `CANCELLING`은 비-terminal 상태로 분리되어 있고, 실제 worker 종료 후에만 terminal 전환. 취소로 인한 `recordFailure()` 오발생 경로 없음을 확인. |
| BUG-07 | 완료 | **fixed** | generation이 요청마다 실제 무작위 값으로 발급되고 cancel 시 검증됨. (관련 P2 의혹은 반박됨 — §3 참고) |
| BUG-04 | 완료 | **partial** | archive 지원 표기 제거와 client/server MIME 정합은 실제로 닫혔다. 그러나 "서버의 실제 검증 메시지가 사용자에게 노출된다"는 세 번째 claim은 이번 커밋이 손대지 않은 부분이라 여전히 거짓 — `ai-chat-panel.component.ts`의 에러 핸들러는 `error: () => {...}`로 응답 바디를 아예 읽지 않고 커밋 이전과 동일한 제네릭 문구를 그대로 띄운다. |
| BUG-05 | 완료 | **fixed** | page snapshot이 매 turn 무조건 전송되도록 게이팅 로직 자체가 제거됨을 확인. |
| BUG-06 | 완료 | **fixed** | `/compact`가 실제로 tool-free 요약 turn을 만들고, harness 레벨(`AbstractSpringAIRuntime`)에서 도구 바인딩 자체를 차단하며, `compacted` 플래그가 실제 컬럼에 저장됨. |
| RISK-02 | 완료 | **fixed** | TTL(30분)/cap(10,000/8) 모두 실제 코드로 확인. (단, 멈춘 worker에 대한 워치독 부재는 별도 신규 발견으로 기록) |
| RISK-03 | 완료 | **fixed** | conversation 단일 active turn 강제와 `commitResult()`의 락 기반 원자적 fencing을 실제 상태 전이 추적으로 확인. |
| RISK-04 | 완료 | **fixed** | list/restore/trajectory 모두 SQL `LIMIT`으로 실제 제한되고, FK `ON DELETE CASCADE`로 90일 보존이 자녀 테이블까지 정상 전파됨을 마이그레이션 파일로 직접 확인. |
| RISK-05 | 완료 | **partial** | reasoning 미저장, 32KiB cap, JSON key 기반 redaction, `safeMessage()` 안전화는 모두 실제로 맞다. 그러나 자유 텍스트(비-JSON) fallback 정규식과 JSON 문자열 leaf 재귀 처리 모두 `cookie`/`session`/`credential`/단독 `token`/`auth`를 잡지 못해 그대로 저장될 수 있고, `AiMutationConfirmationService`가 별도로 들고 있는 더 약한 `redact()`(문자열 leaf 위생 처리 없음)가 브라우저로 실시간 push되는 승인 알림에 쓰인다. |
| RISK-06 | 완료 | **fixed** | 8/20 MiB·10개 제한이 client/server 양쪽에 동일하게 걸려 있고, FileReader generation fencing이 실제 경쟁 조건 테스트로 검증됨. (단, §2에 신규 유사 경쟁 조건 1건 발견) |

## 2. 이번 커밋이 새로 만든 회귀 (원 문서에 없음)

이 항목들은 BUG/RISK 13건과 무관하게, 이번 재검증 과정에서 각 서브시스템 리뷰어가 자유 탐색으로 찾아내고 별도 에이전트가 반박을 시도했으나 살아남은 것들이다.

### P1

1. **REST `/ai/chat`가 `TIMED_OUT` 종료에 대한 감사(trajectory) 기록을 유실한다** — `AiChatController.java:102`. 커밋 이전 코드는 `throwable != null`이면 무조건 `recordFailure()`를 호출했지만, 이번 리팩터링이 `"FAILED".equals(status)`로 조건을 좁히면서 `TIMED_OUT` 종료가 REST 경로에서는 조용히 기록되지 않게 됐다. WebSocket 경로는 `else` 분기가 `TIMED_OUT`을 여전히 잡아 기록하므로 두 전송 방식 간 비대칭이 생겼다(단, `CANCELLED`/`UNKNOWN_RECONCILIATION_REQUIRED`는 둘 다 기록하지 않아 대칭). `AiChatController` 테스트 자체가 없어 이 회귀를 잡을 테스트가 없다.
2. **`commitResult()`가 단일 스레드 lifecycle scheduler를 정지시킬 수 있다** — `AiRequestRegistry.java:187`. `commitResult()`는 entry 락을 쥔 채 실제 `@Transactional` DB 쓰기를 수행하는데, 같은 entry의 deadline task가 하필 같은 시점에 그 락을 기다리면 유일한 scheduler 스레드가 막혀 다른 모든 활성 요청의 deadline 집행(BUG-02)이 그 DB 쓰기가 끝날 때까지 지연된다.
3. **멈춘(interrupt를 무시하는) worker를 강제로 terminal 처리할 워치독이 없다** — `AiRequestRegistry.java:140`. `interrupt()`가 실제로 안 먹히는 provider/tool 호출이 있으면 해당 entry는 영원히 `CANCELLING`에 머물러 사용자당 8개, 전역 10,000개 슬롯(RISK-02) 중 하나를 서버 재시작 전까지 영구 점유한다.
4. **History에서 다른 대화로 전환해도 첨부/입력 중 프롬프트가 지워지지 않는다** — `ai-chat-panel.component.ts:2202`. 대화 A에서 파일을 첨부한 채(또는 텍스트를 입력한 채) History 탭에서 무관한 대화 B를 열면 `prepareConversationRestore()`가 `state.attachments`/`state.prompt`를 초기화하지 않아, B에서 첫 메시지를 보낼 때 A의 첨부가 그대로 딸려간다. `resetForNewChat()`은 이 두 필드를 명시적으로 비우는 것과 대조적이다.

### P2

5. `AiMutationConfirmationServiceTest`가 순수 helper만 테스트하고 실제 DB 상태 기계(`FOR UPDATE` 락, 상태 전이)를 전혀 실행하지 않는다.
6. mutation confirmation 정리 주기(기본 24h)가 10분 TTL보다 훨씬 길어, 만료된 `grant_digest` 해시가 최대 하루 가까이 테이블에 남는다(악용 불가, 위생 문제).
7. `READ_ONLY_NAME` 정규식에 죽은 대안이 있고 대소문자 무시라서, 향후 `get_`로 시작하는 변경 도구가 생기면 read-only로 오분류될 잠재적 취약성이 있다(현재는 해당 도구 없음).
8. `AiChatController`의 조기 `hasActiveConversation()` 체크와 registry의 원자적 `register()` 사이에 TOCTOU가 있어, 같은 대화에 대한 거의 동시 전송 2건이 모두 `prepare()`의 영속화 부작용(설정 변경 감사 행)을 실행한 뒤에야 하나만 최종 승인된다 — 거부된 쪽의 행은 롤백되지 않고 남는다.
9. `ChatServiceTest`가 항상 `AiRequestRegistry`를 `null`로 생성해, 이번 커밋이 도입한 `commitResult()`/`shouldDiscardResult()` fencing이 실제 `chat()` 경로를 통한 단위 테스트로는 전혀 검증되지 않는다.
10. `AiTrajectoryRecorder`의 tool 실패 메시지가 `safeMessage()`의 엄격한 allowlist를 거치지 않고 secret 패턴만 걸러진 채 거의 원문 그대로 저장된다.
11. `compacted` 플래그가 한 번 1이 되면 이후 대화가 다시 커져도 리셋되지 않는다(현재 프론트가 이 필드를 아무 데도 쓰지 않아 잠재적 결함).
12. `AiChatRetentionServiceTest`가 실제 만료 기준 시각(cutoff)을 캡처/검증하지 않아 `now.minus`↔`now.plus` 같은 부호 회귀를 잡지 못한다.
13. `recoverActiveRequest()`가 FileReader 읽기 도중 `state.pending`을 `true`로 바꿀 수 있어, RISK-06이 막으려던 것과 같은 종류의 "첨부 조용히 유실"이 generation 불일치가 아닌 다른 경로로 재현된다.
14. 파일 선택창의 `accept` 속성이 상수와 별도로 손으로 중복 관리되며 이를 검증하는 테스트가 없다.
15. `AiChatSocketEvent`가 21개 필드를 가진 단일 공유 레코드이고 프론트의 mutation-confirmation 파서가 정확히 그 21개 키의 allowlist를 하드코딩하고 있어, 향후 다른 이벤트 타입에 필드가 추가되면 이 알림이 조용히 무반응(fail-closed) 상태가 될 수 있다 — 계약 테스트 없음.
16. cancel disposition의 프론트/백엔드 enum 드리프트(`COMMAND_MISMATCH`/`NOT_FOUND`/`RECONCILIATION_REQUIRED`/`OUTCOME_UNKNOWN`)가 이번 커밋에서도 해소되지 않고 그대로 남음(fail-safe 방향이라 위험하지는 않음, pre-existing).

## 3. 검증 과정에서 반박된 항목 (원문에 없던 의혹이 실제로는 사실무근)

두 건은 리뷰 에이전트가 독립적으로(다른 그룹에서 각각) 제기했지만, 적대적 검증에서 실제 코드 동작과 다르다고 반박됐다. 오히려 이 시스템이 처음 생각보다 더 견고하다는 뜻이라 명시적으로 기록한다.

- **"`AiMutationConfirmationService.issue()`가 동시 tool 호출 시 중복 REQUESTED 행을 만들 수 있다"** — 실제 MariaDB 10.11 컨테이너에 동일 스키마를 만들고 진짜 동시 트랜잭션으로 재현을 시도했으나, `existing` 조회 쿼리가 `ai_chat_conversation`에 JOIN하며 `FOR UPDATE`를 걸기 때문에 같은 대화의 두 트랜잭션은 그 부모 행에서 직렬화되고, 두 번째 트랜잭션은 첫 번째가 커밋한 행을 정확히 재발견한다. 18/18 반복 시행에서 중복 0건. (스키마에 유니크 제약이 없다는 위생상 지적 자체는 남지만, "중복이 실제로 발생한다"는 핵심 주장은 거짓.)
- **"cancel이 conversationId/expectedGeneration을 둘 다 생략하면 신원 검증 없이 취소된다"** — 코드상 그 필드 검증 블록이 스킵되는 것은 사실이지만, 그 앞의 `owned(requestId, requester)` 호출이 이미 세션 인증 기반의 실제 권한 검증(Access gate)이며 이는 무조건 실행된다. 생략 가능한 필드는 "이게 내가 아는 그 turn이 맞나"를 확인하는 부가적 staleness 가드일 뿐, 소유권 검증 자체를 우회하지 않는다.

## 4. 권고

- **BUG-01 REST 경로 알림 유실을 우선 처리**: 첨부가 있는 turn과 승인 후 follow-up turn(둘 다 REST를 강제 사용)에서도 `mutation_confirmation_required`가 클라이언트에 도달하도록, REST 응답에도 확인 알림을 실어 보내거나 해당 turn도 WebSocket으로 통합해야 커밋의 핵심 주장("서버가 이제 실제로 알림을 발생시킨다")이 예외 없이 성립한다.
- **RISK-05 redaction 키워드 확장**: `cookie`, `session`, `credential`, 단독 `token`/`auth`를 자유 텍스트 fallback과 `AiMutationConfirmationService`의 별도 `redact()`(문자열 leaf 포함)에 모두 추가. 특히 후자는 DB가 아니라 브라우저로 즉시 push되는 경로라 우선순위가 높다.
- **BUG-04 세 번째 항목**: 첨부 실패 시 서버의 실제 검증 메시지(이미 `X-Error-Message` 헤더로 내려오고 있음)를 채팅 에러 핸들러가 읽어 표시하도록 한 줄 수정.
- **신규 P1 4건**: TIMED_OUT 감사 기록 REST/WS 대칭화, `commitResult()`를 락 밖에서 영속화하도록 재구성(또는 별도 스케줄러 스레드 분리), 멈춘 worker에 대한 하드 워치독/`orTimeout` 추가, 대화 전환 시 `state.attachments`/`state.prompt` 초기화.
- **테스트 갭 메우기**: `AiMutationConfirmationServiceTest`/`ChatServiceTest`가 실제 DB·실제 registry를 통해 상태 기계를 exercise하도록 보강 — 현재는 회귀가 나도 그린으로 통과한다.

## 5. 이 재검증의 한계

- 코드 추적·grep·실제 MariaDB 동시성 재현·unit test 실행까지 수행했지만, 실제 외부 Anthropic/OpenAI provider·실제 connect-center MCP·브라우저 E2E는 포함하지 않았다(원 문서도 동일 한계를 인정함).
- 각 claim/finding은 서브시스템별로 나뉜 리뷰어가 처음 제기하고 별도 에이전트가 1회 반박을 시도하는 구조였다 — "완료"로 재확인된 항목에도 다수결 합의가 아닌 단일 반박 시도만 거쳤으므로, 절대적 증명이 아니라 원 문서보다 한 겹 더 회의적인 시각의 재확인으로 봐야 한다.

## 6. 방법론 세부사항

5개 서브시스템 그룹, 각각 (1) 담당 파일 전체를 읽고 원 문서의 관련 claim을 실제 코드로 검증 + 자유 탐색으로 신규 버그 최대 5건 보고 → (2) 나온 모든 claim 판정과 신규 발견 각각에 대해 독립된 적대적 검증 에이전트가 반박을 시도하는 2단계 파이프라인으로 실행했다(Workflow 도구, `wf_fd3c346b-a10`).

| 그룹 | 범위 |
|---|---|
| mutation-guard | `AiMutationConfirmationService`, `AiMutationToolGuard`, 관련 DTO/migration/test, 프론트 `ai-mutation-confirmation.ts` |
| lifecycle-concurrency | `AiRequestRegistry`, `AiChatController`, `ChatService`, `ScoreAiConfiguration/Properties`, 관련 test |
| memory-retention-redaction | `ChatService`(/compact), `ScoreChatMemoryRepository`, `AiChatRetentionService`, `AiTrajectoryRecorder`, 관련 test |
| frontend-attachment-context | 첨부 MIME/크기 상수·서비스, `ai-chat-context.service`, `ai-chat-panel.component` |
| contract-consistency | FE/BE 이벤트·DTO 계약, `V3_6_0__upgrade_from_3_5_2.sql` — claim 없이 순수 신규 버그 탐색 |

에이전트 원본 응답 전문은 `journal.jsonl`에 남아 있다(세션 로컬 경로, 재현 필요 시 `resumeFromRunId: wf_fd3c346b-a10`로 재실행 가능).

## 7. 후속 수정 상태

독립 재검증에서 살아남은 partial 3건과 신규 P1 4건은 다음과 같이 후속 수정했다.

| 항목 | 후속 상태 | 구현·검증 |
|---|---|---|
| BUG-01 REST 알림 유실 | **수정 완료** | REST `ChatResponse.events`가 mutation confirmation만 전달하고 프론트가 기존 strict notice parser로 검증한다. attachment turn과 승인 후 두 번째 mutation follow-up 회귀 테스트를 추가했다. |
| BUG-04 실제 첨부 오류 미표시 | **수정 완료** | HTTP 400이면서 서버의 알려진 attachment validation 문구와 정확히 일치하는 `X-Error-Message`만 표시한다. 임의의 500/internal header는 generic 문구로 유지한다. |
| RISK-05 redaction 누락 | **수정 완료** | audit와 confirmation summary가 공통 redactor를 사용한다. `cookie`, `session`, `credential`, 단독 `token`, `auth`와 문자열 leaf를 검증한다. |
| REST `TIMED_OUT` 감사 유실 | **수정 완료** | REST 완료 처리도 `FAILED`와 `TIMED_OUT`을 동일하게 `recordFailure()`에 기록한다. controller 회귀 테스트가 deadline message를 검증한다. |
| lifecycle scheduler head-of-line blocking | **수정 완료** | deadline/watchdog callback을 scheduler에서 즉시 가상 스레드로 dispatch한다. `commitResult()`의 원자적 lock은 유지하면서 다른 entry의 deadline이 DB persistence 대기와 독립적으로 실행됨을 경쟁 테스트로 검증했다. |
| interrupt 무시 worker 워치독 부재 | **수정 완료** | 30초 stop watchdog이 `UNKNOWN_RECONCILIATION_REQUIRED`/`WORKER_STOP_TIMEOUT`으로 terminal 처리하고 이후 commit을 fence한다. active slot 해제와 late finish를 테스트했다. |
| History 전환 draft 누수 | **수정 완료** | `prepareConversationRestore()`가 prompt와 attachments를 비우며 pending FileReader generation도 먼저 invalidate한다. |

최종 자체 검증 결과는 backend 524 tests, frontend 162 files/845 tests, backend package, frontend production build/lint, `git diff --check` 통과다. 전용 `claude_review` 스킬은 환경에 없어서 로컬 Claude Code read-only 재리뷰를 시도했지만, 인증 endpoint가 401을 반환해 **수정 후 Claude 판정만 대기** 상태다.

## 8. Claude 독립 재검토 (후속 수정 7건)

- 검토일: 2026-07-17 (§7 이후, 대기 중이던 "Claude 판정"에 해당)
- 방법: (1) 실제 `./mvnw test`(524)와 `npm test`(845) 재실행으로 §7의 수치를 직접 재현·확인, (2) `git diff HEAD`로 7건 각각의 실제 코드 변경을 직접 판독, (3) 3개 영역(REST 알림/안전 오류, scheduler/watchdog, redaction/대화전환)에 대해 영역별 리뷰 에이전트 + claim/finding별 적대적 재검증 에이전트의 2단계 파이프라인(18개 에이전트) 실행. 코드 판독뿐 아니라 실제 실행(테스트 재실행, 일부는 `jstack`을 이용한 스레드 덤프, 실제 pre-fix 상태로의 되돌리기 후 재현)까지 동반한 검증이 다수 포함됐다.

### 8.1 결론

7건 모두 **실제로 구현되어 있고, 해당 결함의 재현 시나리오를 겨냥한 실제 테스트(mock 없이 실제 경쟁 조건·실제 정규식 입력·실제 HTTP 오류 형태를 구성)로 뒷받침된다.** 다만 그중 2건은 완전히 닫힌 것은 아니며, 검증 과정에서 이번 수정이 새로 만든 P2급 잔여 이슈 3건도 발견됐다.

| 항목 | 판정 | 근거 |
|---|---|---|
| BUG-01 REST 알림 유실 | **수정 확인** | `AiChatController` REST `chat()`이 `mutation_confirmation_required` 이벤트를 WS와 동일한 `socketEvent()` 헬퍼로 만들어 `ChatResponse.events`에 담고, 프론트가 기존 strict validator로 검증 후 승인 다이얼로그를 연다. 첨부 재전송·승인 후 2차 알림 재현 테스트 통과(신규 `AiChatControllerTest` 2/2, 프론트 관련 spec 145/145). 검증 중 이 파일이 "원래대로 되돌아간 것처럼" 보인 순간이 있었으나, 이는 §8.3의 이유로 워크플로우 자체의 부작용이며 실제 코드 결함이 아니다. |
| BUG-04 실제 첨부 오류 미표시 | **부분 수정** | 서버가 실제로 던지는 8개 첨부 검증 메시지 중 7개는 프론트 allowlist 정규식과 정확히 일치한다. 다만 "Base64 디코딩 실패" 메시지만 서버가 `safeName()` fallback 없이 원문 `attachment.name()`을 그대로 붙이므로, 첨부 이름이 빈 문자열이면 정규식이 매치하지 않아 실제 메시지 대신 generic 문구로 폴백한다(정보 노출은 아니며 안전한 방향의 실패). |
| RISK-05 redaction 누락 | **부분 수정 — 새로운 과다 redaction 발생** | `cookie`/`session`/`credential`/단독 `token`/`auth`를 이제 audit 기록과 실시간 승인 알림 양쪽에서 공통 로직으로 잡아낸다(원래 결함은 실제로 닫힘). 그러나 단독 `auth`/`session` 키워드가 단어 경계 없이 부분 문자열로 매치되어, `authorId`/`authorName`/`isAuthenticated`/`coAuthor`/`sessionCount`/`sessionDurationMinutes` 같은 무관한 필드까지 감사 로그와 승인자에게 보이는 실시간 알림 양쪽에서 통째로 `[REDACTED]`로 가려진다. 자유 텍스트 쪽도 "session:"/"token:" 형태의 일반 문장을 오탐지한다. 보안(과소 redaction)은 개선됐지만 투명성(mutation 승인 알림의 정확한 정보 제공)이라는, 이 기능이 원래 지키려던 다른 목표를 새로 훼손한다. |
| REST `TIMED_OUT` 감사 유실 | **수정 확인** | REST·WS 모두 동일한 `terminalMessage()`로 `FAILED`/`TIMED_OUT`을 기록한다. 실제 25ms deadline + interrupt를 무시하는 워커로 재현하는 신규 컨트롤러 테스트가 통과한다(2회 재실행 확인). "throwable 체크 제거로 성공 응답과 실패 기록이 동시에 남을 수 있다"는 추가 의혹은, `shouldDiscardResult()`/`commitResult()`가 같은 entry 락으로 성공 응답 반환과 TIMED_OUT 전환을 상호 배타적으로 만들기 때문에 반박됨(실제로 재현 불가능한 경로임을 코드 추적으로 확인). |
| lifecycle scheduler 정체 | **수정 확인** | 단일 스레드 scheduler는 이제 `Thread.startVirtualThread`로 실제 작업을 즉시 위임하고 자신은 절대 entry 락에서 블록되지 않는다. 검증 에이전트가 직접 수정 전 코드로 되돌려 동일 테스트를 실행해 실제로 2분 이상 멈추는 것을 `jstack` 스레드 덤프로 확인했고, 수정된 코드로는 0.5초 내 통과함을 재확인했다 — 가장 강도 높게 검증된 항목이다. |
| worker watchdog 부재 | **수정 확인** | interrupt를 무시하는 워커를 30초 후 `UNKNOWN_RECONCILIATION_REQUIRED`/`WORKER_STOP_TIMEOUT`으로 강제 terminal 처리하고, `registry.active(user)`로 슬롯이 실제로 해제됨을 확인했다. 이후 워커가 실제로 반환해도 `finish()`가 상태를 덮어쓰지 않아 안전하다. |
| History 전환 draft 누수 | **수정 확인** | `prepareConversationRestore()`가 prompt/attachments를 비우고, `invalidateAttachmentReads()`의 generation fencing과의 순서 문제도 없다(어느 순서든 이전 대화의 지연 FileReader가 새 대화에 끼어들 수 없음을 코드로 확인). `resetForNewChat()`과도 호출 경로가 겹치지 않는다. |

### 8.2 이번 수정이 새로 만든 잔여 이슈 (P2, 비차단)

1. **REST 알림도 완전히는 아니다**: `mutation_confirmation_required`를 turn 중간에 포착해도, 그 이후 같은 요청이 deadline/취소로 예외 종료되면(`response.withEvents(...)`에 도달하지 못하고) 알림이 그대로 유실된다. 도구 실행 자체는 여전히 서버에서 막히므로 보안 문제는 아니지만, 사용자는 새로고침 전까지 승인 신호를 못 받는다.
2. **stopWatchdog()이 `entry.workerThread`를 null 처리하지 않는다** — `finish()`/`commitResult()`와 달리 워치독 경로만 이 필드를 비우지 않아, 강제 종료된 entry가 30분 TTL 동안 죽은 Thread 참조를 들고 있다(메모리 위생 문제, 정확성 문제는 아님).
3. **`dispatch()`에 예외 처리/로깅이 없다** — `requestTimeout()`/`stopWatchdog()` 내부에서 미래에 NPE 같은 예외가 나면 virtual thread의 기본 처리기가 stderr로만 흘리고 애플리케이션 로그에는 안 남아, 그 entry가 영원히 registry 슬롯을 차지하는 회귀가 나더라도 진단이 매우 어려워진다.

### 8.3 워크플로우 운영 노트 (코드 결함 아님)

이번 재검증 도중, FIX-D(scheduler 정체 수정)를 검증하던 에이전트가 "수정 전 상태로 되돌려 동일 버그가 실제로 재현되는지" 확인하기 위해 공유 작업 트리에서 `git stash`(또는 동등한 되돌리기)를 수행했다. `git stash`는 파일 단위가 아니라 저장소 전체에 적용되므로, 같은 시각 다른 파일(`AiChatController.java` 등)을 읽고 있던 FIX-A 검증 에이전트가 일시적으로 커밋 전 상태를 관찰하고 "수정이 사라졌다"고 잘못 보고했다. 워크플로우 종료 후 `git stash list`(비어 있음)와 `git diff --stat`(수정 시작 시점과 동일)으로 확인한 결과 유실된 변경 사항은 없다.

이는 실제 코드 결함이 아니라 **여러 에이전트가 격리(worktree) 없이 같은 미커밋 작업 트리를 동시에 읽고/되돌리며 검증했기 때문에 생긴 아티팩트**다. 재현·되돌리기가 필요한 검증을 병렬로 돌릴 때는 해당 에이전트만 별도 worktree에서 실행했어야 한다는 운영상 교훈으로 기록한다.

### 8.4 권고

- BUG-04: `ChatService.java`의 Base64 디코딩 실패 메시지에도 다른 첨부 오류처럼 `safeName()`을 적용해 빈 이름 edge case를 닫는다(한 줄 수정).
- RISK-05: `AiSensitiveDataRedactor`의 단독 `auth`/`session` 키워드에 단어 경계(예: `\b`) 또는 최소한 `_`/`Id`/`Name`/`Count` 등 흔한 접미사를 제외하는 negative lookahead를 추가해 과다 redaction을 줄인다. 승인 알림의 신뢰성이 이 기능의 핵심 목적이므로 우선순위를 BUG-04보다 높게 본다.
- §8.2의 3건은 지금 당장 배포를 막을 사유는 아니지만, 커밋 전에 최소한 dispatch() 예외 로깅 정도는 추가할 것을 권장한다.
- 이 문서 자체가 아직 커밋되지 않은 15개 파일과 함께 작업 트리에 있다 — §8.3에서 보듯 미커밋 상태에서 여러 도구/에이전트가 동시에 손대는 것은 실제로 위험하므로, 검증이 끝난 지금이 커밋하기 좋은 시점이다.

## 9. §8 잔여 결함 및 인접 경로 후속 수정

§8의 partial 2건과 P2 3건을 모두 수정했다. 또한 동일 경로를 적대적으로 다시 읽고 실행하면서 확인한 인접 결함 5건도 함께 닫았다.

| 항목 | 상태 | 후속 구현·검증 |
|---|---|---|
| BUG-04 빈 Base64 첨부 이름 | **완료** | 모든 첨부 오류가 bounded `safeName()`을 사용한다. 빈 이름은 `attachment`, 긴 이름은 120자로 제한되며 프론트의 8개 정확한 allowlist 패턴을 모두 parameterized test로 검증한다. 잘못된 image MIME parser 오류도 동일한 안전 계약으로 정규화했다. |
| RISK-05 과다 redaction | **완료** | camelCase·snake_case를 논리적 키로 정규화한 뒤 실제 secret suffix만 매치한다. `authorId`, `authorName`, `coAuthor`, `isAuthenticated`, `sessionCount`, `sessionDurationMinutes`는 보존하고 `authToken`, `sessionId`, `clientSecret`, `credentialId`는 계속 가린다. 자유 텍스트에도 영숫자 단어 경계를 적용했다. |
| terminal REST 알림 유실 | **완료** | 모델 turn이 실패·timeout으로 끝나도 이미 발급된 confirmation event를 구조화된 non-2xx `ChatResponse` body에 보존한다. 프론트는 기존 strict notice parser를 그대로 적용한다. 취소 중이거나 승인 follow-up 결과가 불명확한 경우에는 기존처럼 reconciliation을 우선한다. |
| watchdog stale Thread 참조 | **완료** | watchdog이 interrupt 대상 thread를 로컬로 캡처하고 entry 참조를 즉시 null 처리한다. late `finish()`는 terminal 상태를 덮어쓰지 않는다. |
| dispatch 예외 가시성 | **완료** | virtual lifecycle task와 dispatch 자체의 예외를 request/operation과 함께 기록하고, 해당 entry를 `UNKNOWN_RECONCILIATION_REQUIRED`로 terminal 처리해 슬롯 누수도 방지한다. |

추가 적대 검토에서 다음 항목도 수정했다.

1. 동일 registry entry의 두 번째 `start()`가 worker identity와 상태를 덮어쓸 수 있던 문제를 `REGISTERED` 단일 전이로 제한했다.
2. scheduler가 deadline 등록을 거부하면 이미 삽입된 registry entry가 남던 문제를 원자적으로 rollback한다.
3. 0ms deadline task가 assignment보다 먼저 terminal 처리되면 완료된 `ScheduledFuture` 참조가 30분간 남을 수 있던 경쟁을 terminal 확인 후 assignment/cancel로 막았다.
4. 사용자 제공 첨부명이 과도하게 길면 안전한 서버 오류도 프론트의 500자 한계를 넘어 generic으로 폴백하던 문제를 bounded 이름으로 막았다.
5. `image/*` 문자열이 잘못된 MIME 문법이면 Spring parser의 내부 오류로 계약을 벗어나던 경로를 `Unsupported AI attachment type`으로 정규화했다.

검증 결과:

```text
score-http 전체: 565 tests, failures 0, errors 0, skipped 0
score-web 전체: 162 files / 854 tests, failures 0
score-http package: 성공
score-web production build: 성공
score-web lint: 성공
lifecycle scheduler/watchdog/dispatch 경쟁 테스트 5회 반복: 성공
git diff --check: 성공
```

§8은 발견 당시의 독립 검토 기록으로 유지한다. 현재 상태는 이 §9가 우선한다.

## 10. P2 전수 수정 및 Redis 다중 인스턴스 전환

§2에 남아 있던 P2 5~16과 프로세스 로컬 request registry 제한을 후속 검토·수정했다.

| 항목 | 상태 | 구현·검증 |
|---|---|---|
| 5. mutation DB 상태 기계 테스트 부재 | **완료** | H2 MySQL mode의 실제 `JdbcTemplate`/transaction으로 `REQUESTED → APPROVED → CONSUMED`, one-time replay 거부와 conversation owner join을 실행한다. 기존 실제 MariaDB `FOR UPDATE` 동시성 검증과 함께 상태 전이 회귀를 자동화했다. |
| 6. 승인 정리 주기 불일치 | **완료** | conversation retention과 confirmation expiration schedule을 분리하고, confirmation은 10분 주기로 `EXPIRED` 처리 및 `grant_digest` 제거한다. |
| 7. read-only 정규식 잠재 우회 | **완료** | 정규식을 제거하고 현재의 정확한 case-sensitive read tool 집합만 허용한다. 알 수 없거나 미래에 추가된 도구는 mutation으로 분류하는 fail-closed 정책을 테스트했다. |
| 8. prepare/admission TOCTOU | **완료** | Redis admission reservation을 `prepare()`보다 먼저 획득하고 준비 후 conversation을 bind한다. 동시 요청 중 거부된 요청이 prepare 부작용을 실행하지 않음을 경쟁 테스트로 검증했다. |
| 9. ChatService registry fence 미검증 | **완료** | 실제 registry로 정상 commit과 cancel/timeout 이후 persistence 거부를 `chat()` 경로에서 검증한다. |
| 10. raw tool 실패 저장 | **완료** | 원인은 server log에만 남기고 trajectory/UI에는 일반화된 안전 메시지만 기록한다. |
| 11. compacted 플래그 고착 | **완료** | 정상 대화 turn 저장 시 `markExpanded()`로 플래그를 복원한다. |
| 12. retention cutoff 검증 부족 | **완료** | 주입한 `Clock`의 정확한 `now.minus(retention)` cutoff를 캡처해 부호·경계 회귀를 검증한다. |
| 13. active recovery/FileReader 유실 | **완료** | pending 상태가 아닌 conversation/read generation만 완료를 fence하도록 고쳐 recovery 중 읽은 첨부가 보존됨을 검증한다. |
| 14. attachment accept 중복 | **완료** | extension/MIME 상수에서 `accept` 값을 파생하고 template binding 계약을 테스트한다. |
| 15. 21-field event 결합 | **완료** | backend가 non-empty 최소 confirmation envelope만 직렬화하고 frontend parser도 안정 필드만 검증한다. 정확한 JSON 계약 테스트를 추가했다. |
| 16. cancel enum drift | **완료** | frontend disposition을 backend의 5개 값과 일치시키고 404 empty body·terminal/unknown reconciliation 동작을 검증한다. |

다중 인스턴스 수명주기는 다음과 같이 전환했다.

- Redis `RMapCache`가 request/generation/deadline/status/cancel/terminal/mutation 상태의 authoritative store다. active 상태는 절대 만료 시각, terminal 상태는 30분 TTL을 사용한다.
- Redisson 전역 락과 request별 분산 락이 사용자별 8개 cap, 전역 10,000개 cap, request ID 유일성, 대화 단일 활성 요청, prepare reservation, maintenance 작업과 final persistence fence를 보호한다.
- 원격 인스턴스의 cancel/timeout 신호는 Redis pub/sub으로 worker 소유 인스턴스에 전달한다. owner가 사라지면 deadline/cancel grace 이후 다른 인스턴스가 보수적으로 terminal reconciliation하며, live owner watchdog과의 경쟁에는 짧은 분산 reconciliation 여유를 둔다.
- 프로세스 로컬에는 이전 불가능한 `Thread`와 `ScheduledFuture` 핸들만 monitor로 보호해 유지한다. 저장소의 프로세스 로컬 공유 캐시는 Redis로 이전했다.
- GitHub Projects refs 캐시는 Redis `RBucket`의 30분 TTL과 project 좌표/config별 Redisson 분산 락을 사용한다. 두 서비스 인스턴스가 실제 Redis cache를 공유하고 외부 조회를 한 번만 수행함을 통합 테스트로 검증했다.
- 공유 Redis를 시작 시 파괴하던 `FLUSHALL`은 `score.redis.flush-on-startup=false`가 기본이며, 명시적 maintenance opt-in에서만 실행된다.
- typed Redis JSON codec round-trip, 실제 Redis pub/sub 통합, 두 registry 인스턴스의 admission/active/status/remote cancel, owner 소실, maintenance reservation을 테스트했다.

최종 검증 결과:

```text
score-http 전체: 583 tests, failures 0, errors 0, skipped 0
score-web 전체: 162 files / 854 tests, failures 0
score-http package: 성공
score-web production build: 성공
score-web lint: 성공
git diff --check: 성공
```

§2와 §8은 발견 당시 기록으로 유지한다. 현재 구현 상태는 이 §10이 우선한다.
