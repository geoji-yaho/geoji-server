---
description: orca 코디네이터가 워크트리에 띄운 무인 워커 세션이 절차와 무관하게 지키는 것. 질문 경로, 공유 자원, 테스트 제약, 편집 범위
---

# orca 워커

프롬프트 첫머리에 Task ID·Dispatch ID 가 든 orca preamble 이 있으면 워커다. 절차는 `geoji-harness` 스킬 "orca 워커 모드", 마무리는 `git-workflow` 룰 "orca 워커" 절. 여기는 절차와 무관하게 늘 지키는 것이다. preamble 이 없는 세션은 이 룰을 무시한다.

## 질문

- 사람이 이 터미널을 보지 않는다. 물을 것은 preamble 의 `ask` 명령으로 코디네이터에게. 훅이 `AskUserQuestion`을 막는다
- 답 없이 임시값을 넣지 않는다. 10 에 없는 값, `(제안)`·§14 미결의 채택 여부는 사람 세션에서도 묻는 것이 규칙이고, 워커에서는 그 상대가 코디네이터다
- 스펙의 "미리 답한 결정"이 룰의 일반 문장보다 우선한다. 스펙에 **미결** 로 적힌 항목은 시작 전에 `ask`
- 타임아웃이면 같은 message ID 로 `--resume`. 질문을 새로 만들지 않는다

## 공유 자원. 스펙에 없으면 ask

여러 워커가 동시에 건드리면 머지에서 부딪히거나 다른 워커의 빌드를 깨는 것들이다.

- `build.gradle`·`settings.gradle`·`gradle/`(의존성·wrapper)
- `src/main/resources/application.yml`
- `config/**`(인증 체인·오류 본문·설정 빈)
- `domain/enums/`(Postgres enum 과 짝)
- `API.md`. 웨이브마다 한 워커만 쓴다
- `.github/workflows/`, `docs/deploy-aws.md`(배포)
- `src/test/resources/db/`. `000*`·`001~003` 은 기반 작업 소유, `004_verdict_generation.sql` 은 스키마 작업 소유. 테이블을 더하는 워커는 `004b_{주제}.sql` 추가분만
- 앞 웨이브가 만든 repository·엔티티 파일. 조회가 더 필요하면 자기 `*Queries` 클래스에
- `docs/10-backend-contract.md` 는 누구도 편집하지 않는다(훅이 막는다)

## 테스트

- 공유 Supabase 에 붙지 않는다. DB 테스트는 `support/PostgresContainerSupport`(Testcontainers)만
- JDK 25 나 Docker 가 워크트리 환경에 없으면 게이트를 못 돌린 것으로 보고한다. 통과로 적지 않는다
- 기반 작업(`feat-infra`) 머지 전의 `contextLoads` DB 실패는 알려진 상태다. 다른 실패와 구분해 적는다

## 편집 범위

- 스펙 Ownership 의 `owned` 밖과 `forbidden` 안은 훅이 막는다. 필요하면 `ask`로 범위를 넓혀 받는다. 우회하지 않는다
- `_workspace/`는 늘 쓸 수 있다. 커밋되지 않는다

## 형제 저장소

- 워크트리는 `~/orca/workspaces/geoji-server/` 아래라 `../geoji-agent`가 없다. `CLAUDE.md`의 `GEOJIBANG_ROOT` 규칙대로 찾는다. 형제 저장소는 읽기·복사·`uv run` 계산만 한다
