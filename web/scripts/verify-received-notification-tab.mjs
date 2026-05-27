import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "..");

function read(file) {
  return fs.readFileSync(path.join(root, file), "utf8");
}

function assertIncludes(file, needle, message) {
  const text = read(file);
  if (!text.includes(needle)) {
    throw new Error(`${message}\nMissing in ${file}: ${needle}`);
  }
}

assertIncludes(
  "app/src/main/java/com/vibe/notiflow/data/local/Entities.kt",
  "data class ReceivedNotificationEntity",
  "Received notifications must be persisted in Room."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/data/local/Dao.kt",
  "interface ReceivedNotificationDao",
  "A DAO is required for querying received notifications."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/data/local/NotiFlowDatabase.kt",
  "MIGRATION_2_3",
  "Database migration 2 -> 3 must create the received notification table."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowFirebaseMessagingService.kt",
  "saveReceivedNotification",
  "FCM messages must be saved before they can be viewed."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowFirebaseMessagingService.kt",
  "runBlocking(Dispatchers.IO)",
  "FCM message persistence must finish before the short-lived messaging service can be destroyed."
);
assertIncludes(
  "app/src/main/res/values/strings.xml",
  "<string name=\"fcm_default_notification_channel_id\">notiflow_push_alerts</string>",
  "FCM notification payload fallback must use the same NotiFlow-owned alert channel."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowNotificationListenerService.kt",
  "saveOwnFcmNotificationIfNeeded",
  "Notification payloads auto-displayed by Firebase must be persisted when they belong to NotiFlow itself."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowNotificationListenerService.kt",
  "sbn.packageName != packageName",
  "The listener fallback must not persist notifications from other apps."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowNotificationListenerService.kt",
  "sbn.tag?.startsWith(\"FCM-Notification:\")",
  "The listener fallback must be limited to Firebase auto-displayed NotiFlow notifications."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/notification/NotiFlowNotificationListenerService.kt",
  "saveReceivedNotification",
  "The listener fallback must save NotiFlow-owned Firebase notifications to the inbox."
);
assertIncludes(
  "app/src/main/java/com/vibe/notiflow/MainActivity.kt",
  "fun listReceivedNotifications",
  "The WebView bridge must expose received notifications."
);
assertIncludes(
  "web/src/App.jsx",
  'id: "received"',
  "The bottom navigation must include the received notifications tab."
);
assertIncludes(
  "web/src/App.jsx",
  "loadReceivedNotifications",
  "The received notifications tab must load data from the native bridge."
);
assertIncludes(
  "web/src/App.jsx",
  'if (tab !== "received") return;',
  "The received notifications tab must reload when the user opens it."
);
assertIncludes(
  "web/src/App.jsx",
  "ReceivedNotificationItem",
  "The UI must render each received notification."
);

console.log("Received notification tab verification passed.");
