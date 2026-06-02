import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const dao = read("app/src/main/java/com/vibe/notiflow/data/local/Dao.kt");
const repo = read("app/src/main/java/com/vibe/notiflow/domain/repo/RuleRepository.kt");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const css = read("web/src/styles.css");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "suspend fun searchReceivedNotifications(",
  "receivedAt >= :from",
  "receivedAt <= :to",
  "title LIKE '%' || :keyword || '%'",
  "body LIKE '%' || :keyword || '%'",
  "sender LIKE '%' || :keyword || '%'",
  "dataJson LIKE '%' || :keyword || '%'",
]) {
  if (!dao.includes(text)) failures.push(`Dao.kt must include ${text}`);
}

if (!repo.includes("suspend fun searchReceivedNotifications(")) {
  failures.push("RuleRepository must expose searchReceivedNotifications");
}

for (const text of [
  "fun listReceivedNotifications(inputJson: String): String",
  "val input = JSONObject(inputJson)",
  "val keyword = input.optString(\"keyword\").trim()",
  "val from = if (input.has(\"from\")) input.optLong(\"from\") else null",
  "val to = if (input.has(\"to\")) input.optLong(\"to\") else null",
  "searchReceivedNotifications(",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity.kt must include ${text}`);
}

for (const text of [
  "receivedFilters",
  "function defaultReceivedFilters()",
  "Date.now() - 30 * 60 * 1000",
  "from: dateToDateTimeLocalValue",
  "useState(defaultReceivedFilters)",
  "receivedHasSearched",
  "selectedReceivedNotification",
  "function ReceivedNotificationDetailSheet",
  "function receivedPreview",
  "native.listReceivedNotifications(JSON.stringify",
  "onClick={searchReceivedNotifications}",
  "검색 조건을 입력한 뒤 조회를 누르세요.",
]) {
  if (!app.includes(text)) failures.push(`App.jsx must include ${text}`);
}

for (const text of [
  ".received-search-card",
  ".received-item-preview",
  ".received-detail-row",
]) {
  if (!css.includes(text)) failures.push(`styles.css must include ${text}`);
}

if (app.includes('if (tab !== "received") return;')) {
  failures.push("App.jsx must not auto-load received notifications on tab entry");
}

if (app.includes("loadRules(); loadLogs(); loadReceivedNotifications(); loadPerm();")) {
  failures.push("App.jsx refresh must not load received notifications on app entry");
}

if (pkg.scripts["test:received-search"] !== "node scripts/verify-received-notification-search.mjs") {
  failures.push("package.json must expose test:received-search");
}

if (failures.length) {
  console.error("Received notification search contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Received notification search contract passed");
