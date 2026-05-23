import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const pcServer = read("app/src/main/java/com/vibe/notiflow/pc/PcSettingsServer.kt");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "pkg-picker-btn",
  "openPackagePicker",
  "picker-dialog",
  "picker-query",
  "includeSystemApps",
  "group-card",
  "addGroup",
  "addCondInGroup",
  "removeCondInGroup",
  "expressionRowsForSubmit",
  "expr-preview",
  "removeToken",
  "diff-box",
]) {
  if (!pcServer.includes(text)) failures.push(`PC settings rule form must include ${text}`);
}

for (const label of ["본문 포함", "제목 포함", "본문 정규식", "그룹 내부", "다음 그룹"]) {
  if (!pcServer.includes(label)) failures.push(`PC settings rule form must include label: ${label}`);
}

if (pcServer.includes('id="conditionRows"')) {
  failures.push("PC settings rule form must not expose raw conditionRows JSON textarea");
}

if (!pcServer.includes('/api/apps?includeSystem=')) {
  failures.push("PC settings package picker must reload installed apps with includeSystem");
}

if (pkg.scripts["test:pc-rule-form"] !== "node scripts/verify-pc-rule-form-parity.mjs") {
  failures.push("package.json must expose test:pc-rule-form");
}

if (failures.length) {
  console.error("PC rule form parity contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("PC rule form parity contract passed");
