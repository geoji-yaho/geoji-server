// Claude Code 훅. settings.json 이 PreToolUse(Edit·Write·MultiEdit·NotebookEdit·Bash·AskUserQuestion)와 Stop 에 건다.
//
// 막을 때는 exit 2 와 stderr 한 줄. 통과는 exit 0. 실행은 `node .claude/hooks/guard.mjs`. 의존성 없음.
//
// 두 층이다.
// - 늘: `git add .env`, `git push --force`, 10 사본(`docs/10-backend-contract.md`) 편집
// - orca 워커 모드(`_workspace/orca/spec.md` 가 있을 때): 스펙 Ownership 밖 편집, main 커밋·체크아웃, 머지,
//   AskUserQuestion, worker_done 없이 끝내기(`_workspace/orca/done` 없음)

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";

const SPEC = "_workspace/orca/spec.md";
const DONE = "_workspace/orca/done";
const ALWAYS_WRITABLE = ["_workspace/"];
const READ_ONLY = ["docs/10-backend-contract.md"];

function deny(msg) {
  process.stderr.write(`[guard] ${msg}\n`);
  process.exit(2);
}

function readStdin() {
  try {
    return JSON.parse(readFileSync(0, "utf8"));
  } catch {
    return null;
  }
}

// 워커 모드가 아니면 null. 워커 모드면 스펙의 ```json 블록 중 owned 가 든 첫 블록을 읽는다
function loadOwnership() {
  if (!existsSync(SPEC)) return null;
  const text = readFileSync(SPEC, "utf8");
  for (const m of text.matchAll(/```json\s*(\{[\s\S]*?\})\s*```/g)) {
    try {
      const d = JSON.parse(m[1]);
      if ("owned" in d) return { owned: d.owned ?? [], forbidden: d.forbidden ?? [] };
    } catch {
      // 다음 블록
    }
  }
  return { owned: [], forbidden: [] };
}

function relpath(file, cwd) {
  const abs = path.resolve(cwd, file);
  const rel = path.relative(path.resolve(cwd), abs);
  if (rel.startsWith("..") || path.isAbsolute(rel)) return null;
  return rel.split(path.sep).join("/");
}

// fnmatch 비슷하게. `**` 는 경로 구분자를 넘고 `*`·`?` 는 넘지 않는다
function globToRegExp(glob) {
  let re = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        re += ".*";
        i++;
        if (glob[i + 1] === "/") i++;
      } else {
        re += "[^/]*";
      }
    } else if (c === "?") {
      re += "[^/]";
    } else {
      re += c.replace(/[.+^${}()|[\]\\]/g, "\\$&");
    }
  }
  return new RegExp(`^${re}$`);
}

function matches(rel, globs) {
  return globs.some((raw) => {
    const g = raw.replaceAll("\\", "/");
    if (g.endsWith("/")) return rel.startsWith(g);
    const base = g.replace(/\/+$/, "");
    return globToRegExp(g).test(rel) || globToRegExp(`${base}/**`).test(rel);
  });
}

function currentBranch() {
  try {
    // rev-parse 는 커밋이 없는 브랜치에서 실패한다. symbolic-ref 는 그때도 이름을 준다
    return execFileSync("git", ["symbolic-ref", "--short", "-q", "HEAD"], {
      encoding: "utf8",
      timeout: 5000,
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return "";
  }
}

function guardEdit(inp, cwd, own) {
  const fp = inp.file_path || inp.notebook_path;
  if (!fp) return;
  const rel = relpath(fp, cwd);
  if (rel !== null && READ_ONLY.includes(rel)) {
    deny(
      `${rel} 은 AI 저장소 docs/plans/10 의 읽기 전용 사본이다. 고칠 것은 보고서에 적고, 동기화는 cp 로 한다(backend-contract 룰)`,
    );
  }
  if (own === null) return;
  if (rel === null) deny(`워커 모드: 저장소 밖 파일은 편집하지 않는다 — ${fp}`);
  if (ALWAYS_WRITABLE.some((p) => rel.startsWith(p))) return;
  if (matches(rel, own.forbidden)) {
    deny(`워커 모드: 스펙 Ownership 의 forbidden 경로 — ${rel}. 필요하면 orca ask 로 범위를 받는다`);
  }
  if (own.owned.length > 0 && !matches(rel, own.owned)) {
    deny(`워커 모드: 스펙 Ownership 의 owned 밖 — ${rel}. 필요하면 orca ask 로 범위를 받는다`);
  }
}

function guardBash(cmd, worker) {
  // 같은 명령 조각(줄·;·&&·| 로 끊기기 전) 안의 인자만 본다. 커밋 메시지나 heredoc 본문의 글자에는 걸리지 않게
  const seg = "[^\\n;&|]*";
  if (new RegExp(`\\bgit\\s+add\\b${seg}[\\s'"/]\\.env(?![.\\w-])`).test(cmd)) {
    deny(".env 는 git add 하지 않는다. 키 이름만 .env.example 에 둔다");
  }
  if (new RegExp(`\\bgit\\s+push\\b${seg}\\s(--force(?!-with-lease)\\b|-f\\b)`).test(cmd)) {
    deny("git push --force 는 쓰지 않는다");
  }
  if (!worker) return;
  if (/\bgit\s+(checkout|switch)\s+(main|master)\b/.test(cmd)) {
    deny("워커 모드: main 으로 옮기지 않는다. 자기 브랜치에서만 작업한다");
  }
  if (/\bgit\s+merge\b/.test(cmd) || /\bgh\s+pr\s+merge\b/.test(cmd)) {
    deny("워커 모드: 머지하지 않는다. PR 을 열고 worker_done 에 URL 을 넣는다");
  }
  if (/\bgit\s+commit\b/.test(cmd) && ["main", "master"].includes(currentBranch())) {
    deny("워커 모드: main 에 커밋하지 않는다. orca 가 만든 feat-* 브랜치인지 확인한다");
  }
}

function main() {
  const data = readStdin();
  if (!data) return;
  const cwd = data.cwd || process.cwd();
  process.chdir(cwd);
  const own = loadOwnership();
  const worker = own !== null;
  const event = data.hook_event_name || "";
  const tool = data.tool_name || "";
  const inp = data.tool_input || {};

  if (event === "Stop") {
    if (worker && !existsSync(DONE) && !data.stop_hook_active) {
      deny(
        "워커 모드: worker_done 을 보내기 전에는 끝내지 않는다. " +
          "보고서를 쓰고 preamble 의 send --type worker_done 을 보낸 뒤 _workspace/orca/done 을 만든다",
      );
    }
    return;
  }

  if (event !== "PreToolUse") return;
  if (["Edit", "Write", "MultiEdit", "NotebookEdit"].includes(tool)) {
    guardEdit(inp, cwd, own);
  } else if (tool === "Bash") {
    guardBash(String(inp.command ?? ""), worker);
  } else if (tool === "AskUserQuestion" && worker) {
    deny("워커 모드: 사람이 이 터미널을 보지 않는다. preamble 의 orca orchestration ask 로 코디네이터에게 묻는다");
  }
}

main();
