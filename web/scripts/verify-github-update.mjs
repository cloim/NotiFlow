import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const manifest = read("app/src/main/AndroidManifest.xml");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const update = read("app/src/main/java/com/vibe/notiflow/update/GitHubReleaseUpdate.kt");
const app = read("web/src/App.jsx");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

if (!manifest.includes("android.permission.REQUEST_INSTALL_PACKAGES")) {
  failures.push("AndroidManifest must request REQUEST_INSTALL_PACKAGES for APK handoff");
}
if (!manifest.includes("androidx.core.content.FileProvider")) {
  failures.push("AndroidManifest must register FileProvider for downloaded APKs");
}
if (!fs.existsSync(path.join(root, "app/src/main/res/xml/file_paths.xml"))) {
  failures.push("file_paths.xml must define update APK cache sharing path");
}
if (!update.includes("https://api.github.com/repos/cloim/NotiFlow/releases/latest")) {
  failures.push("Update code must query GitHub latest release API");
}
if (!mainActivity.includes("fun checkForUpdate")) {
  failures.push("Native bridge must expose checkForUpdate");
}
if (!mainActivity.includes("fun installUpdate")) {
  failures.push("Native bridge must expose installUpdate");
}
if (!mainActivity.includes("FileProvider.getUriForFile")) {
  failures.push("Native installer must share APK through FileProvider");
}
for (const text of ["checkForUpdate", "installUpdate", "업데이트 확인", "업데이트 설치", "새 버전"]) {
  if (!app.includes(text)) failures.push(`App UI must include ${text}`);
}
if (pkg.scripts["test:github-update"] !== "node scripts/verify-github-update.mjs") {
  failures.push("package.json must expose test:github-update");
}

if (failures.length) {
  console.error("GitHub update contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("GitHub update contract passed");
