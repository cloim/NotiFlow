# NotiFlow Implementation Plan

## Phase 1. Foundation
- NotificationListenerService 수집 파이프라인
- NotificationEvent 정규화
- Rule/Filter/Action 도메인 + Registry 구조
- Room/보안 저장소 골격

## Phase 2. Engine + Webhook MVP
- Rule Engine 실행기
- 기본 필터 4종 구현
- webhook.post 액션 구현
- 중복 방지 + 재시도 + 레이트리밋

## Phase 3. UI + 운영성
- Rule List/Editor 화면
- 실행 로그 화면
- 설정 화면(타임아웃/재시도)
- 권한 상태/진단 UX

## Phase 4. 검증
- Unit Test: 필터/액션/엔진
- Integration: 알림→매칭→액션 실행
- 실기기 수동 테스트
