# NotiFlow Product Spec

## 목표
사용자가 지정한 앱의 알림을 모니터링하고, 형식 필터를 통과한 알림에 대해 지정 액션을 실행하는 Android 앱을 개발한다.
기본 액션은 Webhook POST 호출이다.

## 핵심 원칙
- 확장성: Filter/Action을 Registry 기반 추상화로 확장 가능하게 설계
- 보안성: 민감정보(토큰/비밀번호) 평문 저장 금지
- 안정성: 중복 방지, 재시도, 레이트리밋 기본 탑재

## 범위
### Always
- NotificationListenerService 기반 알림 수집
- 패키지명 기반 대상 앱 필터
- Rule Engine (Filter + Action)
- 기본 Action: webhook.post
- 로컬 저장소(Room) + 민감정보(EncryptedSharedPreferences)

### Ask
- 고급 액션(앱 실행/자동 입력/외부 자동화 도구 연동)
- 클라우드 동기화

### Never
- 민감정보 평문 로그/저장
- 사용자 동의 없는 외부 전송

## 권장 스택
- Kotlin + Jetpack Compose
- Clean Architecture + MVVM
- Coroutines/Flow
- Room
- WorkManager
- OkHttp/Retrofit
- Hilt

## 도메인 모델
- NotificationEvent: packageName, title, text, extras, postedAt
- Rule: enabled, priority, targetPackages, filters, actions
- FilterSpec: type + config(JSON)
- ActionSpec: type + config(JSON)
- ExecutionLog: ruleId, matched, result, executedAt

## 추상화 인터페이스
```kotlin
interface Filter {
    val type: String
    fun matches(event: NotificationEvent, config: JsonObject): Boolean
}

interface Action {
    val type: String
    suspend fun execute(event: NotificationEvent, config: JsonObject): ActionResult
}
```

## 실행 흐름
1) 알림 수신/정규화
2) 대상 패키지 Rule 후보 조회
3) Filter 평가
4) 매칭 시 Action 실행
5) 로그 저장
6) 실패 시 WorkManager 재시도

## MVP 필터/액션
- Filters: package.equals, title.contains, text.contains, text.regex
- Action: webhook.post

## 수용 기준
- 앱 선택 + 필터 설정 + webhook 호출까지 동작
- 실패 재시도 동작
- 비밀번호/토큰 미저장(평문 금지)
- 신규 Filter/Action 추가 시 Registry 확장만으로 반영 가능
