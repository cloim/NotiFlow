import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const buildGradlePath = resolve(repoRoot, "app", "build.gradle.kts");
const mainActivityPath = resolve(repoRoot, "app", "src", "main", "java", "com", "vibe", "notiflow", "MainActivity.kt");
const readmePath = resolve(repoRoot, "README.md");
const playReadinessPath = resolve(repoRoot, "docs", "play-store-readiness.md");

const expectedProd = "com.cloimism.notiflow";
const expectedDev = "com.cloimism.notiflow.dev";

const [buildGradle, mainActivity, readme, playReadiness] = await Promise.all([
  readFile(buildGradlePath, "utf8"),
  readFile(mainActivityPath, "utf8"),
  readFile(readmePath, "utf8"),
  readFile(playReadinessPath, "utf8"),
]);

const failures = [];

if (!buildGradle.includes(`namespace = "${expectedProd}"`)) {
  failures.push(`app namespace must be ${expectedProd}`);
}

if (!buildGradle.includes(`applicationId = "${expectedProd}"`)) {
  failures.push(`prod applicationId must be ${expectedProd}`);
}

if (!buildGradle.includes('applicationIdSuffix = ".dev"')) {
  failures.push(`dev flavor must keep .dev suffix to produce ${expectedDev}`);
}

if (!mainActivity.includes(`import ${expectedProd}.BuildConfig`)) {
  failures.push("MainActivity must import BuildConfig from the Android namespace");
}

for (const [name, text] of [
  ["README.md", readme],
  ["docs/play-store-readiness.md", playReadiness],
]) {
  if (!text.includes(expectedProd)) {
    failures.push(`${name} must document prod package ${expectedProd}`);
  }
  if (text.includes("com.notiflow")) {
    failures.push(`${name} must not reference the previous com.notiflow package`);
  }
}

if (failures.length > 0) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}

process.stdout.write("Android package id contract passed\n");
