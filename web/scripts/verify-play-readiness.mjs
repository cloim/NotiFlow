import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const appPath = resolve(here, "..", "src", "App.jsx");
const docsPath = resolve(here, "..", "..", "docs", "play-store-readiness.md");

const [app, docs] = await Promise.all([
  readFile(appPath, "utf8"),
  readFile(docsPath, "utf8").catch(() => ""),
]);

const failures = [];

if (!/const\s+\[includeSystemApps,\s*setIncludeSystemApps\]\s*=\s*useState\(false\);/.test(app)) {
  failures.push("App picker must default the include-system-apps toggle to false");
}

if (!app.includes("설치된 앱 목록은 이 규칙의 알림 출처를 고르기 위해서만 표시됩니다.")) {
  failures.push("App picker must explain why installed apps are shown");
}

if (!docs.includes("QUERY_ALL_PACKAGES")) {
  failures.push("Play readiness docs must cover QUERY_ALL_PACKAGES");
}

if (!docs.includes("Only the selected package name is saved")) {
  failures.push("Play readiness docs must state that only the selected package name is saved");
}

if (!docs.includes("core rule creation flow")) {
  failures.push("Play readiness docs must explain why package visibility is core to rule creation");
}

if (failures.length > 0) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}

process.stdout.write("Play readiness contract passed\n");
