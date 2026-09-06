# AWS 배포 가이드 (Elastic Beanstalk)

AWS 콘솔 로그인은 각자 계정으로 직접 해야 해서 이 문서는 따라 하는 절차서다.
순수 EC2를 직접 만지는 대신 **Elastic Beanstalk**(EC2 + 배포 자동화)을 쓴다 —
인스턴스는 결국 같은 t3.micro/t2.micro(프리티어)를 쓰지만, Java 설치·systemd 서비스 등록·
헬스체크 같은 걸 EB가 대신 해준다.

## 0. 사전 준비

- AWS 계정 (프리티어 12개월 이내인지 확인 — 초과하면 t3.micro도 과금됨)
- `aws configure`로 액세스 키 등록 (콘솔로만 할 거면 이 단계는 생략 가능)
- Supabase **Session Pooler** 연결 정보 (Direct connection은 IPv6 전용이라 AWS 기본 VPC에서
  또 못 붙는다 — 이미 이 프로젝트의 `.env.example`에 정확한 값이 들어가 있음):
  ```
  host: aws-0-ap-southeast-1.pooler.supabase.com
  port: 5432
  user: postgres.wfovlprcxmsanfuzbfvd
  db:   postgres
  ```

## 1. 배포용 jar 준비

```bash
./gradlew bootJar
# build/libs/backend-0.0.1-SNAPSHOT.jar 생성됨
```

Elastic Beanstalk의 **Java SE 플랫폼**은 jar 파일 하나를 그대로 업로드하는 걸 지원한다
(zip으로 묶을 필요 없음).

**Java 버전 확인부터 할 것.** 이 프로젝트는 로컬에 Java 25만 깔려 있어서 `build.gradle`의
`JavaLanguageVersion`을 25로 맞춰뒀는데, EB의 Corretto 플랫폼이 아직 25를 지원 안 할 수 있다.
AWS 콘솔 → Elastic Beanstalk → Create environment → Platform 드롭다운에서
**Corretto 몇 버전까지 있는지 먼저 확인**하고, 그보다 낮은 버전이면
`backend/build.gradle`의 `languageVersion`을 그 버전(21 LTS 권장)으로 낮춰서
`./gradlew bootJar`를 다시 돌려야 한다.

## 2. Elastic Beanstalk 환경 만들기 (콘솔)

1. AWS Console → **Elastic Beanstalk** → **Create application**
2. Application name: `geoji-server` (아무거나)
3. Platform: **Java**, Platform branch: 위에서 확인한 Corretto 버전
4. Application code: **Upload your code** → 1단계에서 만든 jar 업로드
5. Presets: **Single instance (Free tier eligible)** ← 이게 "제일 사양 낮은" 옵션.
   로드밸런서 없이 인스턴스 1대(t3.micro/t2.micro)만 뜬다.
6. **Create environment** (몇 분 걸림)

## 3. 환경변수 설정

환경이 뜨면: **Configuration → Software → Edit → Environment properties**에 추가.

| Key | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres` |
| `DB_USERNAME` | `postgres.wfovlprcxmsanfuzbfvd` |
| `DB_PASSWORD` | (실제 Supabase DB 비밀번호) |
| `SUPABASE_JWT_ISSUER_URI` | `https://wfovlprcxmsanfuzbfvd.supabase.co/auth/v1` |
| `PORT` | `5000` |

`PORT=5000`이 중요하다 — EB의 Java SE 플랫폼은 nginx가 80번 포트를 내부 5000번으로
프록시하는 구조라, 스프링이 5000번에서 떠 있어야 한다. `application.yml`에 이미
`server.port: ${PORT:8080}`으로 되어 있으니 이 환경변수만 넣으면 된다.

저장하면 환경이 자동으로 재배포된다.

## 4. 확인

```bash
curl http://<환경-URL>.elasticbeanstalk.com/actuator/health
# {"status":"UP"} 나오면 DB 연결까지 성공한 것
```

인증이 필요한 엔드포인트(`/api/me` 등)는 Supabase에서 실제 로그인해서 받은 access token으로
`Authorization: Bearer ...` 헤더를 붙여 테스트한다.

## 5. 이후 재배포

코드를 고친 뒤에는:
```bash
./gradlew bootJar
```
콘솔에서 새 버전(jar) 업로드 → 배포. EB CLI를 쓰면 `eb deploy` 한 줄로 끝난다
(`pip install awsebcli` 또는 `brew install awsebcli`로 설치, `eb init` 후 사용).

## 참고 — 보안그룹 / HTTPS

- EB가 자동으로 만드는 보안그룹은 인바운드 80(또는 443)이 열려 있어 별도 설정 없이 접속된다.
  Supabase로 나가는 아웃바운드는 기본 보안그룹의 "all traffic allow" 규칙에 이미 포함된다.
- 이 Single instance 프리셋은 **HTTP만** 제공한다. HTTPS가 필요해지면 로드밸런서가 있는
  프리셋으로 바꾸고 ACM 인증서를 붙여야 하는데, 그러면 프리티어 범위를 벗어날 수 있다.
  데모/해커톤 기간에는 HTTP로 충분하다.
