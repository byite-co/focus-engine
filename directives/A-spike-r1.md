[작업] Android 선행 스파이크: 화면 off 상태의 카메라 캡처 연속성 검증
[첫 단계] 이 지시문 원문을 directives/A-spike-r1.md 로 저장한다.
[수정 범위] android/spike-r1/**, directives/A-spike-r1.md,
.github/workflows/spike-r1.yml 만. 나머지는 읽기 전용.
[정본] docs/focus/v0-plan-and-gt.md 3장(R1~R3)과
docs/focus/spec-v0.2.0.md 9장(시간 기준, 데이터 경계).
문서의 `\_` 는 `_` 와 같다. 충돌하면 문서가 우선이고 구현 전에 보고한다.

목표: 아래 가정을 실기기 로그로 확인한다. 버리는 코드다.
판정 로직, MediaPipe, Flutter 는 넣지 않는다.
- R1: 화면을 끄고 잠근 상태에서도 camera 타입 foreground service 가
  프레임을 끊김 없이 받는다.
- R2: 전면 카메라를 24fps 로 고정할 수 있다. 안 되면 30fps.
- R3: 프레임 timestamp 를 elapsedRealtime 기준으로 환산할 수 있다.

빌드 환경
- VM 에 Android SDK 가 없으면 설치를 시도한다(dl.google.com 허용됨).
  통과한 설치 명령을 PR 본문에 적는다. 설치가 안 되면 VM 빌드는
  건너뛰고 CI 결과로 검증한다.
- .github/workflows/spike-r1.yml: android/spike-r1 변경 시 assembleDebug,
  단위 테스트, 디버그 APK 아티팩트 업로드.

구성
- 독립 Android 앱(Kotlin). minSdk 29, target/compile 은 최신 안정.
  라이브러리는 최신 안정 버전, libs.versions.toml 에 고정.
  Gradle wrapper 포함. 패키지명은 임시로 kr.co.byite.focus.spike.
- 권한: CAMERA, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA,
  POST_NOTIFICATIONS, WAKE_LOCK. INTERNET 은 선언하지 않는다.
- MainActivity: 권한 요청, 시작/정지 버튼, 마지막 요약 표시, CSV 파일
  경로 표시. 서비스는 반드시 Activity 가 보이는 상태에서 시작한다.
- CaptureService: LifecycleService. startForeground 를 camera 타입으로
  호출. CameraX 를 Activity 가 아니라 서비스 lifecycle 에 바인딩한다.
  use case 는 ImageAnalysis 하나뿐이고 Preview 는 없다. 전면 카메라,
  1280x720, YUV_420_888, STRATEGY_KEEP_ONLY_LATEST.
- Camera2Interop: CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 를 기록하고
  [24,24] 가 있으면 선택, 없으면 [30,30]. 선택 결과를 기록.
  영상 안정화는 off.
- 세션 동안 PARTIAL_WAKE_LOCK 유지.

Analyzer (프레임마다)
- imageInfo.timestamp(ns)와 콜백 시점 elapsedRealtimeNanos 를 읽고 즉시
  image.close(). 픽셀은 어디에도 보관·저장하지 않는다.
- 1Hz 로만 Y plane 을 16x12 격자로 샘플링해 평균 휘도 하나를 계산한다.
  값 하나만 남긴다.

Timebase
- SENSOR_INFO_TIMESTAMP_SOURCE 를 기록.
- offset = 처음 100프레임의 (콜백 elapsedRealtimeNanos - 프레임 ts)
  최솟값. 이후 1분마다 다시 재서 drift 를 기록.

초당 CSV 행 (앱 전용 외부 저장소, 30초마다 flush)
t_mono_ms, t_utc_ms, frames, max_gap_ms, gaps_over_80ms,
cb_latency_ms_mean, y_mean, thermal_status, battery_pct,
battery_current_ua, is_interactive, is_device_idle
세션 header: 기기 모델, OS 버전, 카메라 id, 해상도, 선택 fps 범위,
지원 fps 범위 전체, timestamp source, 배터리 최적화 예외 여부.
정지 시 요약(화면 표시 + 파일): 총 프레임, 80ms 초과 갭 비율, 최대 갭,
누락된 초 수, 평균 fps, 시작·종료 배터리 %, 최고 thermal status,
offset drift. 요약을 클립보드로 복사하는 버튼을 둔다.

순수 로직은 Android 의존 없이 분리하고 JVM 단위 테스트를 붙인다:
FrameStats(갭 통계), TimebaseEstimator(offset·drift).

README: 설치(Actions 아티팩트의 APK)와 실측 절차.
실측 절차 (사람이 수행, 각 30분)
1) 화면 켠 채  2) 화면 끄고 잠금  3) 2번 + 배터리 최적화·잠자기 앱 설정을
바꿔 가며  4) 2번을 충전 중에.
합격: 80ms 초과 갭 < 1%, 1초 넘는 갭 0, 누락된 초 0, 서비스 생존.

끝나면 PR 을 만들고 본문에 적는다: 빌드·테스트 결과, SDK 설치 명령,
구현 중 스펙과 어긋난 점.
