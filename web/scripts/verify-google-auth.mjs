import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const appGradle = read("app/build.gradle.kts");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const app = read("web/src/App.jsx");
const styles = read("web/src/styles.css");
const pkg = JSON.parse(read("web/package.json"));
const docs = read("docs/fcm-notification-receiver.md");

const failures = [];

for (const text of [
  "firebase-auth",
  "androidx.credentials:credentials",
  "credentials-play-services-auth",
  "googleid",
]) {
  if (!appGradle.includes(text)) failures.push(`app Gradle must include ${text}`);
}

for (const text of [
  "FirebaseAuth",
  "CredentialManager",
  "GetGoogleIdOption",
  "GoogleAuthProvider",
  "getAuthState",
  "signInWithGoogle",
  "signOutGoogle",
  "notifyAuthStateChanged",
  "default_web_client_id",
]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity must include ${text}`);
}

for (const text of [
  "authState",
  "loadAuthState",
  "signInWithGoogle",
  "signOutGoogle",
  "notiflowAuthChanged",
  "Google로 로그인",
  "로그아웃",
  "계정",
]) {
  if (!app.includes(text)) failures.push(`App UI must include ${text}`);
}

for (const text of ["account-card", "account-title", "account-email", "account-actions"]) {
  if (!styles.includes(text)) failures.push(`styles.css must include ${text}`);
}

if (!docs.includes("Google Authentication") || !docs.includes("debug SHA-1")) {
  failures.push("FCM/Auth docs must document Google Authentication setup");
}

if (pkg.scripts["test:google-auth"] !== "node scripts/verify-google-auth.mjs") {
  failures.push("package.json must expose test:google-auth");
}

if (failures.length) {
  console.error("Google auth contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Google auth contract passed");
