# NotiFlow

Android에서 공유 콘텐츠와 알림을 규칙에 따라 웹훅으로 연결합니다.

- Google 계정 로그인
- 앱 공유 수신: 텍스트, 링크, 이미지, PDF, 동영상, 오디오와 파일
- 단일 공유와 여러 파일 공유, 콘텐츠 MIME 타입 조건
- 제목, 본문, 정규식, AND/OR와 괄호 조건
- 웹훅 POST, PUT, PATCH, 사용자 헤더와 JSON 템플릿
- 규칙 활성화, 수정, 삭제와 실행 기록
- 아바타에서 설정과 로그아웃, 헤더 아이콘에서 알림 접근 상태 확인과 권한 화면 열기
- 설정에서 Tailscale 원격 편집 서버 실행과 중지, PC에서 규칙 편집과 실행 기록 조회
- 앱 시작 시 업데이트 확인, 변경 내용 안내와 브라우저 APK 다운로드

파일은 최대 20개, 합계 10MB까지 지원합니다. 파일 이름, MIME 타입, 크기와 Base64 본문을 JSON으로 전송합니다. 규칙과 기록은 기기에 저장합니다. Google 로그인은 기기 이용을 확인하며 계정 간 클라우드 동기화는 제공하지 않습니다.

## 개발 환경

Node.js 22.12 이상, JDK 17 이상, Android SDK 35가 필요합니다. 기존 React와 Android WebView 구성을 유지합니다.

1. `web`에서 `npm ci`를 실행합니다.
2. 루트 `local.properties`에 Android SDK 경로를 설정합니다.
3. Firebase Android 설정을 `app/google-services.json`에 둡니다. Firebase Authentication에서 Google 제공자를 활성화하고 패키지와 빌드 인증서 SHA 지문을 등록합니다. 설정 파일은 Git에서 제외합니다.
4. `web`에서 `npm run dev`를 실행합니다. 브라우저는 로그인 화면과 명시적 미리보기를 제공합니다. 실제 Google 로그인, 공유 수신과 웹훅 실행은 Android 앱에서 동작합니다.

## Tailscale 원격 편집

1. Android와 접속할 PC에 Tailscale을 설치하고 같은 tailnet에 연결합니다. 기기 간 접근 정책에서 휴대폰의 TCP 8186 포트를 허용합니다.
2. Android 앱에서 Google 로그인 후 아바타 메뉴의 `설정`을 엽니다. `Tailscale 열기`로 VPN 연결을 켜고 앱으로 돌아와 `서버 실행`을 누릅니다.
3. PC 브라우저에서 설정에 표시된 접속 주소를 열고 접속 코드를 입력합니다. 같은 웹 화면에서 규칙 생성, 수정, 활성화, 삭제와 실행 기록 조회가 가능합니다. 변경은 휴대폰의 기존 데이터 저장소에 바로 반영됩니다.
4. `서버 중지` 또는 실행 알림의 중지 버튼으로 종료합니다. Google 로그아웃, 로그인 계정 변경 또는 VPN 주소 소실 시에도 종료합니다. 서버는 재부팅이나 프로세스 종료 후 자동 재개하지 않습니다.

서버는 VPN 인터페이스의 Tailscale IPv4 주소(100.64.0.0/10)에만 바인딩하며 일반 Wi-Fi 주소와 모든 인터페이스에서는 열지 않습니다. 현재 연결에 맞는 IPv4 주소가 없으면 실행 안내를 표시합니다. HTTP 트래픽은 Tailscale 터널을 통해 전달합니다. 서버 실행마다 새 접속 코드가 생성되며, 인증된 API 요청만 규칙과 기록에 접근합니다. 코드는 URL에 포함하지 않으며 브라우저 탭의 세션 저장소에만 보관합니다. Tailscale의 앱별 분할 터널링을 사용한다면 NotiFlow를 VPN에 포함합니다.

원격 서버는 사용자 시작 전경 서비스로 실행됩니다. Android의 서버 실행 알림에서 설정을 열거나 서버를 중지할 수 있습니다. 브라우저의 `연결 해제`는 해당 탭의 인증을 해제하며 휴대폰 서버는 유지합니다.

참고: [Tailscale 서버 접속](https://tailscale.com/docs/how-to/set-up-servers), [Tailscale Android 클라이언트 구현](https://github.com/tailscale/tailscale-android/blob/main/libtailscale/backend.go), [Android 전경 서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)

## 검증과 APK 빌드

규칙, 공유 수신 또는 화면을 변경한 뒤 아래 순서로 검증합니다. Windows의 긴 명령은 `~/.agents/scripts/maintenance/run_job.py`에 제출하고 최종 종료 상태를 확인합니다.

```text
cd web
npm test
npx playwright install chromium --only-shell
npm run test:ui
npm run build:android
cd ..
gradlew.bat testProdDebugUnitTest assembleProdDebug lintProdDebug
```

`test:ui`는 포트 4186에 임시 서버를 열고 종료 후 닫습니다. 테스트는 브라우저 미리보기와 모의 네이티브 이벤트로 생성, 조회, 수정, 삭제, Google 로그인 상태 전환, 공유 안내와 업데이트 창을 검증합니다. 실제 OAuth 인증을 대체하지 않습니다.

로컬 balance 스킬이 있으면 UI 테스트의 레이아웃 검사도 실행됩니다. 검사는 `~/.agents/skills/balance/scripts/ui-audit.mjs`를 호출하고 1440px, 390px와 325px 화면을 측정합니다. 단독 실행은 임시 서버가 실행 중일 때 `npm run test:layout`을 사용합니다. `PLAYWRIGHT_CHROMIUM_EXECUTABLE`로 검사 브라우저를 지정할 수 있습니다. balance 스킬이 없는 환경은 `npx playwright test --grep-invert "balance layout"`로 화면 시나리오만 실행합니다.

화면 이미지, 검사 결과와 HTML 보고서는 Git에서 제외한 `docs/validation/`에 저장됩니다. 테스트와 빌드의 종료 코드 0이 자동 검증 통과 기준입니다. 오류가 있으면 해당 로그를 확인하고 수정한 뒤 다시 실행합니다. 실제 Google 계정 선택과 외부 앱의 URI 권한은 Android 기기에서 확인합니다.

디버그 APK는 `app/build/outputs/apk/prod/debug/app-prod-debug.apk`에 생성됩니다. 기존 배포 APK를 업데이트하려면 동일한 키로 서명한 release APK가 필요합니다.

## 로컬 배포 절차

GitHub Actions 대신 로컬에서 빌드, 서명 검증, APK 보관과 릴리스 업로드를 수행합니다. 런타임 업데이트 확인은 GitHub 최신 릴리스를 조회합니다. flavor가 일치하는 APK가 있으면 내려받고, 없으면 릴리스 페이지를 엽니다.

1. `app/build.gradle.kts`의 기본 버전과 버전 코드를 올립니다.
2. 기존 release 키의 서명 속성을 로컬 Gradle 사용자 설정에 준비합니다. 키와 비밀번호는 Git에 저장하지 않습니다.
3. `ANDROID_HOME` 또는 `ANDROID_SDK_ROOT`를 설정합니다.
4. 루트에서 `node scripts/release-android.mjs --plan`으로 절차를 확인합니다.
5. 루트에서 `node scripts/release-android.mjs`를 실행해 웹 빌드, 동기화, 테스트, signed APK와 AAB 빌드, lint와 서명 검증을 수행합니다. 공용 서명 설정 파일을 사용할 때는 `--signing-properties <설정.properties> --keystore <서명.jks>`를 지정합니다. 설정 파일의 정확한 키는 `KEY_ALIAS`와 `PASSWORD`이며 비밀번호는 프로세스 환경으로만 전달됩니다. 기존 앱 업데이트용으로 빌드할 때는 `--expected-cert <기존 인증서 SHA-256>`도 지정합니다. SDK 경로는 환경 변수 또는 `local.properties`의 `sdk.dir`에서 읽습니다.
6. `release/NotiFlow-prod-v<버전>.apk`를 확인합니다. 서명 실패나 빌드 실패 시 업로드를 보류하고 원인을 수정합니다.
7. 릴리스 발행을 지시받은 경우 커밋과 푸시를 마친 뒤 `node scripts/release-android.mjs --publish --notes-file <변경내역.md>`를 실행합니다. GitHub CLI 로그인이 필요합니다. 스크립트는 `--publish`를 지정한 경우에만 업로드합니다.

## 웹훅 본문

본문 템플릿을 비우면 전체 이벤트를 전송합니다. 변수는 JSON 값으로 치환하므로 추가 따옴표를 붙이지 않습니다.

```json
{
  "source": {{source}},
  "mimeType": {{mimeType}},
  "title": {{title}},
  "text": {{text}},
  "files": {{attachments}}
}
```

추가 변수는 `{{packageName}}`, `{{postedAt}}`와 `{{extras}}`입니다. HTTP 실패는 실행 기록에 남기며 네트워크 오류와 서버 오류는 백그라운드에서 재시도합니다. 공유 입력은 로그인 후 실행합니다. 로그아웃하면 새 알림 처리와 웹훅 재시도를 멈춥니다.
