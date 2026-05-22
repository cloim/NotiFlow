import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const app = read("web/src/App.jsx");
const css = read("web/src/styles.css");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const pkg = JSON.parse(read("web/package.json"));

const failures = [];

for (const text of [
  "THEME_STORAGE_KEY",
  "notiflow.theme",
  "applyThemePreference",
  "resolveThemePreference",
  "matchMedia",
  "themePreference",
  "setThemePreference",
  "setSystemBarsTheme",
  "시스템",
  "라이트",
  "다크",
  "테마",
]) {
  if (!app.includes(text)) failures.push(`App must include ${text}`);
}

if (!app.includes('data-theme')) {
  failures.push("App must apply the selected theme to documentElement data-theme");
}
if (!app.includes('native?.setSystemBarsTheme?.(resolvedTheme === "light")')) {
  failures.push("App must sync the resolved web theme to native system bar icon contrast");
}
if (!app.includes('"(prefers-color-scheme: light)"')) {
  failures.push("System theme must listen to prefers-color-scheme changes");
}
if (!mainActivity.includes("fun setSystemBarsTheme(isLight: Boolean)")) {
  failures.push("MainActivity must expose setSystemBarsTheme to the web bridge");
}
if (!mainActivity.includes("isAppearanceLightStatusBars = isLight")) {
  failures.push("MainActivity must set status bar icon contrast from the resolved theme");
}
if (!mainActivity.includes("window.statusBarColor")) {
  failures.push("MainActivity must set a status bar background that matches the resolved theme");
}
if (!app.includes("browser-mode-card")) {
  failures.push("Browser mode notice must use a dedicated theme-aware class");
}
if (!css.includes(':root[data-theme="light"]')) {
  failures.push("CSS must define explicit light theme tokens");
}
if (!css.includes(':root[data-theme="dark"]')) {
  failures.push("CSS must define explicit dark theme tokens");
}
if (!css.includes("prefers-color-scheme: light")) {
  failures.push("CSS must support system light preference");
}
if (!css.includes("color-scheme: light dark")) {
  failures.push("System theme must advertise light dark color-scheme");
}
if (!css.includes(".browser-mode-card") || !css.includes("var(--amber-dim)")) {
  failures.push("Browser mode notice must use theme tokens for its background");
}
if (!/#root\s*\{[\s\S]*?background:\s*var\(--bg\)/.test(css)) {
  failures.push("#root must paint the theme background");
}
if (!/\.app\s*\{[\s\S]*?background:\s*var\(--bg\)/.test(css)) {
  failures.push(".app must paint the theme background behind fixed nav gaps");
}
if (!/\.tab-content\s*\{[\s\S]*?background:\s*var\(--bg\)/.test(css)) {
  failures.push(".tab-content must paint the theme background through bottom padding");
}
if (pkg.scripts["test:theme-ui"] !== "node scripts/verify-theme-ui.mjs") {
  failures.push("package.json must expose test:theme-ui");
}

if (failures.length) {
  console.error("Theme UI contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Theme UI contract passed");
