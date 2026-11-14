# FUELOG(풀록)

차량의 운행 기록, 주유·지출 내역, 정비 일정을 한곳에서 관리하는 앱입니다.

개발팀: **우수한친구들**

## 실행 방법

1. 아래 APK를 Android 8.0 이상 기기에 설치합니다.
2. 회원가입 또는 로그인 후 차량을 등록하거나 연결합니다.
3. 운행 기록, 차량 가계부, 정비 일정을 확인합니다.

소스에서 실행하려면 Android Studio에서 프로젝트를 열고, 아래 설정 후 Gradle Sync → `app` 실행을 선택합니다.

## 테스트 환경 및 설정

- 모바일 개발 환경: Medium Phone API 36.0, 1080 × 2400 px (420 dpi)
- 빌드 환경: Android SDK 36, JDK 17 이상, Gradle Wrapper 8.11.1
- 로그인과 데이터 조회에는 인터넷 및 Supabase·백엔드 서버 연결이 필요합니다.
- 소스 빌드 시 프로젝트 루트의 `local.properties`에 다음 값을 설정합니다. 설정 파일은 Git에 포함하지 않습니다.

```properties
sdk.dir=<Android SDK 경로>
SUPABASE_URL=<Supabase 프로젝트 URL>
SUPABASE_ANON_KEY=<Supabase 공개 API 키>
BACKEND_BASE_URL=<백엔드 서버 URL>
```

## Pleos Connect 에뮬레이터

- 버전: 확인 필요
- 화면 규격: 확인 필요 (가로 × 세로 px)

## APK

[우수한친구들_FUELOG(풀록)_release.apk](../apk/우수한친구들_FUELOG%28풀록%29_release.apk)

서명된 release APK이며, 저장소 내 경로는 `apk/우수한친구들_FUELOG(풀록)_release.apk`입니다.
