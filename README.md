# NotiFlow Android Hybrid MVP

NotiFlow는 NotificationListenerService 기반의 네이티브 엔진(알림 수집/룰 실행)을 유지하면서, UI는 WebView로 로드하는 Android + Web 하이브리드 구조입니다.

## 현재 구조
- Android Native
  - NotificationListenerService 수집 파이프라인
  - Rule Engine + Filter/Action Registry
  - Room 저장소 + EncryptedSharedPreferences
  - WorkManager 재시도
- Web UI
  - MainActivity는 WebView Shell 역할
  - 기본 로드 경로: `file:///android_asset/web/index.html`
  - 선택 로드 경로: Gradle 속성 `-PnotiflowWebAppUrl=https://...`

## JS Bridge (Android -> Web)
WebView에서 `window.NotiFlowNative` 객체를 사용할 수 있습니다.

- `getAppInfo(): string(JSON)`
- `listRules(): string(JSON)`
- `listLogs(limit: number): string(JSON)`
- `createRule(inputJson: string): string(JSON)`
- `updateRule(inputJson: string): string(JSON)`
- `deleteRule(ruleId: number): string(JSON)`
- `setRuleEnabled(ruleId: number, enabled: boolean): string(JSON)`
- `exportRules(inputJson: string): string(JSON)`
- `importRules(inputJson: string): string(JSON)`
- `isNotificationListenerEnabled(): boolean`
- `openNotificationListenerSettings(): void`

`createRule` 입력 예시:
```json
{
  "name": "Kakao payment alert",
  "packageName": "com.kakao.talk",
  "conditionOperator": "AND",
  "conditions": [
    { "type": "text.contains", "value": "결제" }
  ],
  "webhook": {
    "url": "https://example.com/webhook",
    "method": "POST",
    "headers": { "X-App": "NotiFlow" },
    "payloadTemplate": "{\"title\":{{title}},\"text\":{{text}}}",
    "token": "optional-secret-token"
  }
}
```

`updateRule`은 `createRule`과 동일 페이로드에 `id`를 포함해 호출하면 됩니다.
- `webhook.token`을 생략하면 기존 토큰 유지
- `webhook.token`에 새 값을 넣으면 교체
- `webhook.token`을 `""`로 보내면 삭제
- `webhook.headers`는 JSON object 형식 (`{ "X-App": "NotiFlow" }`)

`exportRules` 입력 예시:
```json
{
  "ruleIds": [1, 2],
  "includeSecrets": false
}
```

`exportRules`는 `{ "data": { "export": ... } }` 형태로 반환하며, `data.export`에는 다음 필드가 포함됩니다.
- `schemaVersion`: 내보내기 JSON 스키마 버전
- `app`: 내보내기를 생성한 앱 정보
- `exportedAt`: epoch milliseconds 내보내기 시각
- `includeSecrets`: 토큰 포함 여부
- `rules`: 내보낸 룰 목록

`includeSecrets=true`로 내보내면 webhook token이 plaintext `webhook.token` 값으로 포함됩니다. 공유 파일에는 토큰이 평문으로 들어가므로 필요한 경우에만 사용하세요.

`importRules`는 bridge 응답 envelope 전체가 아니라(not the full bridge response envelope), `exportRules` 응답의 `data.export`에 해당하는 exported JSON object/file content를 JSON 문자열로 받습니다. 가져오기는 기존 룰을 유지(keeps existing rules)하면서 새 룰을 추가(adds new rules)하고, 내보낸 파일의 룰 id는 무시(ignores file ids)합니다.

## React 개발
`web/` 폴더에 Vite + React 샘플이 포함되어 있습니다.

기본 Web UI 포함 기능:
- Rule 생성/수정/삭제 + 다중 조건 편집
- 수정 시 Draft Diff 미리보기
- 실행 로그 필터 (결과 코드/패키지/기간)

1. 의존성 설치
```bash
cd web
npm install
```
2. 개발 서버 실행
```bash
npm run dev
```
3. Android 앱을 개발 서버 URL로 실행
```bash
cd ..
gradlew.bat assembleDevDebug -PnotiflowWebAppUrl=http://10.0.2.2:5173
```

실기기에서는 `10.0.2.2` 대신 PC의 LAN IP를 사용하세요.

## 로컬 번들 배포 방식
1. Android 자산까지 자동 동기화 빌드
```bash
cd web
npm run build:android
```
2. `-PnotiflowWebAppUrl` 없이 Android 빌드하면 `app/src/main/assets/web` 로컬 자산을 로드합니다.

## Android 빌드
1. Android SDK 설치
2. `local.properties` 생성
```properties
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```
3. 실행
```bash
gradlew.bat assembleDevDebug
gradlew.bat testDevDebugUnitTest
```

## GitHub Actions Android 배포
`v*` 태그를 푸시하면 GitHub Actions가 개발/운영 APK와 Play Store용 운영 AAB를 빌드하고 GitHub Release에 업로드합니다. 앱 내부 `versionName`은 태그에서 앞의 `v`를 제거한 값으로 설정됩니다. 예를 들어 `v1.0.8` 태그는 앱 버전 `1.0.8`로 빌드됩니다.

생성 산출물:
- 개발: `NotiFlow-dev-<tag>.apk` (`applicationId: com.cloimism.notiflow.dev`, 앱 이름: NotiFlow Dev)
- 운영: `NotiFlow-prod-<tag>.apk` (`applicationId: com.cloimism.notiflow`, 앱 이름: NotiFlow)
- Play Store 업로드용: `NotiFlow-prod-<tag>.aab` (`applicationId: com.cloimism.notiflow`)

필수 GitHub Secrets:
- `ANDROID_RELEASE_KEYSTORE_BASE64`: 기존 release keystore 파일을 base64 인코딩한 값
- `ANDROID_RELEASE_KEY_ALIAS`: release key alias
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_RELEASE_KEY_PASSWORD`: key password
- `GOOGLE_SERVICES_JSON_BASE64`: Firebase Android `google-services.json` 파일을 base64 인코딩한 값

Windows PowerShell에서 keystore를 base64로 인코딩하는 예:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) | Set-Clipboard
```

배포 태그 예:
```bash
git tag v1.0.5
git push origin v1.0.5
```

## Play Store AAB 빌드
Play Console에는 운영 flavor의 signed AAB를 업로드합니다.

```powershell
.\gradlew.bat bundleProdRelease `
  -PandroidVersionName=1.0.9 `
  -PandroidVersionCode=9 `
  -PandroidReleaseStoreFile=C:\path\to\release.jks `
  -PandroidReleaseStorePassword=YOUR_STORE_PASSWORD `
  -PandroidReleaseKeyAlias=YOUR_KEY_ALIAS `
  -PandroidReleaseKeyPassword=YOUR_KEY_PASSWORD
```

로컬 산출물 경로:
- `app/build/outputs/bundle/prodRelease/app-prod-release.aab`

Play 심사 전 체크:
- `android.permission.QUERY_ALL_PACKAGES`는 제한 권한이므로 Play Console 권한 선언과 핵심 기능 소명이 필요합니다. 소명이 어렵다면 권한 제거 또는 더 좁은 package visibility 구현으로 바꿔야 합니다.
- Notification Listener와 webhook 전달 기능은 알림 데이터 수집/전송 목적을 스토어 설명, 앱 내 안내, 개인정보처리방침, Data safety 항목에 정확히 써야 합니다.
- Play Console 권한 선언 초안과 체크리스트는 `docs/play-store-readiness.md`에 정리되어 있습니다.

## 보안
- 토큰은 DB/로그에 평문 저장하지 않음
- Rule 액션에는 `tokenRef`만 저장
- 실제 토큰은 `EncryptedSharedPreferences`에 저장
