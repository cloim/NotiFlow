# NotiFlow Progress

상태 규칙
- ✅ 완료
- ⬜ 미착수
- ⏩ 진행중
- 🟨 보류
- ⛔ 문제

## Phase 0. 문서
- ✅ SPEC.md 작성
- ✅ PLAN.md 작성
- ✅ PROGRESS.md 작성

## Phase 1. Foundation
- ✅ NotificationListenerService 수집 파이프라인
- ✅ Event 정규화 모델
- ✅ Rule/Filter/Action Registry 골격
- ✅ Room 스키마 초안

## Phase 2. Engine + Action
- ✅ Rule Engine 구현
- ✅ 기본 필터 4종
- ✅ webhook.post 액션
- ✅ 재시도/중복방지/레이트리밋

## Phase 3. UI
- ✅ Rule List/Editor
- ✅ 실행 로그 화면
- 🟨 설정 화면(권한 유도 UX 최소 구현)

## Phase 4. 검증
- ✅ Unit Test(필터) 추가
- ✅ Gradle Wrapper 구성/실행 확인 (`gradlew --version`)
- ✅ `local.properties`에 `sdk.dir=D:/AndroidSDK` 설정
- ✅ `gradlew.bat assembleDebug` 통과
- ✅ `gradlew.bat testDebugUnitTest` 통과

## 리스크
- 🟨 compileSdk 35 + AGP 8.5.2 조합 경고(빌드 성공, 추후 AGP 업그레이드 권장)
- ⬜ Android 알림 권한/벤더별 동작 차이 검증 필요
- ⬜ 백그라운드 제한 정책 대응 필요
