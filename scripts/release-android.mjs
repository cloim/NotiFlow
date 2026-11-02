// Local signed Android release. Run without --publish to build and archive only.
import { execFileSync } from "node:child_process";
import {
  copyFileSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
  mkdtempSync,
  rmSync,
  existsSync,
} from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { parseArgs } from "node:util";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const { values } = parseArgs({
  options: {
    publish: { type: "boolean", default: false },
    "notes-file": { type: "string" },
    plan: { type: "boolean", default: false },
    "signing-properties": { type: "string" },
    keystore: { type: "string" },
    "expected-cert": { type: "string" },
  },
});
const gradle = readFileSync(join(root, "app/build.gradle.kts"), "utf8");
const version = /\?: "([\d.]+)"/.exec(gradle)?.[1];
if (!version)
  throw new Error("app/build.gradle.kts의 기본 버전을 확인해 주세요");
const tag = `v${version}`,
  apk = join(root, "release", `NotiFlow-prod-${tag}.apk`),
  aab = join(root, "release", `NotiFlow-prod-${tag}.aab`);
if (values.plan) {
  console.log(
    JSON.stringify(
      {
        version,
        tag,
        steps: [
          "web build",
          "asset sync",
          "signed APK build",
          "signature verification",
          "archive",
          ...(values.publish ? ["GitHub Release upload"] : []),
        ],
        apk,
        aab,
      },
      null,
      2,
    ),
  );
  process.exit(0);
}
if (
  values.publish &&
  execFileSync("git", ["status", "--porcelain"], {
    cwd: root,
    encoding: "utf8",
  }).trim()
) {
  throw new Error("변경 내용을 커밋하고 푸시한 뒤 릴리스를 발행해 주세요");
}
const run = (program, args, cwd = root, env = process.env) =>
  execFileSync(program, args, {
    cwd,
    env,
    stdio: ["ignore", "inherit", "inherit"],
  });

function readProperties(file) {
  return Object.fromEntries(
    readFileSync(file, "utf8")
      .replace(/^\uFEFF/, "")
      .split(/\r?\n/)
      .filter(
        (line) => line.trim() && !/^\s*[#!]/.test(line) && line.includes("="),
      )
      .map((line) => {
        const index = line.indexOf("=");
        return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
      }),
  );
}

const gradleEnv = { ...process.env };
if (values["signing-properties"]) {
  if (!values.keystore)
    throw new Error("--keystore에 사용할 서명 파일을 지정해 주세요");
  const signing = readProperties(resolve(values["signing-properties"]));
  if (!signing.KEY_ALIAS || !signing.PASSWORD)
    throw new Error("서명 설정에 KEY_ALIAS와 PASSWORD가 필요합니다");
  if (!existsSync(resolve(values.keystore)))
    throw new Error("서명 파일을 확인해 주세요");
  // Keep passwords out of command arguments, source files and console output.
  gradleEnv.ORG_GRADLE_PROJECT_androidReleaseStoreFile = resolve(
    values.keystore,
  );
  gradleEnv.ORG_GRADLE_PROJECT_androidReleaseStorePassword = signing.PASSWORD;
  gradleEnv.ORG_GRADLE_PROJECT_androidReleaseKeyAlias = signing.KEY_ALIAS;
  gradleEnv.ORG_GRADLE_PROJECT_androidReleaseKeyPassword = signing.PASSWORD;
}
if (!existsSync(join(root, "app/google-services.json")))
  throw new Error("app/google-services.json을 준비해 주세요");
run(
  process.execPath,
  [join(root, "web/node_modules/vite/bin/vite.js"), "build"],
  join(root, "web"),
);
run(process.execPath, [join(root, "web/scripts/sync-assets.mjs")]);
if (process.platform === "win32")
  run(
    "cmd.exe",
    [
      "/d",
      "/c",
      join(root, "gradlew.bat"),
      "testProdDebugUnitTest",
      "assembleProdRelease",
      "bundleProdRelease",
      "lintProdRelease",
      "--no-daemon",
      "--console=plain",
    ],
    root,
    gradleEnv,
  );
else
  run(
    join(root, "gradlew"),
    [
      "testProdDebugUnitTest",
      "assembleProdRelease",
      "bundleProdRelease",
      "lintProdRelease",
      "--no-daemon",
      "--console=plain",
    ],
    root,
    gradleEnv,
  );
const built = join(
  root,
  "app/build/outputs/apk/prod/release/app-prod-release.apk",
);
if (!existsSync(built))
  throw new Error(
    "서명된 release APK가 필요합니다. Gradle 사용자 설정의 서명 속성을 확인해 주세요",
  );
const localSdk = existsSync(join(root, "local.properties"))
  ? readProperties(join(root, "local.properties"))["sdk.dir"]?.replace(
      /\\([\\: ])/g,
      "$1",
    )
  : null;
const sdk =
  process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || localSdk;
if (!sdk)
  throw new Error(
    "서명을 검증할 Android SDK 경로를 ANDROID_HOME에 설정해 주세요",
  );
const metadata = JSON.parse(
  readFileSync(
    join(root, "app/build/outputs/apk/prod/release/output-metadata.json"),
    "utf8",
  ),
);
if (metadata.elements[0].versionName !== version)
  throw new Error("APK 버전과 앱 기본 버전이 일치해야 합니다");
const { readdirSync } = await import("node:fs");
const buildTools = readdirSync(join(sdk, "build-tools"))
  .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
  .at(-1);
const signer = join(
  sdk,
  "build-tools",
  buildTools,
  process.platform === "win32" ? "apksigner.bat" : "apksigner",
);
const certificate = execFileSync(
  process.platform === "win32" ? "cmd.exe" : signer,
  process.platform === "win32"
    ? ["/d", "/c", signer, "verify", "--print-certs", built]
    : ["verify", "--print-certs", built],
  { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
);
if (values["expected-cert"]) {
  const actual = /certificate SHA-256 digest: ([a-f0-9]+)/i
    .exec(certificate)?.[1]
    ?.toLowerCase();
  const expected = values["expected-cert"].replaceAll(":", "").toLowerCase();
  if (actual !== expected)
    throw new Error("기존 릴리스와 서명 인증서가 일치하지 않습니다");
}
mkdirSync(join(root, "release"), { recursive: true });
copyFileSync(built, apk);
copyFileSync(
  join(root, "app/build/outputs/bundle/prodRelease/app-prod-release.aab"),
  aab,
);
console.log(`APK: ${apk}`);
console.log(`AAB: ${aab}`);
if (values.publish) {
  const temporary = mkdtempSync(join(tmpdir(), "notiflow-release-"));
  try {
    const notes = values["notes-file"]
      ? resolve(values["notes-file"])
      : join(temporary, "notes.md");
    if (!values["notes-file"])
      writeFileSync(notes, `NotiFlow ${tag}\n`, "utf8");
    run("gh", [
      "release",
      "create",
      tag,
      apk,
      aab,
      "--repo",
      "cloim/NotiFlow",
      "--target",
      execFileSync("git", ["rev-parse", "HEAD"], {
        cwd: root,
        encoding: "utf8",
      }).trim(),
      "--title",
      `NotiFlow ${tag}`,
      "--notes-file",
      notes,
    ]);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}
