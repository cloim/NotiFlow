# FCM Notification Receiver

NotiFlow can receive its own Firebase Cloud Messaging push messages and display them as Android notifications. These push messages are intentionally not routed through the notification rule engine.

## Firebase setup

Current Firebase project:

- Display name: `NotiFlow`
- Project ID: `notiflow-s07142`
- Project number: `178904839857`
- Production Android app ID: `1:178904839857:android:d60ca48ad5b63bdd879490`
- Development Android app ID: `1:178904839857:android:20fdb4905147d78c879490`

1. Create or open a Firebase project.
2. Add the Android app package ID:
   - Production: `com.cloimism.notiflow`
   - Development flavor: `com.cloimism.notiflow.dev`, if push testing the dev build
3. Download `google-services.json`.
4. Place it at `app/google-services.json`.

The file is ignored by git. The Gradle Google Services plugin is applied only when `app/google-services.json` exists, so local builds without Firebase configuration still compile.

## Payload handling

`NotiFlowFirebaseMessagingService` displays the received message through `NotiFlowPushNotifier`.
Send NotiFlow-owned pushes as data-only FCM messages. If the FCM message includes a top-level `notification` payload, Android may display it directly while the app is in the background, which means NotiFlow does not run `onMessageReceived()` and cannot store the message in the inbox.

Supported data fallbacks:

- Title: FCM notification title, then `data.title`, then `NotiFlow`
- Body: FCM notification body, then `data.body`, then `data.message`, then a default Korean message
- Notification ID: `data.notificationId`, then `data.id.hashCode()`, then current time

Android 13 and later require the `POST_NOTIFICATIONS` runtime permission. `MainActivity` requests it on startup, and the notifier skips display if the permission is not granted.

NotiFlow-owned push notifications use the `notiflow_push_alerts` Android notification channel with high importance, the default notification sound, vibration enabled, and a full-screen intent that wakes the screen through `PushFullScreenActivity`. Android channel settings are user-controllable, so a user can still silence this channel from the system notification settings.

If an external sender still sends a top-level FCM `notification` payload, Android may auto-display it without calling `onMessageReceived()` while the app is in the background. For that legacy case, the notification listener persists only NotiFlow's own Firebase auto-displayed notifications (`packageName == NotiFlow` and `FCM-Notification:*` tag) as a fallback. It does not persist other apps' listener notifications into the inbox.

## Google Authentication

NotiFlow uses Firebase Authentication with Google Sign-In through Android Credential Manager. The app exposes login state to the WebView through the native bridge and shows the current Firebase user in Settings.

Google provider status:

- Identity Toolkit API: enabled
- Debug SHA fingerprints: registered on both Android apps
- Release SHA fingerprints: registered on the production Android app
- Google provider: enabled

If the provider setup needs to be repaired later:

1. Open Authentication > Sign-in method.
2. Enable Google.
3. Save the generated OAuth client configuration.
4. Download the updated `google-services.json` and replace `app/google-services.json`.

The current debug signing fingerprints registered in Firebase are:

- debug SHA-1: `90:5B:82:75:A6:4D:22:E6:D5:8C:8D:A8:32:CD:1B:A6:3E:7B:91:AC`
- debug SHA-256: `2D:E3:91:60:38:02:F4:FE:9E:34:B4:A8:4B:F0:CB:8D:ED:E2:C3:75:6A:67:C3:E7:9E:D3:FD:0D:FF:A2:26:FA`
- release SHA-1: `3C:58:8C:ED:AD:40:17:5F:E5:40:47:DD:AA:1F:B5:44:7C:91:FB:F6`
- release SHA-256: `21:0F:04:9C:2F:7A:47:8C:16:A4:C2:82:B3:8E:AE:6E:11:45:FC:37:D8:C0:1A:A4:BF:B4:D3:3A:2B:FB:86:A6`

After changing SHA fingerprints, download a fresh `google-services.json` and update the `GOOGLE_SERVICES_JSON_BASE64` GitHub Actions secret before creating a release tag.

## Push token registration

After Google login, NotiFlow registers the current FCM token in Cloud Firestore so a sender can target a specific signed-in user. Token refreshes from `NotiFlowFirebaseMessagingService.onNewToken` update the same storage. Logging out deletes the current device token.

Stored shape:

- User profile: `users/{uid}`
- Device token: `users/{uid}/devices/{tokenHash}`

The device document stores the raw `fcmToken`, hashed document ID, Android package, app version, platform, SDK version, and `updatedAt`. The token registration path is only for NotiFlow's own push delivery and is not routed through the rule engine.

Firestore security rules allow users to read and write only their own `users/{uid}` document and nested `devices` documents. Server-side push senders should use Firebase Admin credentials, which bypass client security rules.

## Sending to a user

The local sender script reads `users/{uid}/devices` from Firestore and sends a high-priority data-only FCM v1 message to each registered device token. It puts `title`, `body`, and `message` inside `data` so NotiFlow receives the push, stores it in the inbox, and then displays the Android notification itself. It uses the active `gcloud` account or `GOOGLE_OAUTH_ACCESS_TOKEN`; it does not require a service account JSON file in the repository.

Dry run:

```bash
npm run send:user-push -- --uid <firebase-uid> --title "NotiFlow" --body "테스트 알림" --dry-run
```

Send:

```bash
npm run send:user-push -- --uid <firebase-uid> --title "NotiFlow" --body "알림 내용" --data kind=manual
```

The script prints message IDs and device counts, but does not print FCM tokens. For a production backend, use Firebase Admin SDK or FCM v1 with server credentials and the same `users/{uid}/devices/{tokenHash}` lookup.
