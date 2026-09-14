---
description: Spring Boot 4.1·Java 25 툴체인, 패키지 배치, 스키마 소유와 004 초안 위치, 내부 API 규약, 잠금 순서
paths:
  - "src/**"
  - "build.gradle"
  - "settings.gradle"
---

# 코드 배치

## 툴체인

- Spring Boot 4.1.1, Java 25 toolchain(Temurin), Gradle wrapper(`./gradlew`). 시스템 Gradle 은 쓰지 않는다
- JSON 은 **Jackson 3**(`tools.jackson.*`)이다. `com.fasterxml.jackson` 을 import 하지 않는다
- Lombok(`@Getter @Setter @Builder @RequiredArgsConstructor`), Bean Validation(`@Valid`), Spring Data JPA, OAuth2 Resource Server, Testcontainers(테스트)
- 의존성 추가(`build.gradle`)는 승인 대상이다. EB 배포 jar 이름 `backend-0.0.1-SNAPSHOT.jar` 가 CI 에 박혀 있어 `version`·`rootProject.name` 을 바꾸지 않는다

## 배치

```
src/main/java/com/ttegeoji/backend/
  api/            공개 REST 컨트롤러(JWT)
  api/internal/   내부 API 컨트롤러 /internal/v1/**(서비스 토큰). 워커가 부른다
  dto/            기존 공개 API 요청·응답 record
  domain/         JPA 엔티티. domain/enums/ 에 Postgres native enum 과 같은 이름의 enum
  repository/     Spring Data JPA
  config/         SecurityConfig, ServiceTokenFilter, ApiExceptionHandler, GeojiProperties, RestClient·스케줄링 설정
  security/       CurrentUser (JWT sub = profiles.id)
  jobs/           ai.jobs INSERT·조회·reaper(10 §3·§7)
  verdict/        평결 확정·SENTENCE 게이트·begin/failed/finalize·폴백·watchdog·재시도·해시·템플릿·짤
  internal/       snapshot·resolve-evidence 조립(10 §4.1·§4.2)
  submission/     게시물 제출·intake(10 §9·§4)
  privacy/        epoch·무효화(10 §8)
  verdictview/    판결 조회 조립(10 §9)
  comment/        게시물 댓글·RETAIN comment
  seed/           데모 시드(10 §12, seed 프로필)
  ai/             AI API 클라이언트(IntakeClient·AiTraceClient) + P1 AiClient 3종
  util/           Json(jsonb ↔ 문자열)
src/main/resources/application.yml       환경변수는 ${KEY:기본값}
src/main/resources/contracts/            AI 저장소에서 복사한 templates-v1.json
src/main/resources/sql/                  AI 저장소에서 복사한 invalidate_scope.sql
```

- 위 패키지는 9/14 orca 플랜으로 승인됐다. 목록 밖의 새 패키지·계층은 승인받는다
- 기존 공개 API 는 컨트롤러가 repository 를 직접 부른다. 새 재판 흐름은 여러 테이블을 한 트랜잭션으로 묶으므로 서비스 클래스를 둔다
- 조회가 더 필요하면 앞 작업의 repository 파일을 고치지 않고 자기 패키지 `*Queries` 클래스(JdbcTemplate)에 둔다. 병렬 작업끼리 파일이 부딪히지 않게
- 기존 동기 판결 경로(`ExpenseTrialController`·`expense_trials`·`AiClient.judge`)는 새 흐름이 붙은 뒤 제거한다. `AiClient` 의 상·도전·순찰 3종은 P1 이라 유지(10 §15.3)

## 스키마

- 업무 테이블 DDL 정본은 `supabase/migrations/`이고 **이 저장소에 없다**(10 §15.1). Hibernate 는 `ddl-auto: validate` 라 엔티티와 DB 가 다르면 기동이 실패한다
- 10 §2 의 004 는 백엔드 소유다. **초안은 `src/test/resources/db/004_verdict_generation.sql`**(테스트가 적용), 머지 뒤 사용자가 supabase/migrations 로 옮겨 운영에 적용한다(9/14 결정). 추가분은 `004b_{주제}.sql`
- `ai` 스키마의 001~003 은 AI 저장소 러너가 적용한다. 백엔드가 `ai.*` DDL 을 새로 만들지 않는다(테스트용 복사본만). `ai.*` 테이블은 엔티티 없이 JdbcTemplate 으로 다룬다
- role 3종(`ai_api`·`ai_worker`·`backend`)과 grants 는 10 §1 표 그대로. DELETE 권한은 없다

## 10 이 코드에 거는 규약

- **잠금 순서(10 §2):** privacy scope 행(key 오름차순) → verdict 행 → job 행. 어느 코드 경로든 같다
- **DB 트랜잭션 안에서 HTTP 를 기다리지 않는다.** intake 호출(10 §4)은 트랜잭션 밖
- **job INSERT 는 업무 트랜잭션 안에서** `ON CONFLICT (dedupe_key) DO NOTHING`. payload 는 kind 별 키 그대로, 알 수 없는 필드를 넣으면 워커가 거부한다(10 §3)
- **호출자가 보낸 평결·강도·허용 목록을 믿지 않는다.** DB 스냅샷에서 읽는다(10 §5)
- **내부 API `/internal/v1/**` 는 JWT 가 아니라 서비스 토큰**. `SecurityConfig` 에 별도 체인, `ServiceTokenFilter` 가 `Authorization: Bearer <SERVICE_AUTH_TOKEN>` 을 timing-safe 비교, 토큰 설정값이 비면 전부 401. 거부 본문은 `{"code": "..."}`(10 §4.7). 공개 API 의 `{"message": "..."}` 와 섞지 않는다
- 환경변수: `SERVICE_AUTH_TOKEN`(양방향 같은 값)·`AI_API_BASE_URL`(백엔드 → AI API)·`GUARDRAIL_POLICY_VERSION`(finalize 비교값)
- 같은 요청 재도착은 멱등하게 받는다. finalize 는 commit record(10 §5 1·3단계)
- `draft_hash` 는 10 §5 규칙(NFC → 키 정렬 → `,` `:` 구분 → 비ASCII 이스케이프 없음 → sha256 소문자 hex). AI 저장소 `src/geoji_ai/domain/draft_hash.py` 와 같은 값이 나오는지 테스트로 묶는다
- 시각은 `OffsetDateTime`/`timestamptz` UTC. 마감 비교는 DB `now()` 기준(10 §5 6단계)
- 로그에 서비스 토큰·벤더 키·사유 원문을 남기지 않는다
