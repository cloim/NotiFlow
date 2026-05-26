import { execFileSync } from "node:child_process";

const DEFAULT_PROJECT_ID = "notiflow-s07142";

function usage() {
  return `Usage:
  npm run send:user-push -- --uid <firebase-uid> --title <title> --body <body> [options]

Options:
  --project <project-id>   Firebase project ID. Defaults to ${DEFAULT_PROJECT_ID}
  --data <key=value>       Add a data payload field. Can be repeated.
  --dry-run                Resolve devices and print a summary without sending.
  --help                   Show this help.

Examples:
  npm run send:user-push -- --uid abc123 --title "NotiFlow" --body "테스트 알림" --dry-run
  npm run send:user-push -- --uid abc123 --title "NotiFlow" --body "도착했습니다" --data kind=manual
`;
}

function parseArgs(argv) {
  const args = {
    project: DEFAULT_PROJECT_ID,
    data: {},
    dryRun: false,
    help: false,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") {
      args.help = true;
    } else if (arg === "--dry-run") {
      args.dryRun = true;
    } else if (arg === "--uid") {
      args.uid = argv[++i];
    } else if (arg === "--title") {
      args.title = argv[++i];
    } else if (arg === "--body") {
      args.body = argv[++i];
    } else if (arg === "--project") {
      args.project = argv[++i];
    } else if (arg === "--data") {
      const entry = argv[++i] ?? "";
      const separator = entry.indexOf("=");
      if (separator <= 0) throw new Error("--data must be formatted as key=value");
      const key = entry.slice(0, separator).trim();
      const value = entry.slice(separator + 1);
      if (!key) throw new Error("--data key must not be blank");
      args.data[key] = value;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return args;
}

function requireArgs(args) {
  for (const key of ["uid", "title", "body"]) {
    if (!String(args[key] ?? "").trim()) {
      throw new Error(`Missing required argument: --${key}`);
    }
  }
}

function getAccessToken() {
  const token = process.env.GOOGLE_OAUTH_ACCESS_TOKEN?.trim();
  if (token) return token;

  return execFileSync("gcloud", ["auth", "print-access-token"], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

async function requestJson(url, { token, project, method = "GET", body } = {}) {
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      "x-goog-user-project": project || DEFAULT_PROJECT_ID,
    },
    body: body ? JSON.stringify(body) : undefined,
  });

  const text = await response.text();
  const json = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const message = json.error?.message || response.statusText;
    throw new Error(`${method} ${url} failed: ${message}`);
  }
  return json;
}

function firestoreValue(field) {
  if (!field) return undefined;
  if ("stringValue" in field) return field.stringValue;
  if ("integerValue" in field) return field.integerValue;
  if ("booleanValue" in field) return field.booleanValue;
  return undefined;
}

async function listUserDevices({ project, uid, token }) {
  const encodedUid = encodeURIComponent(uid);
  const url = `https://firestore.googleapis.com/v1/projects/${project}/databases/(default)/documents/users/${encodedUid}/devices`;
  const json = await requestJson(url, { token, project });
  return (json.documents ?? [])
    .map((doc) => ({
      name: doc.name,
      fcmToken: firestoreValue(doc.fields?.fcmToken),
      appPackage: firestoreValue(doc.fields?.appPackage),
      appVersionName: firestoreValue(doc.fields?.appVersionName),
      platform: firestoreValue(doc.fields?.platform),
    }))
    .filter((device) => typeof device.fcmToken === "string" && device.fcmToken.length > 0);
}

async function sendMessage({ project, token, fcmToken, title, body, data }) {
  const url = `https://fcm.googleapis.com/v1/projects/${project}/messages:send`;
  const messageData = {
    ...data,
    title,
    body,
    message: body,
  };

  return requestJson(url, {
    token,
    project,
    method: "POST",
    body: {
      message: {
        token: fcmToken,
        android: { priority: "HIGH" },
        data: messageData,
      },
    },
  });
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(usage());
    return;
  }
  requireArgs(args);

  const accessToken = getAccessToken();
  const devices = await listUserDevices({
    project: args.project,
    uid: args.uid,
    token: accessToken,
  });

  if (devices.length === 0) {
    throw new Error(`No registered devices found for uid=${args.uid}`);
  }

  if (args.dryRun) {
    console.log(`Dry run: ${devices.length} device(s) resolved for uid=${args.uid}`);
    console.log("FCM tokens are intentionally not printed.");
    return;
  }

  const results = [];
  for (const device of devices) {
    const result = await sendMessage({
      project: args.project,
      token: accessToken,
      fcmToken: device.fcmToken,
      title: args.title,
      body: args.body,
      data: args.data,
    });
    results.push(result.name);
  }

  console.log(`Sent ${results.length} message(s) to uid=${args.uid}`);
  for (const name of results) console.log(`- ${name}`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
