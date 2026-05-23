import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");
const read = (p) => fs.readFileSync(path.join(root, p), "utf8");

const rootGradle = read("build.gradle.kts");
const appGradle = read("app/build.gradle.kts");
const manifest = read("app/src/main/AndroidManifest.xml");
const mainActivity = read("app/src/main/java/com/vibe/notiflow/MainActivity.kt");
const appClass = read("app/src/main/java/com/vibe/notiflow/NotiFlowApp.kt");
const pkg = JSON.parse(read("web/package.json"));

const fcmServicePath = "app/src/main/java/com/vibe/notiflow/notification/NotiFlowFirebaseMessagingService.kt";
const notifierPath = "app/src/main/java/com/vibe/notiflow/notification/NotiFlowPushNotifier.kt";
const listenerPath = "app/src/main/java/com/vibe/notiflow/notification/NotiFlowNotificationListenerService.kt";

const fcmService = fs.existsSync(path.join(root, fcmServicePath)) ? read(fcmServicePath) : "";
const notifier = fs.existsSync(path.join(root, notifierPath)) ? read(notifierPath) : "";
const listener = fs.existsSync(path.join(root, listenerPath)) ? read(listenerPath) : "";

const failures = [];

if (!rootGradle.includes("com.google.gms.google-services")) {
  failures.push("root Gradle must declare the Google Services plugin");
}
if (!appGradle.includes("firebase-messaging")) {
  failures.push("app Gradle must depend on Firebase Messaging");
}
if (!appGradle.includes("google-services.json")) {
  failures.push("app Gradle must apply Google Services only when google-services.json exists");
}
if (!manifest.includes("android.permission.POST_NOTIFICATIONS")) {
  failures.push("AndroidManifest must request POST_NOTIFICATIONS for Android 13+");
}
if (!manifest.includes("com.vibe.notiflow.notification.NotiFlowFirebaseMessagingService")) {
  failures.push("AndroidManifest must register the Firebase messaging service");
}
if (!manifest.includes("com.google.firebase.MESSAGING_EVENT")) {
  failures.push("Firebase messaging service must handle MESSAGING_EVENT");
}
if (!mainActivity.includes("requestPostNotificationsPermissionIfNeeded")) {
  failures.push("MainActivity must request the notification runtime permission when needed");
}
if (!appClass.includes("NotiFlowPushNotifier") || !appClass.includes("ensureChannel")) {
  failures.push("NotiFlowApp must create the push notification channel during app startup");
}
if (!fcmService.includes("FirebaseMessagingService")) {
  failures.push("NotiFlowFirebaseMessagingService must extend FirebaseMessagingService");
}
if (!fcmService.includes("onMessageReceived")) {
  failures.push("NotiFlowFirebaseMessagingService must receive FCM messages");
}
if (!fcmService.includes("NotiFlowPushNotifier")) {
  failures.push("FCM service must delegate display to NotiFlowPushNotifier");
}
if (fcmService.includes("RuleEngine") || fcmService.includes("ruleEngine") || fcmService.includes("NotificationEvent")) {
  failures.push("FCM service must not route NotiFlow push messages through the rule engine");
}
for (const text of ["NotificationManagerCompat", "NotificationChannel", "notiflow_push", "POST_NOTIFICATIONS"]) {
  if (!notifier.includes(text)) failures.push(`NotiFlowPushNotifier must include ${text}`);
}
if (!notifier.includes(".setAutoCancel(false)")) {
  failures.push("NotiFlow push notifications must remain visible after the user opens NotiFlow from them");
}
for (const text of ["cancelNotification", "cancelAllNotifications", "contentIntent.send", "deleteIntent.send"]) {
  if (listener.includes(text)) failures.push(`Notification listener must not dismiss or activate source notifications via ${text}`);
}
if (pkg.scripts["test:fcm-notifications"] !== "node scripts/verify-fcm-notification-receiver.mjs") {
  failures.push("package.json must expose test:fcm-notifications");
}

if (failures.length) {
  console.error("FCM notification receiver contract failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log("FCM notification receiver contract passed");
