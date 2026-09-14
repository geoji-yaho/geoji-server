<!-- orca 워커 작업 스펙 틀. 코디네이터가 /orca-plan 으로 채워 _workspace/orca/tasks/{이름}.md 에 두고
     `orca orchestration worker-start --spec "$(cat 그 파일)"` 로 통째로 넘긴다. Claude Code 는 이 틀을 읽지 않는다 -->

# 작업: {feat-topic}

이 스펙은 orca 워커(Claude Code)에게 주는 전체 지시다. `geoji-harness` 스킬의 "orca 워커 모드"로 진행한다. 이 스펙을 그대로 `_workspace/orca/spec.md`에 저장하는 것이 첫 동작이다.

## Target (대상)

- 10 절·§0.1 줄: {10 §4.3 begin-generation. §0.1 9/14 "내부 API 5종 상태 기계" 줄}
- 브랜치: `{feat-topic}`. orca 가 워크트리 이름으로 만든다. 바꾸지 않는다

## Change (만들 것)

코디네이터가 10 과 서버 코드를 대조해 정한 파일. 표에 없는 파일은 넣지 않는다.

| 경로 | 신규/수정 | 무엇 | 10 절 |
| ---- | --------- | ---- | ----- |
|      |           |      |       |

테스트. 10 §13 줄과 절의 결과·거부 표 행. DB 필요 여부.

| 테스트 클래스 | 케이스 | DB 필요 |
| ------------- | ------ | ------- |
|               |        |         |

## Constraints (제약)

- 10 에 없는 값은 만들지 않는다. 막히면 preamble 의 `ask`
- "미리 답한 결정" 밖의 `(제안)`·§14 미결·승인 항목은 `ask`. 임시값 금지
- `orca-worker` 룰의 공유 자원은 스펙에 없으면 건드리지 않는다
- 공유 Supabase 에 테스트를 붙이지 않는다. 실제 AI API·벤더를 부르지 않는다
- `docs/10-backend-contract.md` 는 고치지 않는다. 다른 점은 보고서 "AI 파트 회신"에
- {이 작업만의 제약. 예: 잠금 순서 privacy scope → verdict → job}

## Ownership (편집 범위)

훅이 이 블록으로 Write/Edit 를 막는다. 글롭은 저장소 루트 기준, `_workspace/` 는 늘 허용.

```json
{
  "owned": ["src/main/java/com/ttegeoji/backend/api/InternalVerdictController.java", "src/test/java/com/ttegeoji/backend/api/InternalVerdict*Test.java"],
  "forbidden": ["build.gradle", "src/main/resources/application.yml", "src/main/java/com/ttegeoji/backend/config/**", "docs/**", ".github/**"]
}
```

- 같은 웨이브의 다른 워커 담당: {브랜치 — 파일 목록. 없으면 "없음"}

## 미리 답한 결정

코디네이터가 10(§14, `(제안)`)과 사용자에게서 미리 받은 답. 게이트 A·B 는 이 표로 먼저 푼다.

| 항목 | 답 | 출처 |
| ---- | -- | ---- |
| {없으면 "없음"} |    |      |

## Observable acceptance (완료 증거)

- `./gradlew build` 통과. 기존 `contextLoads` DB 실패만 남으면 구분해 보고. JDK 가 없으면 못 돌린 것으로 보고
- 케이스 {n}개가 테스트에 있고 통과. 빠진 케이스 0. DB 필요 케이스는 보류로 명시
- `git-workflow` 룰 "orca 워커" 절대로 PR 이 열려 있고, `worker_done` body 에 PR URL 과 `--report-path`
