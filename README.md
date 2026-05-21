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
gradlew.bat assembleDebug -PnotiflowWebAppUrl=http://10.0.2.2:5173
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
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

## GitHub Actions APK 배포
`v*` 태그를 푸시하면 GitHub Actions가 개발/운영 APK를 빌드하고 GitHub Release에 업로드합니다.

생성 APK:
- 개발: `NotiFlow-dev-<tag>.apk` (`applicationId: com.notiflow.dev`, 앱 이름: NotiFlow Dev)
- 운영: `NotiFlow-prod-<tag>.apk` (`applicationId: com.notiflow`, 앱 이름: NotiFlow)

필수 GitHub Secrets:
- `ANDROID_RELEASE_KEYSTORE_BASE64`: 기존 release keystore 파일을 base64 인코딩한 값
- `ANDROID_RELEASE_KEY_ALIAS`: release key alias
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_RELEASE_KEY_PASSWORD`: key password

Windows PowerShell에서 keystore를 base64로 인코딩하는 예:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\release.jks")) | Set-Clipboard
```

배포 태그 예:
```bash
git tag v1.0.5
git push origin v1.0.5
```

## 보안
- 토큰은 DB/로그에 평문 저장하지 않음
- Rule 액션에는 `tokenRef`만 저장
- 실제 토큰은 `EncryptedSharedPreferences`에 저장
