# CLAUDE.md

떼거지(친구들이 내 지출을 재판하는 소비 절제 커뮤니티)의 백엔드. 게시물·투표·평결·권한을 소유하고, AI 파트와는 공유 Postgres 의 `ai.jobs` 큐와 내부 HTTP API 로 붙는다. 판결문을 만드는 모델 호출은 AI 파트 몫이고, 이 저장소는 job INSERT·내부 API 5개·finalize·watchdog·재시도 예약·삭제 무효화·공개 API 를 맡는다. 저장소 이름은 `geoji-server`이고 로컬에서는 `geojibang/` 워크스페이스 안에 있다. 원티드 AI Championship 2026 출품작이고 제출 마감은 2026-09-20, 심사는 9/21부터 10/5까지다. AI 파트 `geoji-agent`와 프론트 `geoji-web`는 같은 워크스페이스의 형제 저장소다. 형제 저장소의 위치는 환경변수 `GEOJIBANG_ROOT`(사용자 `~/.claude/settings.json`의 `env`)이고, 없으면 `..`이다. orca 워크트리는 `~/orca/workspaces/` 아래에 생겨 `..`이 형제가 아니므로 먼저 `echo $GEOJIBANG_ROOT`로 루트를 잡고 절대 경로로 읽는다. 스택은 Spring Boot 4.1 · Java 25 · Gradle · JPA · Supabase Postgres 이고, `main` push 가 GitHub Actions 로 Elastic Beanstalk 에 배포된다. Python 은 쓰지 않는다.

## 규칙

- 문서와 주석, 커밋 메시지, 응답은 한국어. 코드 식별자는 영어
- 할 일의 정본은 `docs/10-backend-contract.md`(이하 10)다. AI 저장소 `docs/plans/10`의 **읽기 전용 사본**이라 여기서 고치지 않는다. 절은 `10 §4.3`, `10 §0.1`처럼 부른다. 사본 다루는 법과 회신 기록은 `.claude/rules/backend-contract.md`
- 프론트가 읽는 공개 API 문서는 `API.md`다. 공개 엔드포인트를 바꾸면 같은 커밋에서 고친다
- Git: `.claude/rules/git-workflow.md`. `type(scope): 한국어 제목`. `.env`는 `git add` 하지 않는다
- 용어: `.claude/rules/domain-vocabulary.md`. enum은 프론트 값이 표준이다(10 §15.2 D-21)
- 코드: `.claude/rules/code-layout.md`. 패키지 배치, Jackson 3, 스키마 소유, 잠금 순서
- 테스트: `.claude/rules/testing.md`. 게이트는 `./gradlew build`. 공유 Supabase 에 테스트를 붙이지 않는다
- 10 에 없는 값이 필요하면 만들지 말고 묻는다. 10 의 `(제안)`·§14 미결은 채택을 정한 뒤에 코드로 옮긴다

## 스킬셋

에이전트가 읽는 룰·스킬·서브에이전트·슬래시 커맨드·훅은 `.claude/` 에 있다. 목록과 추가하는 법은 `.claude/README.md`.

10 의 절이나 §0.1 할 일을 코드로 만들 때는 `geoji-harness` 스킬로 진행한다. 명세 대조, 구현, 테스트 검증을 서브에이전트 셋이 나눠 맡는다. 코드 리뷰는 하네스에 없고 사용자가 필요할 때 직접 `/code-review`로 돌린다. 단순 질문과 한 줄 수정, 문서 작업, 커밋에는 쓰지 않는다.

## 기준 문서

| 무엇 | 정본 | 여기 있는 것 |
| ---- | ---- | ------------ |
| 백엔드 계약·할 일·회신 대기 | AI 저장소 `docs/plans/10-backend-contract.md` | `docs/10-backend-contract.md` 사본 |
| JSON 모양·enum·길이 | AI 저장소 `contracts/*-v1.schema.json` | 없음. 형제 저장소에서 읽는다 |
| 폴백 문구 | AI 저장소 `contracts/fixtures/templates-v1.json` | 복사해 오면 버전 고정(10 §10) |
| 워커가 기대하는 백엔드 동작 | AI 저장소 `tests/fakes/backend_app.py`(가짜 백엔드) | 없음. 10 과 다르면 10 이 이긴다 |
| 프론트 enum | `geoji-web/src/shared/domain/*.ts` | Java enum 이 소문자로 맞춘다 |
| 공개 API | 이 저장소 `API.md` | — |
| 업무 테이블 DDL | `supabase/migrations/`(이 저장소에 없음, 10 §15.1) | 엔티티는 `ddl-auto: validate` 로만 대조 |

10 과 코드가 어긋나면 10 을 따르고 어긋남을 보고한다. 10 과 가짜 백엔드가 어긋나면 10 이 이긴다.

## 자주 틀리는 것

<!-- TODO 실제로 틀렸던 것만 적는다 -->
