import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");
const exists = (p) => fs.existsSync(path.join(root, p));

const pkg = JSON.parse(read("web/package.json"));
const docs = read("docs/fcm-notification-receiver.md");
const senderPath = "web/scripts/send-user-push.mjs";
const sender = exists(senderPath) ? read(senderPath) : "";

const failures = [];

if (pkg.scripts["send:user-push"] !== "node scripts/send-user-push.mjs") {
  failures.push("package.json must expose send:user-push");
}

for (const text of [
  "gcloud",
  "auth",
  "print-access-token",
  "firestore.googleapis.com",
  "fcm.googleapis.com/v1/projects",
  "--uid",
  "--title",
  "--body",
  "--data",
  "--dry-run",
  "users",
  "devices",
  "fcmToken",
  "messages:send",
]) {
  if (!sender.includes(text)) failures.push(`send-user-push.mjs must include ${text}`);
}

for (const text of [
  "Sending to a user",
  "npm run send:user-push",
  "--uid",
  "--dry-run",
  "does not print FCM tokens",
]) {
  if (!docs.includes(text)) failures.push(`FCM docs must include ${text}`);
}

if (failures.length) {
  console.error("User push sender contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("User push sender contract passed");
