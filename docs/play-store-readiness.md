# Play Store Readiness

## Upload Artifact

- Upload `app/build/outputs/bundle/prodRelease/app-prod-release.aab` to Play Console.
- Tagged GitHub releases also publish `NotiFlow-prod-<tag>.aab`.
- Use package name `com.notiflow` for the Play Store app.

## QUERY_ALL_PACKAGES Declaration

NotiFlow uses `android.permission.QUERY_ALL_PACKAGES` to let users select an installed app as the target of a notification automation rule.

Suggested Play Console declaration:

```text
NotiFlow uses QUERY_ALL_PACKAGES to let users select an installed app as the target of a notification automation rule.

The app's core functionality is to forward notifications from user-selected apps to user-configured webhooks based on rules. To create a rule, users must choose the source app whose notifications should be matched. Android package names are not user-friendly and are difficult for ordinary users to type accurately, so showing the installed app list is required for the core rule creation flow.

NotiFlow does not upload, sell, or share the installed app inventory. The app list is shown only when the user opens the target app picker. Only the selected package name is saved as part of the user's local notification rule.
```

Korean review note:

```text
NotiFlow의 핵심 기능은 사용자가 선택한 앱의 알림을 조건에 따라 사용자가 설정한 webhook으로 전달하는 것입니다. 룰 생성 시 알림 출처 앱을 선택해야 하며, 일반 사용자가 Android 패키지명을 직접 정확히 입력하는 방식은 핵심 룰 생성 흐름을 사실상 사용하기 어렵게 만듭니다.

설치 앱 목록은 사용자가 대상 앱 선택 화면을 열었을 때만 표시됩니다. 설치 앱 전체 목록은 업로드, 판매, 공유하지 않으며, 사용자가 선택한 패키지명만 로컬 알림 룰의 일부로 저장합니다.
```

## Store Listing And Privacy Checklist

- Store description must explain that NotiFlow reads notifications only after the user grants notification access.
- Privacy policy must describe notification content, selected package names, webhook URLs, optional headers, optional payload templates, and optional tokens.
- Data safety answers must reflect that notification data can be sent to user-configured webhook endpoints.
- In-app package picker must disclose that installed apps are shown only for choosing a notification source.
- Keep the system-apps toggle off by default unless the user explicitly enables it.
