---
description: 10 계약서의 절이나 §0.1 할 일 묶음을 orca 워커 스펙 여러 장과 웨이브 순서로 나누고, 확인 뒤 워크트리마다 워커를 띄워 감독한다
argument-hint: "[10 §6 | 10 §4.1,§4.3,§4.6 | §0.1 9/14 줄 전부]"
---

# orca-plan

코디네이터 쪽 절차다. 이 세션은 사람이 보고 있는 main 워크트리에서 돈다. 워커 쪽 규칙은 `geoji-harness` 스킬의 "orca 워커 모드"와 `orca-worker` 룰, 마무리는 `git-workflow` 룰 "orca 워커" 절에 있다.

감독 루프 자체(`run-create`, `worker-start`, `check --wait`, `reply`, `worker-release`)는 전역 `orchestration` 스킬이 정본이다. 먼저 `orca skills get orchestration`으로 읽는다. 이 커맨드는 스펙을 어떻게 만들고 어느 순서로 띄우는지만 정한다.

## 절차

1. **사본 확인.** `diff -q docs/10-backend-contract.md "$GEOJIBANG_ROOT/geoji-agent/docs/plans/10-backend-contract.md"`. 다르면 멈추고 동기화를 먼저 묻는다
2. **대상 확정.** `$ARGUMENTS`를 10 §0 읽는 법 표로 절·§0.1 줄로 바꾼다. "§0.1 9/14 줄 전부"면 그 날짜 줄이 가리키는 절 전부
3. **선행 확인.** 10 은 계층이 쌓인다. DDL·엔티티(§2) → job INSERT(§3) → 내부 API(§4) → finalize(§5) → watchdog·스케줄러(§6·§7) → 무효화(§8) → 공개 API(§9). 앞 층이 main 에 머지돼 있는지 `git log origin/main`과 코드로 본다. 안 돼 있으면 멈추고 무엇이 먼저인지 알린다. DDL·엔티티가 없으면 그것 하나만 워커 하나로 띄운다
4. **분할.** 파일 단위로 나눈다. 같은 파일을 두 스펙에 넣지 않는다. `orca-worker` 룰의 공유 자원(`build.gradle`, `application.yml`, `SecurityConfig`, `ApiExceptionHandler`, `domain/enums/`, `API.md`)을 바꾸는 스펙은 웨이브 맨 앞에 혼자 둔다. 워커 하나에 파일이 두 자리 수를 넘으면 더 나눈다
5. **스펙 작성.** `.claude/templates/orca-task.md`를 `_workspace/orca/tasks/{feat-topic}.md`로. 이름은 `feat-topic`(orca 가 `/`를 `-`로 바꾸므로 처음부터 대시). 미결(10 `(제안)`, §14, 004 위치, 테스트 DB, `posts`↔`expenses` 대응)에 걸리는 스펙은 지금 `AskUserQuestion`으로 사용자에게 묻고 답을 "미리 답한 결정"에 적는다. 워커가 나중에 `ask`로 올리면 한 워커가 한참 논다
6. **웨이브 표.** `_workspace/orca/plan.md`에 웨이브별 스펙·파일·10 절·선행을 표로 쓰고 사용자에게 보여 준다. 확인 전에는 띄우지 않는다
7. **띄운다.** 한 웨이브를 한 번에.
   ```bash
   orca status --json
   orca orchestration run-create --objective "{10 §n 웨이브 1}" --json
   orca orchestration worker-start --spec "$(cat _workspace/orca/tasks/feat-topic.md)" \
     --worktree new-top-level --name feat-topic --agent claude --setup run --json
   ```
   PowerShell 이면 `--spec (Get-Content -Raw 파일)`. `--worktree new-top-level`. 워커끼리 부모 관계를 두지 않는다. 워크트리는 `~/orca/workspaces/geoji-server/{이름}`에 생긴다
8. **감독.** `orchestration` 스킬대로 `check --wait --types "worker_done,escalation,question"`. `question`은 10 에서 답할 수 있으면 답하고, 아니면 사용자에게 묻고 `reply`. `worker_done`은 `--outcome`과 body 의 PR URL 을 확인한다. 성공이면 `worker-release`, 실패면 body 의 이유를 사용자에게 옮기고 재시도 여부를 묻는다
9. **웨이브 종료.** PR 목록, 각 워커의 "확인하지 못한 것"(DB·JDK), "AI 파트 회신"을 모아 사용자에게. **머지는 사용자가 한다. main 머지가 곧 EB 배포다.** 다음 웨이브는 머지 뒤 main 에서 다시 3부터
10. **정리.** 워커 워크트리는 PR 머지 뒤 `orca worktree rm`. 사용자가 시킬 때만

## 하지 않는 것

- 코디네이터가 워커의 파일을 직접 고치지 않는다. 지적은 `send --to dispatch:<id>`로 되돌린다
- 워커 대신 커밋·PR 을 만들지 않는다
- 10 사본을 고치지 않는다. 모인 회신을 AI 저장소로 옮기는 것은 사용자가 시킬 때만
- 워커 하나가 `ask`로 막혀 있는데 다른 워커를 더 띄워 덮지 않는다. 먼저 답한다
