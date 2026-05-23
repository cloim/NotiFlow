import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");
const exists = (p) => fs.existsSync(path.join(root, p));

const appGradle = read("app/build.gradle.kts");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const messagingService = read("app/src/main/java/com/vibe/notiflow/notification/NotiFlowFirebaseMessagingService.kt");
const docs = read("docs/fcm-notification-receiver.md");
const pkg = JSON.parse(read("web/package.json"));

const registrarPath = "app/src/main/java/com/vibe/notiflow/notification/PushTokenRegistrar.kt";
const registrar = exists(registrarPath) ? read(registrarPath) : "";
const firestoreRules = exists("firestore.rules") ? read("firestore.rules") : "";
const firebaseJson = exists("firebase.json") ? read("firebase.json") : "";
const firebaseRc = exists(".firebaserc") ? read(".firebaserc") : "";

const failures = [];

if (!appGradle.includes("firebase-firestore")) {
  failures.push("app Gradle must include firebase-firestore");
}

for (const text of [
  "FirebaseFirestore",
  "FirebaseMessaging",
  "FirebaseAuth",
  "FieldValue.serverTimestamp",
  "registerCurrentToken",
  "registerToken",
  "unregisterCurrentToken",
  "tokenHash",
  "users",
  "devices",
]) {
  if (!registrar.includes(text)) failures.push(`PushTokenRegistrar must include ${text}`);
}

for (const text of ["PushTokenRegistrar", "onNewToken", "registerToken"]) {
  if (!messagingService.includes(text)) failures.push(`FCM service must include ${text}`);
}

for (const text of ["PushTokenRegistrar", "registerCurrentToken", "unregisterCurrentToken"]) {
  if (!mainActivity.includes(text)) failures.push(`MainActivity must include ${text}`);
}

for (const text of ["match /users/{userId}", "match /devices/{deviceId}", "request.auth.uid == userId"]) {
  if (!firestoreRules.includes(text)) failures.push(`firestore.rules must include ${text}`);
}

if (!firebaseJson.includes("firestore.rules")) failures.push("firebase.json must reference firestore.rules");
if (!firebaseRc.includes("notiflow-s07142")) failures.push(".firebaserc must set notiflow-s07142");

for (const text of ["Push token registration", "users/{uid}/devices/{tokenHash}", "not routed through the rule engine"]) {
  if (!docs.includes(text)) failures.push(`FCM docs must include ${text}`);
}

if (pkg.scripts["test:push-token-registration"] !== "node scripts/verify-push-token-registration.mjs") {
  failures.push("package.json must expose test:push-token-registration");
}

if (failures.length) {
  console.error("Push token registration contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("Push token registration contract passed");
