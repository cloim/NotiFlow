import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const appPath = resolve(here, "..", "src", "App.jsx");
const app = await readFile(appPath, "utf8");

const required = [
  "규칙",
  "실행 로그",
  "설정",
  "대상 앱",
  "설치된 앱 목록에서 선택",
  "조건",
  "우선순위",
  "헤더",
  "페이로드 템플릿",
  "알림 접근 권한",
  "리스너 활성화됨",
  "리스너 설정 열기",
  "아직 규칙이 없습니다",
  "실행 로그가 없습니다",
];

const forbidden = [
  "Rules",
  "Logs",
  "Settings",
  "New Rule",
  "Edit Rule",
  "Target Package",
  "Select app from installed list",
  "Rule Name",
  "Conditions",
  "Priority",
  "Headers",
  "Payload Template",
  "Save Changes",
  "Create Rule",
  "Select Target Package",
  "Search app label or package",
  "No installed apps found.",
  "No rules yet",
  "No logs found",
  "Permission Required",
  "Listener Active",
  "Open Listener Settings",
  "Browser mode",
];

const failures = [];

for (const text of required) {
  if (!app.includes(text)) {
    failures.push(`App.jsx must include Korean UI text: ${text}`);
  }
}

for (const text of forbidden) {
  const quoted = new RegExp(`["'\`]${text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`, "u");
  if (quoted.test(app)) {
    failures.push(`App.jsx must not include English UI text: ${text}`);
  }
}

if (failures.length > 0) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}

process.stdout.write("Korean UI contract passed\n");
