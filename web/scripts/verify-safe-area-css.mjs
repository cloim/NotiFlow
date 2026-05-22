import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const cssPath = resolve(here, "..", "src", "styles.css");
const css = await readFile(cssPath, "utf8");

const failures = [];

if (!/--safe-t:\s*env\(safe-area-inset-top,\s*0px\);/.test(css)) {
  failures.push("styles.css must define --safe-t from safe-area-inset-top");
}

const tabContent = css.match(/\.tab-content\s*\{(?<body>[\s\S]*?)\}/)?.groups?.body ?? "";
if (!/padding-top:\s*calc\(var\(--safe-t\)\s*\+\s*var\(--topbar-h\)\s*\+\s*16px\);/.test(tabContent)) {
  failures.push(".tab-content padding-top must include --safe-t, --topbar-h, and 16px spacing");
}

const appTopbar = css.match(/\.app-topbar\s*\{(?<body>[\s\S]*?)\}/)?.groups?.body ?? "";
if (!/top:\s*var\(--safe-t\);/.test(appTopbar)) {
  failures.push(".app-topbar must start below the top safe area so the system status bar remains visible");
}
if (/height:\s*calc\(var\(--safe-t\)\s*\+/.test(appTopbar)) {
  failures.push(".app-topbar height must not include --safe-t; reserve safe area above it");
}
if (/padding:\s*calc\(var\(--safe-t\)\s*\+/.test(appTopbar)) {
  failures.push(".app-topbar padding must not absorb --safe-t; reserve safe area above it");
}

if (failures.length > 0) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}

process.stdout.write("safe-area CSS contract passed\n");
