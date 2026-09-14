---
description: 브랜치 전략(main push = EB 배포)과 커밋 메시지 형식, git add 하지 않는 것, orca 워커 마무리
---

# Git 워크플로우

## 브랜치

- `main` 하나다. **`main` push 가 곧 Elastic Beanstalk 배포다**(`.github/workflows/deploy.yml`). 작업은 `main`에서 `feat/{topic}`·`fix/{topic}`·`docs/{topic}`으로 분기하고 PR로 `main`에 머지한다
- 한두 줄짜리 문서 갱신(`API.md`, `docs/deploy-aws.md`, 10 사본 동기화)은 `main`에 직접 커밋해도 된다. 코드는 직접 커밋하지 않는다
- "커밋하고 푸시해줘"는 두 동작을 한 번에 한다. main 이면 배포가 나간다는 것을 한 줄로 알린다

## 커밋 메시지

- 제목은 `type(scope): 한국어 제목`. type은 `docs`, `feat`, `fix`, `test`, `chore`, `refactor`. 9/14 이전 이력은 영어 한 줄이지만 이후로는 이 형식을 쓴다
- scope 는 기능 단위다. `verdict`, `jobs`, `internal-api`, `finalize`, `watchdog`, `privacy`, `submission`, `api-docs`, `contract`, `deploy`. 10 의 절을 옮긴 커밋은 제목에 절을 넣는다. `feat(watchdog): 10 §6 마감 초과 PENDING 폴백`
- 10 사본 동기화는 `docs(contract): 9/NN 10 사본 동기화 — geoji-agent <sha>`
- 나열은 가운뎃점(·), 구분은 em dash(—) 또는 `+`
- 본문은 `- ` 불릿으로 무엇을 왜 바꿨는지 쓴다. 10 은 번호로만 부른다. `10 §4.3`, `10 §0.1 D-24 줄`
- 한 커밋은 한 기능의 전파다. 공개 API 를 바꿨으면 `API.md` 를 같은 커밋에 넣는다
- Claude Code가 주는 `Co-Authored-By`와 `Claude-Session` 트레일러를 붙인다

## git add 하지 않는 것

- `.env`. 키 이름만 `.env.example`에 둔다. 훅이 막는다
- `_workspace/`, `_workspace_prev/`. 하네스 중간 산출물(`.gitignore`)
- `build/`, `.gradle/`, IDE 파일. `.gitignore` 에 있다
- `git push --force`는 어디서든 쓰지 않는다. 훅이 막는다

## orca 워커(무인)

orca 코디네이터가 워크트리에 띄운 워커 세션에만 적용된다. 사람이 보는 세션은 위 절만 따른다.

- 브랜치는 orca 가 워크트리 이름으로 만든다. 이름은 `feat-{topic}`. orca 가 `/`를 `-`로 바꾸므로 처음부터 대시로 짓는다. 워커는 브랜치를 새로 만들거나 옮기지 않는다
- main 에 커밋하지 않는다. 훅이 막는다. main 머지가 배포이므로 더더욱
- 마무리 순서. 게이트 통과 → `git add <경로>`(경로를 지정한다. `-A` 금지) → 커밋(형식은 위와 같다. 제목에 10 절. `feat(jobs): 10 §3 SENTENCE job INSERT·D-24 게이트`) → `git fetch origin main && git rebase origin/main` → 충돌이면 풀지 말고 preamble 의 `ask` → `git push -u origin HEAD` → `gh pr create --base main --title "<커밋 제목>" --body-file _workspace/04_report.md`
- PR 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`와 세션 URL 을 붙인다
- 워커는 머지하지 않는다. PR URL 을 `worker_done` body 에 넣는다. 승인·머지·워크트리 정리는 코디네이터가 한다(`/orca-plan` 9~11, 9/15 사용자 지시)
