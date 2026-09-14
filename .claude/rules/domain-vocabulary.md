---
description: 서비스 용어, enum 표준, 대문자 식별자, 10 이름과 서버 테이블의 대응(9/14 결정), 재판 규칙, 확정 수치
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

- 강도 `mild|spicy|hell`, 평결 `guilty|notGuilty|agree|disagree|dismissed`, 게시물 `spent|considering`, 형량 `probation|oneDay|life`, 카테고리 11종 고정(`식비`·`배달`·`카페/간식`·`교통/택시`·`쇼핑/패션`·`뷰티`·`취미/여가`·`술/유흥`·`구독`·`생활`·`기타`, 10 §15.2)
- 정본은 형제 저장소 `geoji-web/src/shared/domain/`(위치는 `CLAUDE.md`의 `GEOJIBANG_ROOT` 규칙). Java enum 상수도 이 값 그대로 소문자·camelCase 다(`SpiceLevel.mild`, `VerdictType.notGuilty`). Postgres native enum 라벨과 이름이 같아야 `NAMED_ENUM` 매핑이 변환 없이 돈다
- 새 enum 에 대문자 `MILD/GUILTY/DAYS_1`을 쓰지 않는다
- 대문자를 유지하는 것은 식별자뿐이다. 짤 태그 5종(`GUILTY_HEAVY` 등), 감정 6종(`DISAPPROVAL` 등, 10 §11), `NO_SPEND`, job kind(`PREPARE`, `SENTENCE`, `RETAIN`, `TEXT_RETRY`), job 상태(`QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED`), 판결 상태(`PENDING/FINAL`, `GENERATING/TEMPLATE_READY/AI_READY`), 제출 상태(`NEW/NEEDS_INPUT/COMPLETED/BLOCKED/EXPIRED`), 오류 코드(`STALE_GENERATION`, `DEADLINE_EXCEEDED`, `EVIDENCE_INVALIDATED`, `IDEMPOTENCY_CONFLICT`, `INVALID_DRAFT`), 소스(`AI/TEMPLATE/RULE/FALLBACK`), 정책 버전(`guardrail-v2`). 이것들은 Java enum 이어도 대문자 상수다

## 10 이름대로 새 테이블을 만든다(9/14 결정)

10 은 서버에 재판 흐름이 없던 9/8 기준으로 쓴 이름이다. 9/11 서버에 동기 판결 경로가 생겨 이름이 갈라졌지만, **재판 흐름은 10 대로 신규 테이블에 올리고 기존 경로는 나중에 제거한다.** 매핑하지 않는다.

| 10 · 신규 | 기존 서버 | 처리 |
| --------- | --------- | ---- |
| `posts`(`item`·`reason`·`post_type`·`vote_deadline_at`) + `post_rooms`(공유 방) | `expenses`(`memo`·`source`) | `expenses` 는 그대로 둔다(무지출·그리드 등 기존 기능) |
| `votes`(post_id, voter_id, room_id, verdict, reason 1~500 필수) | `expense_votes` | 기존은 제거 대상 |
| `verdicts`·`verdict_texts` | `expense_trials`(`verdict_text`·`sentence_days`) | 기존은 제거 대상 |
| 판결 확정 → SENTENCE job → 워커 → finalize | `POST .../trial/judge` → `AiClient.judge` 동기 호출 | 기존은 제거 대상 |
| `post_comments`(content ≤ 200) | `comments`(expense 단위) | 기존 댓글은 그대로. RETAIN 은 `post_comments` 만 |
| `submissions`·`meme_images` | 없음 | 신규 |

10 §2 에 없는 테이블·컬럼(`post_rooms`, `votes`, `post_comments`, `posts.vote_deadline_at` 등)을 만들면 AI 파트에 회신한다(`backend-contract` 룰).

## 재판 규칙(geoji-web SPEC.md + 9/14 결정)

- 정족수 2표, 투표 가능 인원이 2명 미만이면 그 인원 수. 미달이면 `dismissed`(job 없음)
- 동률은 `spent` → `notGuilty`, `considering` → `disagree`
- 게시물당 1인 1표. 여러 방에 공유돼도 1표. 작성자는 투표하지 않는다. 투표 수정 불가
- 마감 = 공유 방 `vote_deadline_minutes` 중 가장 짧은 것. 전원 투표하면 즉시
- 판결은 게시물당 1건. `target_intensities` = 공유 방 강도 집합, `default_intensity`·`applied_intensity` = 표가 가장 많은 방(동률은 방 생성일 이른 방)
- 양형 밴드(유죄일 때만): 유죄율 50~69% `[probation]`, 70~89% `[probation, oneDay]`, 90%+ `[probation, oneDay, life]`. `fallback_sentence` = 밴드 상한
- 게시물 댓글: 1단계, 200자, 본인 삭제. 판결 확정 게시물 댓글은 모두 RETAIN, 안전 필터는 AI 워커
- 미결: `rooms.rule_version`, `policy_snapshot.version`·`reason_required` 값

## ID 체계

- 결정 `D-NN`(10 §15), 절 `10 §n.n`, 근거 라벨 `F0~F6`
- 마이그레이션 `001~003` AI 소유(AI 저장소 러너가 적용), `004` 백엔드 소유(10 §2, 초안은 `src/test/resources/db/`), `005` P1
- 마일스톤 M2 9/10, M3 9/15, M4 9/18, 9/20 동결

## 구조와 수치(바꾸려면 AI 파트와 합의)

- 공유 Supabase Postgres + `ai.jobs` 큐 + Python 워커 + 백엔드 finalize. 워커는 업무 테이블을 직접 쓰지 않는다. 최종 저장은 백엔드 내부 API 뿐
- 전달은 폴링. Realtime과 SSE는 없다(10 §9)
- SENTENCE 마감은 INSERT + 10초, PREPARE 대기 최대 30초(D-24). watchdog 250ms, reaper 5초, 재시도 round 5·10·20분(10 §6·§7)
- 지출 등록 `item` 30자 이하, `reason` 200자 이하. 판결문 `statement` 합산 300자 이하
