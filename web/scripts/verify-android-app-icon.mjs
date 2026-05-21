import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const manifestPath = resolve(repoRoot, "app", "src", "main", "AndroidManifest.xml");
const sourceIconPath = resolve(repoRoot, "NotiFlow.png");

const densitySizes = {
  mdpi: 48,
  hdpi: 72,
  xhdpi: 96,
  xxhdpi: 144,
  xxxhdpi: 192,
};

async function readPngSize(path) {
  const bytes = await readFile(path);
  const signature = "89504e470d0a1a0a";
  if (bytes.subarray(0, 8).toString("hex") !== signature) {
    throw new Error(`${path} is not a PNG file`);
  }
  return {
    width: bytes.readUInt32BE(16),
    height: bytes.readUInt32BE(20),
  };
}

const failures = [];
const manifest = await readFile(manifestPath, "utf8");

if (!/android:icon="@mipmap\/ic_launcher"/.test(manifest)) {
  failures.push("AndroidManifest.xml must use @mipmap/ic_launcher for android:icon");
}

if (!/android:roundIcon="@mipmap\/ic_launcher_round"/.test(manifest)) {
  failures.push("AndroidManifest.xml must use @mipmap/ic_launcher_round for android:roundIcon");
}

try {
  const source = await readPngSize(sourceIconPath);
  if (source.width !== 512 || source.height !== 512) {
    failures.push("NotiFlow.png must be a 512x512 PNG source icon");
  }
} catch (error) {
  failures.push(error.message);
}

for (const [density, size] of Object.entries(densitySizes)) {
  for (const name of ["ic_launcher", "ic_launcher_round"]) {
    const path = resolve(repoRoot, "app", "src", "main", "res", `mipmap-${density}`, `${name}.png`);
    try {
      const actual = await readPngSize(path);
      if (actual.width !== size || actual.height !== size) {
        failures.push(`${name}.png in mipmap-${density} must be ${size}x${size}`);
      }
    } catch (error) {
      failures.push(error.message);
    }
  }
}

if (failures.length > 0) {
  process.stderr.write(`${failures.join("\n")}\n`);
  process.exit(1);
}

process.stdout.write("Android app icon contract passed\n");
