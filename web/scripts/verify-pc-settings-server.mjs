import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const css = read("web/src/styles.css");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const server = read("app/src/main/java/com/vibe/notiflow/pc/PcSettingsServer.kt");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "PcSettingsServer",
  "startPcSettingsServer",
  "stopPcSettingsServer",
  "getPcSettingsServerStatus",
  "setPcSettingsServerToken",
  "pcSettingsServerHandler",
  "PREF_PC_SETTINGS_TOKEN",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity must include ${text}`);
}

for (const text of [
  "ServerSocket",
  "X-NotiFlow-Token",
  "/api/rules",
  "/api/logs",
  "/api/apps",
  "UNAUTHORIZED_HTML",
  "ADMIN_HTML",
]) {
  if (!server.includes(text)) failures.push(`PcSettingsServer must include ${text}`);
}

for (const text of [
  "PC 설정 서버",
  "pcServerInfo",
  "pcServerTokenDraft",
  "savePcSettingsServerToken",
  "startPcSettingsServer",
  "stopPcSettingsServer",
  "접속 토큰",
  "pc-server-card",
]) {
  if (!app.includes(text)) failures.push(`App must include ${text}`);
}

if (!css.includes(".pc-server-card") || !css.includes(".pc-server-url") || !css.includes(".pc-server-token-row")) {
  failures.push("styles.css must style the PC settings server card, token input, and URL");
}
if (pkg.scripts["test:pc-settings-server"] !== "node scripts/verify-pc-settings-server.mjs") {
  failures.push("package.json must expose test:pc-settings-server");
}

if (failures.length) {
  console.error("PC settings server contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("PC settings server contract passed");
