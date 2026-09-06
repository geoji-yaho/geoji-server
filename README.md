# 떼거지 백엔드 (Spring Boot)

Supabase Postgres에 직접 붙는 API 서버. 인증은 Supabase Auth가 발급한 JWT를 그대로 검증한다
(Spring Boot는 Resource Server 역할만 함 — 로그인/회원가입 로직 없음).

## 구조

- `domain/` — JPA 엔티티. `supabase/migrations/0001_init.sql` 스키마와 1:1 매핑.
- `repository/` — Spring Data JPA repository.
- `api/` — REST 컨트롤러.
- `dto/` — 요청/응답 record.
- `config/SecurityConfig` — JWT 검증 설정 (JWKS는 issuer-uri로 자동 discovery).
- `security/CurrentUser` — JWT `sub` 클레임(=profiles.id) 추출 헬퍼.

## 실행 전 준비

1. `.env.example`을 참고해서 아래 환경변수를 실제로 export 하거나 IDE 실행 설정에 넣는다.
   (이 프로젝트는 `.env` 파일을 자동으로 읽지 않는다.)
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
   - `SUPABASE_JWT_ISSUER_URI`
2. **Direct connection(`db.<ref>.supabase.co`)은 IPv6 전용이다.** `.env.example`은 이미
   Session Pooler(IPv4) 주소로 채워져 있음 — 대부분의 사내망/AWS 기본 VPC가 여기 해당하니
   Direct connection으로 바꾸지 말 것.

## 실행

```bash
./gradlew bootRun
```

## 확인된 것 / 안 된 것

- `./gradlew build` 성공 (컴파일 + 패키징 검증 완료)
- Supabase JWKS(`/auth/v1/.well-known/jwks.json`) 응답 확인 → `SecurityConfig`의 issuer-uri 설정이
  실제로 맞는 값임을 확인함
- Session Pooler 호스트(`aws-0-ap-southeast-1.pooler.supabase.com`)가 IPv4로 응답하는 것까지 확인함
- **실제 DB에 붙여서 `bootRun`까지 돌려보지는 못했다** — 이 개발 환경 자체가 Supabase Direct
  connection(IPv6)에 못 붙는 네트워크라서 (Session Pooler는 애초에 이 상황을 위한 것).
  로컬에서 처음 실행할 때 연결 에러가 나면 `.env.example` 값을 그대로 썼는지부터 확인할 것.

## AWS 배포

Elastic Beanstalk(Java 플랫폼, 단일 인스턴스 t3.micro/프리티어)에 올리는 걸 추천한다.
자세한 절차는 [`docs/deploy-aws.md`](docs/deploy-aws.md) 참고.
