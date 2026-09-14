---
description: Spring Boot 4.1·Java 25 툴체인, 패키지 배치, 스키마 소유, 내부 API 규약, 잠금 순서
paths:
  - "src/**"
  - "build.gradle"
  - "settings.gradle"
---

# 코드 배치

## 툴체인

- Spring Boot 4.1.1, Java 25 toolchain, Gradle wrapper(`./gradlew`). 시스템 Gradle 은 쓰지 않는다
- JSON 은 **Jackson 3**(`tools.jackson.*`)이다. `com.fasterxml.jackson` 을 import 하지 않는다
- Lombok(`@Getter @Setter @Builder @RequiredArgsConstructor`), Bean Validation(`@Valid`), Spring Data JPA, OAuth2 Resource Server
- 의존성 추가(`build.gradle`)는 승인 대상이다. EB 배포 jar 이름 `backend-0.0.1-SNAPSHOT.jar` 가 CI 에 박혀 있어 `version`·`rootProject.name` 을 바꾸지 않는다

## 배치

```
src/main/java/com/ttegeoji/backend/
  api/          REST 컨트롤러. 공개 API 는 /api/**
  dto/          요청·응답 record. 엔티티를 응답으로 직접 내보내지 않는다
  domain/       JPA 엔티티. domain/enums/ 에 Postgres native enum 과 같은 이름의 enum
  repository/   Spring Data JPA
  config/       SecurityConfig, ApiExceptionHandler, RequestLoggingFilter
  security/     CurrentUser (JWT sub = profiles.id)
  ai/           AiClient 경계. 지금은 StubAiClient
  util/         Json(jsonb ↔ 문자열)
src/main/resources/application.yml   환경변수는 ${KEY:기본값}
```

- 지금 관례는 컨트롤러가 repository 를 직접 부른다. 10 의 재판 흐름(§3 INSERT, §5 finalize, §6 watchdog, §7 스케줄러, §8 무효화)은 여러 테이블을 한 트랜잭션으로 묶으므로 새 패키지(서비스·스케줄러·내부 API)가 필요할 수 있다. **새 패키지나 계층을 만들기 전에 이름과 위치를 승인받는다**
- 기존 `ai/AiClient` 3종(상·도전·순찰)은 P1 이다(10 §15.3). 재판 흐름 때문에 지우거나 바꾸지 않는다. `judge` 를 job 큐로 대체할지는 domain-vocabulary 룰 어긋남 표대로 묻는다

## 스키마

- 업무 테이블 DDL 정본은 `supabase/migrations/`이고 **이 저장소에 없다**(10 §15.1). Hibernate 는 `ddl-auto: validate` 라 엔티티와 DB 가 다르면 기동이 실패한다
- 10 §2 의 004(업무 테이블 변경 + `ai.privacy_epochs`·`ai.verdict_commit_records`·`ai.text_evidence_refs`)는 백엔드 소유다. **파일을 어디에 두고 누가 적용하는지 정해지지 않았다.** DDL 을 쓰기 전에 묻는다
- `ai` 스키마의 001~003 은 AI 저장소 러너가 적용한다. 백엔드가 `ai.*` DDL 을 만들지 않는다. `ai.*` 테이블은 엔티티 대신 네이티브 SQL(`JdbcTemplate` 또는 `@Query(nativeQuery)`)로 다룰지 승인받는다
- role 3종(`ai_api`·`ai_worker`·`backend`)과 grants 는 10 §1 표 그대로. DELETE 권한은 없다

## 10 이 코드에 거는 규약

- **잠금 순서(10 §2):** privacy scope 행(key 오름차순) → verdict 행 → job 행. 어느 코드 경로든 같다
- **DB 트랜잭션 안에서 HTTP 를 기다리지 않는다.** intake 호출(10 §4)은 트랜잭션 밖
- **job INSERT 는 업무 트랜잭션 안에서** `ON CONFLICT (dedupe_key) DO NOTHING`. payload 는 kind 별 키 그대로, 알 수 없는 필드를 넣으면 워커가 거부한다(10 §3)
- **호출자가 보낸 평결·강도·허용 목록을 믿지 않는다.** DB 스냅샷에서 읽는다(10 §5)
- **내부 API `/internal/v1/*` 는 JWT 가 아니라 서비스 토큰**(`Authorization: Bearer <SERVICE_AUTH_TOKEN>`, timing-safe 비교). 거부 본문은 `{"code": "..."}`(10 §4.7). 공개 API 의 `{"message": "..."}`(`ApiExceptionHandler`)와 섞지 않는다. 필터 체인을 어떻게 나눌지는 `SecurityConfig` 변경이라 승인 대상
- 같은 요청 재도착은 멱등하게 받는다. finalize 는 commit record(10 §5 1·3단계)
- `draft_hash` 는 10 §5 규칙(NFC → 키 정렬 → `,` `:` 구분 → 비ASCII 이스케이프 없음 → sha256 소문자 hex). AI 저장소 `src/geoji_ai/domain/draft_hash.py` 와 같은 값이 나오는지 테스트로 묶는다
- 시각은 `OffsetDateTime`/`timestamptz` UTC. 마감 비교는 DB `now()` 기준(10 §5 6단계)
- 로그에 서비스 토큰·벤더 키·사유 원문을 남기지 않는다
