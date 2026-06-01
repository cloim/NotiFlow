import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const css = read("web/src/styles.css");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "function RuleCard({ rule, onEdit, onToggle, onDelete, onCopy, busy })",
  "Icon.Copy",
  "<Icon.Copy /> 복사",
  "onCopy(rule)",
  "copyRule",
  "native.copyRule(rule.id)",
  "규칙을 복사했습니다.",
  "onCopy={copyRule}",
]) {
  if (!app.includes(text)) failures.push(`App.jsx must include ${text}`);
}

for (const text of [
  "private fun copiedRuleName",
  "@JavascriptInterface",
  "fun copyRule(ruleId: Long): String",
  "getRuleById(ruleId)",
  "copy(id = 0, name = copiedRuleName(existing.name))",
  "sourceRuleId",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity.kt must include ${text}`);
}

if (!css.includes(".rule-btn.copy:active")) {
  failures.push("styles.css must style the copy rule button active state");
}

if (pkg.scripts["test:rule-copy"] !== "node scripts/verify-rule-copy.mjs") {
  failures.push("package.json must expose test:rule-copy");
}

if (failures.length) {
  console.error("Rule copy contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Rule copy contract passed");
