import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const css = read("web/src/styles.css");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

if (!app.includes('className="app-topbar"')) {
  failures.push("App must render a shared app-topbar");
}
if (!app.includes("topbarTitle") || !app.includes("topbarSubtitle") || !app.includes("topbarAction")) {
  failures.push("App must derive title, subtitle, and refresh action from current tab");
}
if (app.includes('className="page-hdr"')) {
  failures.push("Tab pages must not render per-tab page-hdr blocks");
}
if (!css.includes("--topbar-h")) {
  failures.push("CSS must define a stable topbar height variable");
}
if (!/\.app-topbar\s*\{[\s\S]*?position:\s*fixed/.test(css)) {
  failures.push(".app-topbar must be fixed at the top");
}
const appTopbar = css.match(/\.app-topbar\s*\{(?<body>[\s\S]*?)\}/)?.groups?.body ?? "";
if (!appTopbar.includes("top: var(--safe-t)")) {
  failures.push(".app-topbar must be offset below the system status bar safe area");
}
if (!appTopbar.includes("height: var(--topbar-h)")) {
  failures.push(".app-topbar height must stay independent from the system status bar safe area");
}
const tabContent = css.match(/\.tab-content\s*\{(?<body>[\s\S]*?)\}/)?.groups?.body ?? "";
if (!tabContent.includes("var(--topbar-h)")) {
  failures.push(".tab-content top padding must account for --topbar-h");
}
if (pkg.scripts["test:topbar-ui"] !== "node scripts/verify-topbar-ui.mjs") {
  failures.push("package.json must expose test:topbar-ui");
}

if (failures.length) {
  console.error("Topbar UI contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Topbar UI contract passed");
