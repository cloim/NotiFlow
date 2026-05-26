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
  "ReceivedNotificationItem",
  "The UI must render each received notification."
);

console.log("Received notification tab verification passed.");
