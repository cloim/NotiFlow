import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";

export function runLayoutAudit() {
  const project = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
  const output = path.resolve(project, "../docs/validation");
  mkdirSync(output, { recursive: true });
  const script = path.join(
    os.homedir(),
    ".agents/skills/balance/scripts/ui-audit.mjs",
  );
  const args = [script, "--project", project, "--json"];
  if (process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE)
    args.push(
      "--browser-executable",
      process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
    );
  else if (process.platform === "win32") {
    const cache = path.join(os.homedir(), "AppData/Local/ms-playwright");
    const revision = readdirSync(cache)
      .filter((n) => /^chromium_headless_shell-\d+$/.test(n))
      .sort()
      .at(-1);
    const browser =
      revision &&
      path.join(
        cache,
        revision,
        "chrome-headless-shell-win64/chrome-headless-shell.exe",
      );
    if (browser && existsSync(browser))
      args.push("--browser-executable", browser);
  }
  for (const route of ["rules", "rules/new", "logs"])
    args.push("--url", `http://127.0.0.1:4186/?preview=1#/${route}`);
  args.push(
    "--viewport",
    "1440x900",
    "--viewport",
    "390x844",
    "--viewport",
    "325x667",
  );
  try {
    const result = execFileSync(process.execPath, args, {
      encoding: "utf-8",
      timeout: 90000,
    });
    writeFileSync(path.join(output, "layout-audit.json"), result);
  } catch (error) {
    writeFileSync(
      path.join(output, "layout-audit.json"),
      error.stdout || String(error),
    );
    throw error;
  }
}
if (process.argv[1] === fileURLToPath(import.meta.url)) runLayoutAudit();
