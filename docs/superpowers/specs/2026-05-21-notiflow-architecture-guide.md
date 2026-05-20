# NotiFlow Architecture Guide

This document describes the repository build commands, architecture, bridge API, and extension points for NotiFlow.

## Build Commands

### Android
```bash
# local.properties에 sdk.dir=D:/AndroidSDK 필요
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest

# 개발 서버 URL로 빌드 (에뮬레이터)
gradlew.bat assembleDebug -PnotiflowWebAppUrl=http://10.0.2.2:5173
# 실기기는 PC의 LAN IP 사용
```

### Web UI (`web/`)
```bash
npm install
npm run dev          # 개발 서버 (0.0.0.0:5173)
npm run build        # 프로덕션 빌드
npm run build:android  # 빌드 + app/src/main/assets/web/ 동기화
```

단일 테스트 실행:
```bash
gradlew.bat testDebugUnitTest --tests "com.vibe.notiflow.SomeTest"
```

## Architecture Overview

**Hybrid 구조**: Android Native 엔진 + WebView UI

```
알림 수신 (NotificationListenerService)
  → RuleEngine.process()
    → FilterRegistry (package.equals / title.contains / text.contains / text.regex)
    → ActionRegistry (webhook.post)
      → WebhookPostAction (OkHttp, 토큰은 SecureStore에서 조회)
      → 실패 시 WorkManagerRetryScheduler → WebhookRetryWorker (최대 3회)
    → RuleRepository.insertLog()

MainActivity (WebView Shell)
  → WebViewAssetLoader: https://appassets.androidplatform.net/assets/web/index.html
  → BuildConfig.WEB_APP_URL 설정 시 해당 URL 로드
  → NotiFlowBridge (@JavascriptInterface) → window.NotiFlowNative 노출
```

### 레이어 구조
| 레이어 | 패키지 | 역할 |
|---|---|---|
| Presentation | `ui/rules`, `ui/logs` | (미사용 — UI는 WebView) |
| Service | `notification/` | `NotificationListenerService` |
| Domain | `domain/engine`, `domain/model`, `domain/filter`, `domain/action`, `domain/repo` | 비즈니스 로직 |
| Data | `data/local/` | Room DB, SecureStore |
| DI | `di/ServiceLocator` | 수동 DI (Hilt 미사용, object 싱글턴) |
| Worker | `worker/` | WorkManager 재시도 |

> **주의**: Hilt 의존성이 포함되어 있으나 실제 DI는 `ServiceLocator` object로 처리됨. `@HiltAndroidApp`/`@Inject` 미사용.

### DB 스키마 (Room v2)
- `rules`: id, name, enabled, priority, targetPackagesJson, filtersJson, filterOperator, actionsJson, createdAt
- `execution_logs`: id, ruleId, matched, result, message, executedAt, eventPackage, eventTitle
- 마이그레이션: `NotiFlowDatabase.MIGRATION_1_2` (v1→v2: filterOperator 컬럼 추가)

### 보안
- Webhook 토큰은 `EncryptedSharedPreferences`(`SecureStore`)에 UUID alias로 저장
- DB/액션 설정에는 `tokenRef`(alias)만 기록, 평문 토큰 미저장
- 토큰 삭제: `webhook.token = ""`로 updateRule 호출

## JS Bridge API (`window.NotiFlowNative`)

모든 메서드는 동기 호출이며, 대부분 `{ ok: boolean, data?: ..., error?: string }` JSON 문자열 반환.

| 메서드 | 반환 |
|---|---|
| `getAppInfo()` | `{ appName, versionName, versionCode, packageName, platform }` |
| `listRules()` | `{ data: { rules: Rule[] } }` |
| `listLogs(limit)` | `{ data: { logs: Log[] } }` |
| `createRule(inputJson)` | `{ data: { ruleId } }` |
| `updateRule(inputJson)` | `{ data: { ruleId } }` |
| `deleteRule(ruleId)` | `{ data: { ruleId } }` |
| `setRuleEnabled(ruleId, enabled)` | `{ data: { ruleId, enabled } }` |
| `isNotificationListenerEnabled()` | `boolean` |
| `openNotificationListenerSettings()` | `void` |

Rule 입력 스키마는 `README.md` 참조.

## Extension Points

### 새 Filter 추가
1. `domain/filter/Filters.kt`에 `EventFilter` 구현 클래스 추가
2. `di/ServiceLocator.kt`의 `FilterRegistry(listOf(...))` 에 등록
3. `MainActivity.kt`의 `normalizeConditions()` 허용 타입 목록 추가

### 새 Action 추가
1. `domain/action/Actions.kt`에 `EventAction` 구현 클래스 추가
2. `di/ServiceLocator.kt`의 `ActionRegistry(listOf(...))` 에 등록

## Payload Template 변수

`{{packageName}}`, `{{title}}`, `{{text}}`, `{{postedAt}}`, `{{extras}}`

렌더링 후 알 수 없는 `{{placeholder}}`가 남으면 에러. 결과는 JSON object 또는 array여야 함.

## RuleEngine 동작 특성

- **중복 방지**: 5초 내 동일 (packageName + title + text) 이벤트 무시, 캐시 최대 500개
- **레이트 리밋**: 동일 룰 2초 이내 재실행 방지
- **우선순위**: `rule.priority` 내림차순으로 처리
- **필터 평가**: `package.equals`는 항상 AND, 나머지는 `filterOperator`(AND/OR) 적용
- **재시도**: `webhook.post` 실패 + `retryable=true`일 때 WorkManager 큐잉, 최대 3회, HTTP 5xx만 retryable
