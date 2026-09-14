---
description: 서비스 용어, enum 표준, 대문자를 유지하는 식별자, ID 체계, 계약 이름과 서버 테이블의 어긋남, 확정 수치
paths:
  - "docs/**"
  - "src/**"
  - "API.md"
---

# 도메인 용어

## 이름

- 서비스명은 떼거지. 거지방은 방 단위 명칭, 거지야호는 팀명
- AI 역할 다섯: 심문관(Intake), 조서(Context), 드립 후보(Banter), 양형관·서기(Judge), 검수관(Evaluator). 백엔드는 이 역할을 부르지 않고 job 을 넣고 결과를 확정한다
- 판결 결과는 유죄, 무죄, 동의, 기각, 각하 5종

## enum은 프론트 값이 표준이다(10 §15.2 D-21)

- 강도 `mild|spicy|hell`, 평결 `guilty|notGuilty|agree|disagree|dismissed`, 게시물 `spent|considering`, 형량 `probation|oneDay|life`, 카테고리 11종 고정(10 §15.2)
- 정본은 형제 저장소 `geoji-web/src/shared/domain/`(위치는 `CLAUDE.md`의 `GEOJIBANG_ROOT` 규칙). Java enum 상수도 이 값 그대로 소문자·camelCase 다(`SpiceLevel.mild`, `VerdictType.notGuilty`). Postgres native enum 라벨과 이름이 같아야 `NAMED_ENUM` 매핑이 변환 없이 돈다
- 새 enum 에 대문자 `MILD/GUILTY/DAYS_1`을 쓰지 않는다
- 대문자를 유지하는 것은 식별자뿐이다. 짤 태그 5종(`GUILTY_HEAVY` 등), 감정 6종(`DISAPPROVAL` 등, 10 §11), `NO_SPEND`, job kind(`PREPARE`, `SENTENCE`, `RETAIN`, `TEXT_RETRY`), job 상태(`QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED`), 판결 상태(`PENDING/FINAL`, `GENERATING/TEMPLATE_READY/AI_READY`), 제출 상태(`NEW/NEEDS_INPUT/COMPLETED/BLOCKED/EXPIRED`), 오류 코드(`STALE_GENERATION`, `DEADLINE_EXCEEDED`, `EVIDENCE_INVALIDATED`, `IDEMPOTENCY_CONFLICT`, `INVALID_DRAFT`), 소스(`AI/TEMPLATE/RULE/FALLBACK`), 정책 버전(`guardrail-v2`). 이것들은 Java enum 이어도 대문자 상수다

## 계약 이름과 서버 테이블은 1:1이 아니다

10 은 AI 파트가 서버에 재판 흐름이 없던 9/8 기준으로 쓴 이름이다. 9/11 서버에 재판 API 가 생기면서 이름이 갈라졌다. **대응은 아직 정해지지 않았다.** 매핑하거나 새 테이블을 만들기 전에 묻는다.

| 10 | 서버 지금 | 어긋남 |
| -- | --------- | ------ |
| `posts`(`item`·`reason`·`post_type`) | `expenses`(`memo`·`source`) | `item` 컬럼 없음. `post_type` `spent/considering` ↔ `ExpenseSource` `quick_tap/purchase_check` |
| `verdicts`(`verdict_version`, `sentence`, `text_status` …) | `expense_trials`(`verdict`, `verdict_text`, `sentence_days`) | 형량이 enum 이 아니라 일수. 버전·상태 컬럼 없음 |
| `verdict_texts`, `submissions`, `meme_images` | 없음 | 신규 |
| 판결 확정 → SENTENCE job | `POST .../trial/judge` 가 `AiClient.judge` 동기 호출 | 연동 방식 자체가 다름(10 §15.2 "연동") |
| `posts.category` 11종 CHECK | `expenses.category` 자유 문자열 | 제약 없음 |

## ID 체계

- 결정 `D-NN`(10 §15), 절 `10 §n.n`, 근거 라벨 `F0~F6`
- 마이그레이션 `001~003` AI 소유(AI 저장소 러너가 적용), `004` 백엔드 소유(10 §2), `005` P1
- 마일스톤 M2 9/10, M3 9/15, M4 9/18, 9/20 동결

## 구조와 수치(바꾸려면 AI 파트와 합의)

- 공유 Supabase Postgres + `ai.jobs` 큐 + Python 워커 + 백엔드 finalize. 워커는 업무 테이블을 직접 쓰지 않는다. 최종 저장은 백엔드 내부 API 뿐
- 전달은 폴링. Realtime과 SSE는 없다(10 §9)
- SENTENCE 마감은 INSERT + 10초, PREPARE 대기 최대 30초(D-24). watchdog 250ms, reaper 5초, 재시도 round 5·10·20분(10 §6·§7)
- 지출 등록 `item` 30자 이하, `reason` 200자 이하. 판결문 `statement` 합산 300자 이하
