---
description: Gradle 게이트, 테스트 DB 미정, AI API·벤더 격리, 10 수용 검사를 테스트로 옮기는 법
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
- JDK 25 가 없으면 Gradle 이 시작하지 않는다. 그때는 게이트를 **돌리지 못한 것**으로 보고한다. 통과로 적지 않는다
- Windows PowerShell 에서는 `.\gradlew.bat build`

## 케이스가 먼저다

- 10 §13 수용 검사 표와 각 절의 결과·거부 표(§4.3 거부, §4.6 오류 코드, §5 결과, §4.7 재전송·헤더)가 곧 테스트 목록이다
- 새 기능은 케이스를 테스트로 먼저 쓰고 구현한다. 테스트 이름에 10 절을 남긴다. `@DisplayName("10 §5 같은 generation 다른 본문 → 409 IDEMPOTENCY_CONFLICT")`
- 테스트는 `src/test/java/com/ttegeoji/backend/` 아래 소스와 같은 패키지

## 테스트 DB 는 아직 정하지 않았다

- **공유 Supabase(`DB_URL`)에 테스트를 붙이지 않는다.** 운영 데이터와 AI 파트 `ai` 스키마가 같은 인스턴스에 있다
- 기존 `TtegeojiBackendApplicationTests.contextLoads` 는 `ddl-auto: validate` 라 DB 없이 실패한다. 이 실패는 알려진 상태로 보고하고 테스트를 지우거나 `@Disabled` 하지 않는다
- 방식(Testcontainers, 로컬 Postgres, 슬라이스 테스트만)이 정해지기 전에는 새 테스트를 DB 없이 도는 것으로 쓴다. 순수 단위 테스트, `@WebMvcTest` + mock repository. SQL·잠금·`ON CONFLICT`·트랜잭션 경합처럼 DB 가 있어야 의미가 있는 케이스는 `approval` 에 올리고 "확인하지 못한 것"에 적는다
- 테스트 의존성 추가(testcontainers 등)는 승인 대상이다

## AI API·벤더 격리

- 테스트는 실제 AI API(`/internal/v1/intake`)와 OpenAI·xAI 를 부르지 않는다. HTTP 클라이언트는 mock 서버나 가짜 구현으로 바꾼다
- 워커 → 백엔드 방향(snapshot·begin-generation·finalize·generation-failed)은 `MockMvc` 로 요청을 재현한다. 헤더 5종과 서비스 토큰(10 §4.7)을 넣는다
- 워커가 기대하는 응답 모양은 AI 저장소 `tests/fakes/backend_app.py` 와 `contracts/fixtures/*.json`에서 가져와 픽스처로 쓸 수 있다. 복사하면 출처 경로를 주석에 남긴다
- 테스트에 실제 토큰·키·DB 비밀번호를 넣지 않는다
