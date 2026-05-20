import { cp, mkdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webDir = resolve(here, "..");
const sourceDir = resolve(webDir, "dist");
const targetDir = resolve(webDir, "..", "app", "src", "main", "assets", "web");

async function run() {
  await rm(targetDir, { recursive: true, force: true });
  await mkdir(targetDir, { recursive: true });
  await cp(sourceDir, targetDir, { recursive: true });
  process.stdout.write(`[sync-assets] copied ${sourceDir} -> ${targetDir}\n`);
}

run().catch((error) => {
  process.stderr.write(`[sync-assets] failed: ${error?.message ?? String(error)}\n`);
  process.exit(1);
});
