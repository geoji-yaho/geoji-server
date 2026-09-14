---
description: Gradle 게이트, Testcontainers 테스트 DB, AI API·벤더 격리, 10 수용 검사를 테스트로 옮기는 법
paths:
  - "src/test/**"
  - "build.gradle"
---

# 테스트

## 게이트

```bash
./gradlew build
```

- 컴파일·테스트·bootJar 를 한 번에 본다. CI(`deploy.yml`)는 `bootJar` 만 돌려 테스트를 거치지 않으므로 로컬 게이트가 유일한 검사다
- JDK 25(Temurin)와 Docker(Testcontainers)가 필요하다. 없으면 게이트를 **돌리지 못한 것**으로 보고한다. 통과로 적지 않는다
- Windows PowerShell 에서는 `.\gradlew.bat build`

## 케이스가 먼저다

- 10 §13 수용 검사 표와 각 절의 결과·거부 표(§4.3 거부, §4.6 오류 코드, §5 결과, §4.7 재전송·헤더)가 곧 테스트 목록이다
- 새 기능은 케이스를 테스트로 먼저 쓰고 구현한다. 테스트 이름에 10 절을 남긴다. `@DisplayName("10 §5 같은 generation 다른 본문 → 409 IDEMPOTENCY_CONFLICT")`
- 테스트는 `src/test/java/com/ttegeoji/backend/` 아래 소스와 같은 패키지

## 테스트 DB 는 Testcontainers 다(9/14 결정)

- **공유 Supabase(`DB_URL`)에 테스트를 붙이지 않는다.** 운영 데이터와 AI 파트 `ai` 스키마가 같은 인스턴스에 있다
- DB 가 필요한 테스트는 `support/PostgresContainerSupport` 를 상속한다. 싱글턴 Postgres 컨테이너에 `classpath:db/*.sql` 을 **파일명 순**으로 적용한다
  - `000a_roles.sql` role 3종 → `000b_base_schema_test.sql` 기존 테이블(테스트 전용 사본, 정본은 supabase/migrations) → `001~003` AI 저장소 마이그레이션 복사본 → `004_verdict_generation.sql` 초안 → `004b_*` 추가분
  - 테스트 프로필은 `ddl-auto: validate`. 엔티티가 SQL 과 다르면 컨텍스트가 뜨지 않는다
- 004 초안 원본은 한 파일이고 소유가 정해져 있다. 뒤 작업이 테이블을 더하면 `004b_{주제}.sql` 같은 추가분 파일로
- DB 없이 뜻이 있는 로직(해시·집계·점수·파싱)은 순수 단위 테스트로 쓴다. 컨테이너를 쓰지 않는다
- `PostgresContainerSupport` 가 생기기 전(W1 머지 전)의 `contextLoads` DB 연결 실패는 알려진 상태다. 지우거나 `@Disabled` 하지 않는다

## AI API·벤더 격리

- 테스트는 실제 AI API(`/internal/v1/intake`, trace)와 OpenAI·xAI 를 부르지 않는다. HTTP 클라이언트는 `MockRestServiceServer` 나 가짜 구현으로 바꾼다
- 워커 → 백엔드 방향(snapshot·begin-generation·finalize·generation-failed)은 `MockMvc` 로 요청을 재현한다. 헤더 5종과 서비스 토큰(10 §4.7)을 넣는다
- 워커가 기대하는 응답 모양은 AI 저장소 `tests/fakes/backend_app.py` 와 `contracts/fixtures/*.json`에서 가져와 픽스처로 쓸 수 있다. 복사하면 출처 경로를 주석에 남긴다
- 테스트에 실제 토큰·키·DB 비밀번호를 넣지 않는다. 서비스 토큰은 `application-test.yml` 의 테스트 값
