import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const secureStore = read("app/src/main/java/com/vibe/notiflow/data/local/SecureStore.kt");
const repository = read("app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt");
const dao = read("app/src/main/java/com/vibe/notiflow/data/local/Dao.kt");
const app = read("web/src/App.jsx");
const styles = read("web/src/styles.css");
const readme = read("README.md");
const architectureGuide = read("docs/superpowers/specs/2026-05-21-notiflow-architecture-guide.md");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "onShowFileChooser",
  "ACTION_CREATE_DOCUMENT",
  "application/json",
  "saveJsonFile",
  "clearPendingExport",
  "ensureJsonFileName",
  "endsWith(\".json\", ignoreCase = true)",
  "safeName == \".\"",
  "safeName == \"..\"",
  "fun exportRules(inputJson: String): String",
  "fun importRules(inputJson: String): String",
  "RuleTransfer.exportRules",
  "RuleTransfer.importRuleInputs",
  "importedTokenRefs",
  "rollbackImportedSecrets",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity must include ${text}`);
}

if (!secureStore.includes("fun removeSecret(alias: String)")) {
  failures.push("SecureStore must expose removeSecret");
}

if (!repository.includes("suspend fun upsertRules(rules: List<Rule>): List<Long>")) {
  failures.push("RuleRepository must expose upsertRules");
}

if (!dao.includes("suspend fun upsertAll(entities: List<RuleEntity>): List<Long>")) {
  failures.push("RuleDao must expose upsertAll");
}

if (pkg.scripts["test:rule-transfer"] !== "node scripts/verify-rule-transfer.mjs") {
  failures.push("package.json must expose test:rule-transfer");
}

for (const text of [
  "RuleExportSheet",
  "룰 내보내기",
  "룰 가져오기",
  "전체 선택",
  "전체 해제",
  "토큰 포함",
  "selectedExportRuleIds",
  "native.exportRules",
  "native.importRules",
  "native.saveJsonFile",
  "notiflow-rules-",
  "setTimeout(() => URL.revokeObjectURL(url)",
  "canExportRules",
  "canImportRules",
  "catch (error)",
  "error?.message ??",
]) {
  if (!app.includes(text)) failures.push(`App.jsx must include ${text}`);
}

if (app.includes("canRuleTransfer")) {
  failures.push("App.jsx must split canRuleTransfer into canExportRules and canImportRules");
}

for (const text of [
  "exportRules(inputJson: string): string(JSON)",
  "importRules(inputJson: string): string(JSON)",
  '"ruleIds": [1, 2]',
  '"includeSecrets": false',
  "data.export",
  "schemaVersion",
  "app",
  "exportedAt",
  "includeSecrets",
  "rules",
  "includeSecrets=true",
  "webhook.token",
  "plaintext",
  "exported JSON object/file content",
  "not the full bridge response envelope",
  "epoch milliseconds",
  "keeps existing rules",
  "adds new rules",
  "ignores file ids",
]) {
  if (!readme.includes(text)) failures.push(`README.md must document ${text}`);
}

for (const text of [
  "| `exportRules(inputJson)` | `{ data: { export } }` |",
  "| `importRules(inputJson)` | `{ data: { imported, ruleIds } }` |",
]) {
  if (!architectureGuide.includes(text)) failures.push(`Architecture guide must include ${text}`);
}

for (const text of [
  ".rule-transfer-card",
  ".rule-export-sheet",
  ".rule-export-item",
  ".rule-export-secret",
]) {
  if (!styles.includes(text)) failures.push(`styles.css must include ${text}`);
}

if (failures.length) {
  console.error("Rule transfer contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Rule transfer contract passed");
