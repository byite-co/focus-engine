# android/focus-engine

V0-A/B: Android 측정 엔진 골격과 Face/Pose raw feature 기록, R4 성능 실험(프리셋 비교 계측). 게이트 판정은 없다.
지시문은 `directives/C-v0ab-raw-features.md` 와 `directives/D-r4-perf-experiments.md`, 정본은 `docs/focus/spec-v0.2.0.md` 1·6·7·9장과
`docs/focus/v0-plan-and-gt.md` 2~4장, 기록 스키마는 `core/focus-core` (0.2.3).
spike-r1(R1~R3)의 foreground service, Camera2Interop fps 고정, 요약 복원·클립보드 복사,
Release 게시 워크플로를 그대로 이어받았다. 스키마·정의 결정은 `CHANGELOG.md` v0.2.2·v0.2.3 과
`docs/research-notes/RN-002-mediapipe-telemetry-and-jitter.md`, `RN-003-r4-presets-counters-and-threads.md`.

## 구성

| 경로 | 내용 |
|---|---|
| `engine/` | `:engine` Android 라이브러리. `CaptureService`(camera 타입 FGS, 서비스 lifecycle 에 CameraX 바인딩, wakelock, 스레드 모델·정지 순서), `CapturePreset`(프리셋 A~E·G, `PresetResolution`, `FrameSkipRule`), `pipeline/camera/CameraPipeline`, `pipeline/face/FacePipeline`(+`HeadPose`, `RigidJitter`), `pipeline/pose/PosePipeline`(+`PoseGeometry`)·`PoseWorker`(비동기 Pose, 큐 깊이 1), `pipeline/scene/SceneQuality`(+`SceneGrid`), `MotionPipeline`(IMU 5Hz), `timebase/Timebase`(+`ClockOffsetEstimator`), `FeatureLogger`(JSONL, IO 스레드 직렬화), `SessionRecovery`, `DeviceStatusReader` |
| `engine/src/main/assets/` | MediaPipe 모델 파일 2개 (아래 표) |
| `engine/src/main/java/com/google/android/datatransport/` | MediaPipe 가 끌고 오는 Google 텔레메트리 라이브러리의 no-op 스텁 8개 (아래 "MediaPipe 원격 통계 로깅 차단") |
| `devapp/` | `:devapp` 개발용 앱. 프리셋 선택(세션마다 하나), 시작/정지, 구간 마커 6개(정면, 가만히, 숙임, 엎드림, 자리비움, 빈의자), 상태(1Hz), 요약, 요약 복사. edge-to-edge 인셋 처리 |
| `../../core/focus-core` | composite build (`includeBuild`) 로 참조하는 순수 로직. 초당 집계 `FeatureAggregator`(+`CounterTotals`, `CounterConsistency`, `StopSequence`), 로그 모델, 요약 `V0bReport` |

패키지 `co.byite.focus.engine` (엔진), `co.byite.focus.devapp` (앱, applicationId 도 같다).
minSdk 29, compileSdk 37.2, targetSdk 37. AGP 9.4.1, Kotlin 2.4.20, CameraX 1.6.2, MediaPipe Tasks Vision 1.0.0(`strictly` 고정).
APK 는 arm64-v8a 만 담는다(실측 기기 기준, `devapp/build.gradle.kts` 의 `abiFilters`).

기기 층(이 모듈)은 프레임과 센서 값을 스칼라로 줄여 aggregation 큐에 post 하고, 그 큐를 소유한 스레드가 `FeatureAggregator`(focus-core)로
집계한 `SecondRecord` + `v0b_raw` 를 `FeatureLogger` 가 IO 스레드에서 JSONL 로 쓴다. 판정·캘리브레이션은 다음 단계다.

## 프리셋 (R4 실험, 지시문 D)

세션마다 devapp 에서 하나를 고른다. A 가 기준이고 나머지는 A 에서 한 가지만 바꾼다. header 의 `capture_preset` 에 기록되고 요약 첫 줄에 나온다.

| id | A 와 다른 점 | header |
|---|---|---|
| A | 1280x720(16:9), Face CPU, blendshape on, Face 매 프레임 | `frame_process_divisor 1`, `frame_gap_threshold_ms 80` |
| B | blendshape off | `face_blendshapes false` |
| C | 640x360(16:9) 요청. 기기의 YUV 출력 크기에 640x360 이 없으면 면적이 가장 가까운 16:9 크기, 그것도 없으면 640x480 을 **C2** 로 표시(`PresetResolution.choose`) | `capture_preset C` 또는 `C2` |
| D | Face GPU delegate(`Delegate.GPU`). 생성 실패는 자가 점검이 잡는다 | `face_delegate GPU` |
| E | Face 격프레임(24fps → 12fps). `FrameSkipRule`: 마지막 처리 프레임보다 (2 − 0.5)×프레임 주기(62.5ms) 안에 온 프레임을 건너뛴다(capture timestamp 기준이라 카메라가 프레임을 떨어뜨려도 위상이 밀리지 않는다). 건너뛴 프레임은 `frames_skipped_intentional` | `frame_process_divisor 2`, `frame_gap_threshold_ms 167` |
| G | Face 분석 스레드(`focus-analysis`)에만 `PerformanceHintManager` 세션(목표 35ms). 매 Face 사이클(analyzer 콜백 1회: wrap + Face + post + scene + pose copy) 뒤 `reportActualWorkDuration` 으로 실제 처리 시간을 보고한다. Pose 스레드는 세션에 넣지 않고 스레드 우선순위는 바꾸지 않는다. API 31 미만이거나 세션 생성이 null 이면 자가 점검 줄에 남기고 header `perf_hint_target_ms` 는 null | `perf_hint_target_ms 35` |
| F | ROI 크롭. **이번에 구현하지 않았다**(아래 "프리셋 F") | — |

요약과 header 의 해상도는 요청 해상도가 아니라 CameraX 가 실제로 정한 해상도(`ImageAnalysis.resolutionInfo`, 첫 프레임으로 재확인)이고 종횡비를 같이 표시한다(`1280x720 (16:9)`).
events.log 의 `resolution_choice`(요청·사유·기기의 YUV 출력 크기 목록)와 `camera_bound`(실제 선택) 줄로 확인한다.

**프리셋 F(ROI 크롭)를 건너뛴 사유**: 정정 2 의 규칙대로 하면 2D 랜드마크·얼굴 폭·위치 스칼라는 crop 의 위치·크기·배율로 역변환하면 되지만, head pose 는 좌표 변환으로
동일성을 가정할 수 없어(크롭 창이 중심에서 벗어나면 주점이 옮겨져 transformation matrix 의 yaw·pitch 가 치우친다) 같은 자세에서 A 와 F 를 실기기로 반복 측정해 편향을
재야 한다(허용 2°). 이 세션에는 실기기가 없고 A~E·G 와 계수·스레드 모델의 범위가 이미 크다. 구현한다면: `FacePipeline.infer` 앞에서 직전 얼굴 박스 주변을 잘라 별도 버퍼에
복사하고 `extract` 에서 `x·cropW + cropX` 로 되돌린다, 편향 측정 절차를 아래 실측 절차에 추가한다, 허용 오차를 넘으면 F 의 yaw·pitch 를 G2 입력으로 쓰지 않는다고 PR 본문에 적는다.

## 스레드 모델 (지시문 D 정정 3)

| 스레드 | 소유 | 큐로 넘기는 것 |
|---|---|---|
| `main` | 서비스 lifecycle, CameraX bind/unbind, 알림 | — |
| `focus-analysis` (`THREAD_PRIORITY_FOREGROUND`) | CameraX analyzer: 프레임 계수, `FrameSkipRule`, wrap, Face 추론·후처리, Scene(1Hz), Pose 프레임 deep copy, G 의 hint 세션, landmarker 생성·해제 | `onFrameReceived/Skipped/…`, `ProcessedFrame`(Face 결과 + 단계 시간), `SceneSample`, `onPoseRequested/Superseded` → aggregation 큐 |
| `focus-pose` | `PoseWorker`: 대기 슬롯 1 + 실행 중 1, 픽셀 버퍼 2개(밖으로 안 나감, `release` 에서 해제), Pose landmarker 추론 | `PoseSample`, `onPoseError` → aggregation 큐 |
| `focus-aggregate` | **`FeatureAggregator` 의 유일한 접근자**, 레코드 목록, 1Hz 버킷 닫힘(tick), timebase 줄, 상태 문자열, `FeatureLogger.append`(직렬화 없이 객체만 큐잉) | 레코드 → IO 큐 |
| `focus-io` (`THREAD_PRIORITY_BACKGROUND`) | JSONL 직렬화 + `session.jsonl` 쓰기(30초 배치), `events.log`, `summary.txt` | — |
| `focus-status` | 1Hz `DeviceStatusReader.read()`(thermal·배터리·keyguard binder 호출), IMU `SensorEventListener` | `DeviceSample`(최신값), `ImuSample` → aggregation 큐 |
| `focus-stop` | 정상 종료의 `StopSequence` 실행(아래) | — |

분석 스레드에는 추론·스칼라 추출·memcpy 만 남고 파일 IO·직렬화·binder 호출은 없다. 어느 파이프라인도 집계기를 직접 호출하지 않는다(`DataBoundaryTest` 와 별개로 코드 구조로 보장: `CaptureService` 의 Listener 구현은 전부 `aggHandler.post`).

## 정상 종료 순서 (지시문 D 정정 5, `StopSequence`)

`정지` 버튼(`ACTION_STOP`) → `focus-stop` 스레드에서 순서대로: ① `main` 에서 IMU 해제·camera unbind, 그 시각을 accept fence 로 aggregation 큐에 post(`stopInputs(fence)`; capture timestamp ≥ fence 인 입력은 계수에서 제외), 분석 스레드에 marker 를 post 해 실행 중인 Face·Scene 작업이 끝나길 기다린다(상한 500ms, 넘으면 `frames_cancelled_at_stop` 1) → ② Pose 대기 슬롯 폐쇄(대기 중 요청은 `pose_cancelled_at_stop`) → ③ 실행 중 Pose 를 상한 500ms 기다린다(넘으면 `pose_cancelled_at_stop`, 늦게 온 결과는 finish 뒤라 버려진다) → ④ status 스레드 marker → aggregation 큐 barrier(제한 3초) → ⑤ 집계 스레드에서 `finish()`(완전한 버킷만) → ⑥ `CounterConsistency.check(totals, cancelled…)` → ⑦ `session_end` 줄, 요약(첫머리에 검사 결과), `summary.txt`. 그 뒤 분석 스레드에서 landmarker·hint 세션·Pose 워커 해제(GPU delegate 는 만든 스레드에서 닫아야 한다), 스레드 종료. events.log 에 `stop_sequence steps=… fence=… frames_cancelled=… pose_cancelled=… drained=… mismatches=…` 와 `counter_mismatch …` 줄이 남는다.
시스템이 서비스를 내리는 `onDestroy` 는 이 순서를 밟지 않고 버퍼만 flush 한다; 다음 실행의 복원 요약은 "계수 검증 생략(비정상 종료)" 로 시작한다.

## 데이터 경계

- 프레임(`ImageProxy`, `RgbaFrame`, `MPImage`)과 랜드마크는 `co.byite.focus.engine.pipeline.*` 안에서만 존재한다.
  파이프라인이 밖으로 내는 것은 focus-core 의 `FrameSample`, `PoseSample`, `SceneSample`, `ImuSample` 스칼라뿐이다.
  `engine/src/test/.../DataBoundaryTest` 가 소스 import 와 리플렉션으로 검사한다.
- `FeatureLogger` 는 focus-core 로그 모델(`SessionHeader`, `TimebaseRecord`, `AggregatedSecond`, `SessionEnd`)만 받는다.
  로그 DTO 에 배열·비트맵 필드가 없는지는 focus-core 의 `SchemaBoundaryTest` 가 검사한다.
- 파일·로그·크래시 경로에 픽셀이나 랜드마크를 쓰는 코드 경로가 없다. 이번 단계에는 DEBUG 덤프(Pose 5점 기록)도 없다.
- INTERNET 권한 없음. 아래 "MediaPipe 원격 통계 로깅 차단" 참조. APK 매니페스트 권한은
  `CAMERA, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS, WAKE_LOCK` 뿐이고 CI 가 검사한다.
- 모델은 assets 에 포함하고 런타임 다운로드 경로가 없다.

## MediaPipe 원격 통계 로깅 차단 (CHANGELOG v0.2.2 (c), RN-002)

**대상 버전**: `com.google.mediapipe:tasks-vision:1.0.0` (`gradle/libs.versions.toml` 에 `strictly` 로 고정. 올릴 때는 아래 확인 방법을 다시 돌린다).

**문제**: `tasks-core` 는 `com.google.android.datatransport:transport-backend-cct` 를 끌고 오고, 그 매니페스트가 `INTERNET`·`ACCESS_NETWORK_STATE` 를
선언하며 `transport-runtime` 은 JobService·BroadcastReceiver 를 병합한다. `tasks-core` 의 `TasksStatsLoggerFactory.create` 는 배포 버전
0.10.14~1.0.0 모두 `TasksStatsProtoLogger` 를 만들고, 그 생성자가 무조건 `new RemoteLoggingClient(context)`(Google Clearcut 로 사용 통계 전송)를
실행한다. 의존성만 빼면 landmarker 생성 시 `NoClassDefFoundError` 가 난다.

**처리** (잠정 승인): (1) `configurations.all { exclude(group = "com.google.android.datatransport") }` (engine·devapp), (2) `RemoteLoggingClient` 가
링크하는 심볼만 아무것도 하지 않는 스텁으로 제공, (3) 매니페스트에서 두 권한을 `tools:node="remove"`, (4) CI 가 병합 매니페스트에 두 권한이 없는지 검사.
스텁은 `engine/src/main/java/com/google/android/datatransport/` 한 곳에 모았다. 패키지 이름은 MediaPipe 바이트코드가 정하므로 3개 패키지·8개 파일이고,
`DataBoundaryTest.datatransportStubsAreExactlyTheDocumentedSet` 가 이 목록 외의 파일이 없는지 검사한다.

| 스텁 (FQCN) | MediaPipe 가 쓰는 멤버 | 동작 |
|---|---|---|
| `com.google.android.datatransport.runtime.TransportRuntime` | `initialize(Context)`, `getInstance()`, `newFactory(Destination)` | 아무것도 안 함 / 싱글턴 / 드롭 팩토리 |
| `com.google.android.datatransport.runtime.Destination` | 마커 인터페이스 | — |
| `com.google.android.datatransport.cct.CCTDestination` | `INSTANCE` | 싱글턴 |
| `com.google.android.datatransport.TransportFactory` | `getTransport(String, Class, Encoding, Transformer)` | 드롭 `Transport` 반환 |
| `com.google.android.datatransport.Transport` | `send(Event)` | 드롭 |
| `com.google.android.datatransport.Event` | `ofData(Object)` | 페이로드 버림 |
| `com.google.android.datatransport.Encoding` | `of(String)` | 상수 |
| `com.google.android.datatransport.Transformer` | `apply(T)` (람다 SAM) | 인터페이스만 |

**확인 방법** (버전을 올릴 때 반복):

```sh
# 1) tasks-core 가 datatransport 를 어디서 쓰는지, 팩토리가 어떤 로거를 만드는지
curl -sSO https://dl.google.com/dl/android/maven2/com/google/mediapipe/tasks-core/1.0.0/tasks-core-1.0.0.aar
unzip -o tasks-core-1.0.0.aar classes.jar && mkdir -p cls && unzip -qo classes.jar -d cls
grep -rl datatransport cls                       # RemoteLoggingClient 만 나와야 한다
javap -c -p -cp cls com.google.mediapipe.tasks.core.logging.TasksStatsLoggerFactory | grep Logger.create
javap -v -p -cp cls com.google.mediapipe.tasks.core.logging.RemoteLoggingClient | grep -E "Class|Methodref|InterfaceMethodref|Fieldref" | grep datatransport
# 2) 빌드 산출물: 병합 매니페스트 권한과 dex 안의 datatransport 클래스(스텁만 있어야 한다)
grep -o 'android:name="android.permission.[A-Z_]*"' devapp/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml | sort -u
unzip -o devapp/build/outputs/apk/debug/devapp-debug.apk 'classes*.dex' -d dex
for d in dex/classes*.dex; do $ANDROID_HOME/build-tools/37.0.0/dexdump -l plain "$d" | grep -oE "Lcom/google/android/datatransport/[^;]*;"; done | sort -u
# 3) 실기기: devapp 시작 → 상태·요약 첫 줄 "자가 점검: OK (…)" 확인
```

**엔진 자가 점검**: 서비스 시작 시(카메라 바인딩 전, 분석 스레드) Face·Pose landmarker 를 만들고 640x480 회색 더미 프레임 1장을 각각 추론한다.
`Throwable` 까지 잡아(NoClassDefFoundError, UnsatisfiedLinkError 포함) 실패하면 `자가 점검 실패: <예외 클래스>: <메시지>` 를 상태와 요약에 표시하고
세션을 끝낸다. 성공하면 요약 첫 줄이 `자가 점검: OK (Face …ms, Pose …ms, …)` 다. 복원한 요약도 마지막 자가 점검 결과를 첫 줄에 둔다.
실기기에서 스텁 관련 크래시가 확인되면 대안(실제 라이브러리 유지 + 매니페스트에서 INTERNET 과 그 라이브러리의 컴포넌트 제거)으로 바꾼다.

## 모델 파일

| 파일 | 출처 | 버전 경로 | sha256 |
|---|---|---|---|
| `engine/src/main/assets/face_landmarker.task` | https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task | face_landmarker / float16 / 1 (2023-05) | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` |
| `engine/src/main/assets/pose_landmarker_lite.task` | https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task | pose_landmarker_lite / float16 / 1 (2023-04) | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |

Apache-2.0 (MediaPipe models). CI 가 두 해시를 검증한다. 라이브러리 `com.google.mediapipe:tasks-vision:1.0.0` (Google Maven).

## 설치

1. GitHub Releases 의 prerelease **`focus-engine-dev-latest`** 에서 `focus-engine-dev-debug-<커밋해시>.apk` 를 받는다.
   `android/focus-engine/**` 또는 `core/focus-core/**` 변경이 main 에 푸시될 때와 Actions 의 `focus-engine-android` 워크플로를
   수동 실행할 때 갱신되는 rolling prerelease 다. PR 에서는 단위 테스트·assembleDebug·매니페스트 검사만 한다.
2. 디버그 서명 키가 빌드마다 달라서 재설치 전에 앱을 지운다. 앱을 지우면 세션 로그도 지워지므로 먼저 `adb pull` 한다.

   ```sh
   adb pull /sdcard/Android/data/co.byite.focus.devapp/files/focus-engine/ ./focus-engine-logs/
   adb uninstall co.byite.focus.devapp
   adb install focus-engine-dev-debug-<해시>.apk
   ```

3. 앱을 열고 `권한 요청` 으로 CAMERA 와 알림 권한을 허용한다.

로컬 빌드: JDK 17 이상, Android SDK(platforms;android-37.2, build-tools;37.0.0).

```sh
cd android/focus-engine
./gradlew :engine:testDebugUnitTest :devapp:assembleDebug
# → devapp/build/outputs/apk/debug/devapp-debug.apk
```

## 카메라·추론

- 전면 카메라, 1280x720(16:9, 가장 가까운 상위→하위), `[24,24]` 없으면 `[30,30]`(Camera2Interop), 영상·광학 안정화 off,
  `STRATEGY_KEEP_ONLY_LATEST`, Preview 없음, `OUTPUT_IMAGE_FORMAT_RGBA_8888`. YUV + ROI 최적화는 나중.
- 회전은 비트맵을 돌리지 않고 `ImageProcessingOptions.setRotationDegrees(imageInfo.rotationDegrees)` 로 넘긴다.
  `setTargetRotation(ROTATION_0)` 으로 고정했으므로 "upright" = 기기 세로(포트레이트) 방향이다. 가로 거치는 v0 미지원.
- MediaPipe Tasks Vision, CPU delegate, VIDEO 모드, timestamp = 프레임 capture timestamp(monotonic, ms).
  Face Landmarker: num_faces 1, blendshape·transformation matrix on, 전 프레임.
  Pose Landmarker lite: 각 1초 버킷의 첫 프레임에서 1회(1fps). 얼굴이 미검출인 프레임에서는 직전 Pose 뒤 300ms 이상이면 다시 실행(≥3fps).
  SceneQuality: 각 버킷의 첫 프레임(1Hz).
- Face·Scene 은 분석 스레드(`focus-analysis`), Pose 는 `PoseWorker`(`focus-pose`)가 비동기로 돈다. 프레임마다 (E 는 격프레임) wrap → Face 추론 → 후처리 → (Pose 프레임 deep copy·요청) → (Scene) 순서이고 단계마다 wall time 을 잰다("단계별 계측").
  Pose 요청은 버킷의 첫 프레임(1fps)과 얼굴 미검출 중 직전 요청 뒤 300ms 이상(≥3fps); 워커가 바쁘면 대기 슬롯의 이전 요청을 최신 프레임으로 교체한다(`pose_superseded`).
  Pose 결과는 프레임의 capture timestamp 가 속한 버킷에 반영되고, 버킷이 닫힌 뒤(끝 + 300ms) 도착하면 버리고 `pose_late_dropped` 로 센다.
- **프레임 계수**(CHANGELOG v0.2.3 (a); 정의는 `core/focus-core/README.md`): `frames_requested`(CaptureResult), `frames_analyzer_received`(analyzer 콜백), `frames_skipped_intentional`(E 의 건너뜀),
  `frames_processed`(Face 추론이 성공한 직후 확정 — 후처리·Scene·Pose 복사가 실패해도 처리 프레임), `frames_sample_applied`·`frames_sample_late_dropped`. 파생: 백프레셔 = requested − received(KEEP_ONLY_LATEST 가 버린 것),
  미처리(예상 밖) = received − skipped − processed(`face_inference_errors` + `pre_face_errors`(timestamp 역행, wrap 실패, 파이프라인 미준비)), Face 이후 실패 = processed − 반영 − 늦음. `frames_dropped` = 이 넷의 합, 드롭 비율 = ÷ (requested − skipped).
  capture result 가 한 번도 오지 않은 세션은 requested = received. 갭은 처리 프레임의 capture timestamp 차이(버킷 경계를 넘는 갭은 뒤 프레임의 버킷에): `gaps_over_80ms` 와 프리셋 임계 `gaps_over_threshold`.
- **단계별 계측**(`v0b_raw`): `stage_wrap_ms_mean`(ImageProxy → RGBA, zero-copy 검사 또는 행 복사), `face_infer_ms_*`(landmarker), `stage_face_post_ms_mean`(HeadPose·폭·j), `stage_scene_ms_mean`(1Hz),
  `pose_frame_copy_ms_mean/p95/max`(deep copy, ~3.7MB @1280x720), `frame_total_ms_mean/p95/max`(analyzer 콜백 진입부터 aggregation 큐 post 직전까지 = Face 사이클; G 가 보고하는 값), `pose_wait_ms_mean`(요청 → 워커 추론 시작), `pose_infer_ms_*`.
- **세션 창**: `t_start_mono_ms` = **첫 수신 프레임의 capture timestamp**(카메라 바인딩 시각이 아니다). 버킷은 여기에 정렬한 `[t, t+1000)` 이고 버킷 끝 + 300ms 뒤에 닫아 늦게 오는 입력을 기다린다.
  첫 프레임 전에 온 capture result 는 보관했다가 세션 시작 뒤에 넣되 timestamp 가 시작 전이면 계수에서 뺀다; 정지 fence 이후 timestamp 의 입력도 뺀다(`inputsBeforeStart`/`inputsAfterFence`, 요약 ※ 줄).
  프레임이 없는 초도 레코드를 남긴다(`frames_processed 0`). 첫 버킷은 항상 scene 표본을 가지므로 `scene_luma` 는 언제나 측정값이다.
- IMU: 가속도계 5Hz(200,000µs), `focus-status` 스레드에서 받아 aggregation 큐에 post. 캘리브레이션(거치 자세)이 없어 `imu_state` 는 분류하지 않고 원시 통계만
  `v0b_raw` 에 남긴다.
- Timebase: camera `SENSOR_INFO_TIMESTAMP_SOURCE` 가 REALTIME 이면 offset 0(R1 결과). 아니면 처음 100프레임의
  min(콜백 elapsedRealtimeNanos − 프레임 ts) 를 offset 으로 쓰고 1분마다 다시 잰다. IMU 는 항상 25표본으로 추정(대개 0 근처).
  `timebase` 줄을 세션 시작과 1분마다 쓴다.

## 스칼라 정의

**yaw·pitch·roll 부호 규약** (`HeadPose`). transformation matrix 는 column-major 4x4(`m[c*4+r]`)이고 3x3 회전 R 은
canonical face → 카메라 공간(X 오른쪽, Y 위, Z 카메라 쪽; 정면 응시 = I)이다. 랜드마크와 R 은 회전 전 버퍼 기준으로
나오므로 R_upright = Rz(−θ)·R 로 보정한 뒤(θ = `rotationDegrees`, 시계 방향) f = R·(0,0,1)(코 방향), u = R·(0,1,0)(정수리 방향)에서:

| 각 | 식 | 양수의 뜻 |
|---|---|---|
| yaw | atan2(f_x, f_z) | +Y(위) 축 오른손 회전. 코가 영상 오른쪽으로 = 사용자가 **자기 왼쪽**으로 고개를 돌림(분석 영상은 미러가 아니다) |
| pitch | atan2(f_y, √(f_x²+f_z²)) (고도각) | 위를 봄. 아래를 보면 음수(스펙의 "θ 하강" 과 같은 방향) |
| roll | atan2(−u_x, u_y) | +Z(카메라) 축 오른손 회전. 정수리가 영상 왼쪽으로 기움(정면에서 보는 사람에게 반시계) |

실측 첫 세션에서 확인할 것: 정면 응시 시 roll ≈ 0, 자기 왼쪽으로 고개를 돌리면 yaw > 0, 책상을 보면 pitch < 0.
행렬의 아래 행이 (0,0,0,1)이 아니면(레이아웃 가정 위반) events.log 에 `transform_matrix_layout_unexpected` 를 한 번 남긴다.

**얼굴 폭 px**: face-oval 랜드마크 234↔454 의 버퍼 픽셀 거리.

**강체 잔차 지터 j** (`RigidJitter`; 스펙 6장, 정의는 CHANGELOG v0.2.2 (b)). 정확한 정의:

1. 강체 부분집합 14점 — 콧등 168·6·197·195·5, 눈꼬리·눈머리 33·133·362·263, 이마 10·151·9·108·337 — 을 버퍼 픽셀 3차원 점 p_i = (x·W, y·H, z·W) 로 둔다
   (z 는 MediaPipe 정규화 z 에 폭을 곱한 것, 회전 보정 없음: 두 프레임이 같은 좌표계면 충분하다).
2. 직전 얼굴 프레임의 점 P 를 현재 프레임의 점 Q 에 similarity 변환(스케일 s, 회전 R, 평행이동 t)으로 최소제곱 정합한다:
   min Σ|s·R·p_i + t − q_i|². R 은 Horn(1987)의 폐형식(중심을 뺀 교차공분산으로 만든 4x4 행렬의 최대 고윳값 고유벡터 = 단위 쿼터니언, Jacobi 반복),
   s = Σ(R·p_i)·q_i ÷ Σ|p_i|², t 는 중심 차이.
3. j = √(Σ|s·R·p_i + t − q_i|² / 14) ÷ 현재 프레임의 양안 거리. 양안 거리 = 왼눈 중심(33·133 중점)과 오른눈 중심(362·263 중점)의 버퍼 픽셀 거리.
   j 는 무단위(양안 거리 대비 비율)다.
4. 제외(null): 머리 각속도 = transformation matrix 회전 블록의 상대 회전각 acos((tr(R_prevᵀR_cur) − 1)/2) ÷ Δt 가 30°/s 초과인 쌍,
   직전 얼굴 프레임이 500ms 보다 오래된 쌍, 얼굴 재검출 직후(기준 없음), 양안 거리 0. 제외된 쌍의 수는 events.log 의 `jitter_skipped_fast_rotation` 에 남는다.
5. 초당 `jitter_j` 는 그 초의 프레임 j 평균.

스펙과 다른 점: canonical 공간 역변환은 Java API 가 metric 랜드마크를 주지 않아 쓸 수 없고, 대신 similarity 정합이 강체 운동과 거리 변화(스케일)를
흡수한다. baseline 도 같은 방법으로 잰다. 단위 테스트: 순수 강체 이동·회전·스케일에서 j ≈ 0, 랜드마크별 독립 잡음에서 j > 0, 빠른 회전 쌍 제외.

**Pose** (`PoseGeometry`, BlazePose 33점): 어깨 11·12, 코 0, 귀 7·8. 버퍼 정규화 좌표를 upright 로 돌린 뒤 픽셀로 잰다.
`shoulder_visibility_min` = min(vis 11, vis 12). `shoulder_center_x/y` = 어깨 중점 ÷ upright 폭/높이,
`shoulder_width` = 어깨 픽셀 거리 ÷ upright 폭. `head_landmark_present` = 코 visibility ≥ 0.5, 아니면 visibility ≥ 0.5 인 귀가 있음(엔진 가정).
`head_offset_below_shoulder_ratio` = (머리 y − 어깨 중점 y) ÷ 어깨 폭(픽셀, 아래가 양수; 머리 = 코, 없으면 보이는 귀의 평균).
`pose_motion` = 어깨 중점의 변위 ÷ 어깨 폭, 기준은 0.9초 이상 3초 이하 전의 가장 최근 Pose 표본. 초당 값은 버킷의 마지막 검출 표본.

**SceneQuality** (`SceneGrid`): 64x48 격자(셀 중심)에서 RGBA → BT.601 휘도. `scene_luma` = 전체 평균, tile texture = 4x4 tile
(16x12 표본) 안 휘도의 모표준편차 → 최솟값·중앙값(`v0b_raw`). `bg_tile_texture_ratio` 는 캘리브레이션 기준이 없어 null.

## 기록

세션마다 `/sdcard/Android/data/co.byite.focus.devapp/files/focus-engine/<yyyyMMdd_HHmmss>/`:

```
session.jsonl   header, timebase(시작·1분마다), 초당 second + v0b_raw, session_end   (focus-core JsonlCodec 형식)
events.log      카메라 상태, 첫 프레임, fps 범위 변화, 마커, 오류 (스칼라·문자열)
summary.txt     정지 시 요약. 서비스가 죽었으면 다음 실행 때 JSONL 로 복원한 요약
```

- `second` 줄은 focus-core `SecondRecord`(스키마 0.2.3) 그대로다. 캘리브레이션과 게이트가 없어 다음을 비운다: `raw_state`,
  `final_state`, `invalid_reason`, `candidate_*`, `events`(빈 목록), `torso_center_offset_ratio`, `torso_width_ratio`, `zone_id`,
  `bg_tile_texture_ratio`. null 을 허용하지 않는 필드는 CHANGELOG v0.2.2 (a) 의 결정대로 채운다:

  | 필드 | 값 | 이유 |
  |---|---|---|
  | `zone_status` | `uncalibrated` | 작업영역이 아직 없다(캘리브레이션 전·진행 중). V0-B 레코드는 얼굴 유무와 무관하게 전부 이 값 |
  | `imu_state` | `UNKNOWN` | 거치 자세 기준이 없어 DOCKED/LIFTED 등을 분류할 수 없음 |
  | `power_state` | `P0` | 전력 상태 머신 미구현 |
  | header `calibration_id`, `calibration_snapshot_version` | `"none"` | 캘리브레이션 없음 |
  | `scene_luma` | 그 초의 측정값. 표본이 없는 초만 직전 측정값 | 상수 자리표시 금지. 세션이 첫 처리 프레임에서 시작하므로 항상 측정값이 있다 |
  | `face_detect_ratio` | 처리 프레임 0 이면 0.0 | non-null double (0/0) |
  | `frames_requested` | capture result 가 한 번도 없으면 수신 수 | 드롭 계산의 분모(− skipped) |

- `v0b_raw` 줄(focus-core `V0bRawRecord`, 스칼라만): `segment_label`(마커), 어깨 중심·폭(정규화), `pose_samples`(= `pose_applied`),
  tile texture 최솟값·중앙값, `scene_samples`, Face 추론 ms 평균·p95·최대, Pose 추론 ms 평균·최대, 프레임 콜백 지연 평균, 단계별 계측(위), Pose 계수(`pose_requested`·`pose_completed`·`pose_applied`·`pose_superseded`·`pose_late_dropped`·`pose_errors`),
  `face_inference_errors`·`pre_face_errors`, IMU 표본 수·축별 평균·분산 합, thermal status, 배터리 %·전류(µA 원값)·전압(mV), 화면 on, Doze.
- header 에 프리셋(`capture_preset`, `frame_process_divisor`, `frame_gap_threshold_ms`, `face_delegate`, `face_blendshapes`, `perf_hint_target_ms`)이 들어간다.
- 마커는 버킷 시작 시각에 활성이던 마커를 그 초에 붙인다. 마커 변경 시각은 events.log 에도 남는다.
- 요약 첫 줄은 엔진 자가 점검 결과(`자가 점검: OK (프리셋 …, perf hint …)` 또는 실패 내용)이고 둘째 줄이 계수 검증 결과다. 복원한 요약도 같다.
- flush: 닫힌 레코드 30개(= 30초)마다 IO 스레드에서 직렬화해 파일에 쓰고 flush 한다. header·timebase·session_end 는 즉시. 프로세스가 죽으면
  마지막 flush 이후 최대 30초를 잃는다.
- 복원(R6): 앱을 다시 열면 `session_active` 플래그가 남은 세션을 감지해 `session.jsonl` 을 읽고(잘린 마지막 줄은 버린다),
  마지막 레코드 시각에 `session_end(PROCESS_DEATH_RECOVERED)` 를 덧붙인 뒤 같은 `V0bReport` 로 요약을 만든다.
  시스템이 서비스만 내리는 경우(`onDestroy`)에도 버퍼를 flush 만 하고 종료 줄은 다음 실행의 복원이 쓴다(v0.2.1 판정 10).

## 요약 (화면 + summary.txt + 클립보드)

`core/focus-core` 의 `V0bReport` 가 `session.jsonl` 과 같은 레코드로 만든다(실시간·복원 동일; 정지 정보만 실시간 경로가 더한다).

- 첫머리: 자가 점검 줄 다음에 `계수 보존식: 이상 없음` / `계수 불일치: <항목> …`(줄마다 하나) / `계수 검증 생략(비정상 종료)`.
- 전체: 세션 길이, 초당 레코드 수, 프레임 계수(요청·분석기 수신·의도적 건너뜀·처리·표본 반영·표본 늦어 폐기)와 드롭 분해(백프레셔 / 미처리(예상 밖: Face 추론 오류·Face 이전 오류) / Face 이후 실패 / 늦어 폐기), 드롭 비율(÷ 요청−건너뜀), 처리 fps(처리 프레임 ÷ 레코드 수),
  프리셋 임계 초과 갭(참고로 80ms 초과), 최대 갭, 누락된 초(= 레코드 없는 초 + 프레임 0인 초), Face 추론 ms(평균 = 프레임 가중 평균, p95 = **초당 평균값의** nearest-rank p95,
  최대 = 초당 최댓값의 최대), Pose 계수(요청·대기 중 교체·완료(반영·늦어 폐기)·오류·종료 시 취소)와 추론 ms, 최고 thermal status, 배터리 % 시작→끝, 평균 전류(µA, 원값 부호),
  추정 평균 전력(mW = |전류 µA| × 전압 mV ÷ 10⁶ 의 초 평균), 화면 off 행, idle 행.
- **[비교 통계]** 세션 시작 뒤 60초(워밍업)와 화면 상태가 바뀐 직후 5초(전환)를 뺀 초를 **화면 off 행**과 **화면 on 행**으로 나누고, 워밍업·전환은 각각 한 행으로 따로 보인다.
  열: 초, fps, 드롭%(백프레셔/미처리/Face후/늦음), 갭>임계 %(= gaps_over_threshold ÷ 처리 프레임), Face ms 평균/p95/최대, 사이클 ms 평균/p95/최대, wrap/post/scene ms, pose copy 평균/p95/최대, Pose ms 평균/최대·대기, mA, mW, thermal.
- **합격(프리셋 X, 화면 off 행 기준)**: 처리 fps ≥ 23.5 ÷ divisor(E 는 11.75), 갭 초과 비율 < 1%, 드롭 비율 < 1% 를 모두 만족하면 합격. 옆에 화면 off 평균 전력(mW)과 그 행의 최고 thermal 을 같이 적는다 — G 는 성능과 전력·발열을 함께 본다. off 행이 없으면 "판정 불가".
- **[화면 상태 구간]** 연속 on/off 구간마다 시작·끝(세션 시작 기준 초), 상태, 초(괄호 안은 워밍업·전환으로 제외한 초), Face ms 평균/p95, 드롭%, mW.
- **[10초 추이]** 10초 창마다 초 수, fps, 드롭%, 갭>임계 수, Face ms, 사이클 ms, Pose ms, thermal, mA, 화면 on 초.
- 마커별: 길이(초), face_detect_ratio(반영 표본 가중), yaw·pitch·roll 평균 ± 표준편차(초당 평균값 기준, 모표준편차), 얼굴 폭 중앙값,
  j 중앙값·p95, `shoulder_visibility_min ≥ 0.6` 인 초 비율, 머리 landmark 있는 초 비율, head_offset 중앙값, 휘도 평균.
  마커가 없던 초는 `(마커 없음)` 행에 모은다.
- ※ 줄: 늦게 도착한 scene/IMU 표본 수, timestamp 역행, 세션 창 밖 입력 수, barrier 시간 초과, 정지 시 취소된 Face 작업.

## 실측 절차 (사람이 수행, 화면 off)

폰은 거치대에 세로로 세워 전면 카메라가 사람 쪽을 보게 둔다. 마커를 누를 때만 화면을 켜고 바로 끈다(잠금 상태에서 앱은 계속 포그라운드).
세션마다 프리셋 선택 → `시작` → 시나리오 → 화면 켜고 `정지` → `요약 복사`. 요약 전문과 기기·OS·거치 높이·프리셋을 기록에 붙인다.
`정지` 버튼은 정상 종료 순서를 밟으므로 요약 둘째 줄이 `계수 보존식: 이상 없음` 이어야 한다; `계수 불일치` 가 나오면 events.log 의 `stop_sequence`·`counter_mismatch` 줄을 함께 기록한다.

1. **정면 착석 10분.** 시작 직후 `정면` 마커. 중간에 2분은 `가만히` 마커를 누르고 가만히 있는다. 처음 30초 안에 한 번은 자기 왼쪽으로
   고개를 30° 정도 돌려 yaw 부호를 확인한다(위 표: yaw > 0 이어야 한다), 책상을 보면 pitch < 0.
   기준: `[정면]`·`[가만히]` face ≥ 99%, `[가만히]` 구간 yaw·pitch 표준편차 < 2°.
2. **평소처럼 공부 30분.** 마커 없이 둔다. 기준: 합격 줄(화면 off 행: 처리 fps ≥ 23.5, 갭 초과 < 1%, 드롭 < 1%), thermal ≤ 1(LIGHT). 화면 off 평균 전력(mW)을 기록한다.
3. **낮은 거치**에서 마커 순서대로: `정면` 1분 → `숙임`(필기) 1분 → `엎드림` 1분 → `자리비움` 30초 → `빈의자`(의자에 옷 걸기) 30초.
   `정지` 후 같은 순서를 **눈높이 거치**에서 반복한다(별도 세션). 마커별 face·머리 landmark·어깨 vis·head_offset 통계가 V0-C/D 설계 자료다.
4. **프리셋 비교(R4).** 같은 자리·같은 조명에서 A → B → C → D → E → G 순서로 각 10분 이상, 화면 off 위주로 돈다(처음 60초는 워밍업으로 요약에서 빠진다).
   비교 중 화면을 켜야 하면 한 번에 5초 이상 켜 두어 전환 구간(5초)과 on 행이 구분되게 한다. 프리셋마다 요약의 합격 줄, 화면 off 행, `[화면 상태 구간]`, 자가 점검 줄
   (D: GPU 생성 여부, G: hint 세션 생성 여부)을 기록한다. C 는 header `capture_preset` 이 C 인지 C2 인지, `camera_resolution` 이 무엇이었는지 적는다.
   화면 on/off 에 따른 Face 시간 차이는 같은 세션의 on 행과 off 행으로 비교한다(세션을 나누지 않는다).

세션이 끝나면 `adb pull` 로 로그를 받아 `gt/sessions/` 에 둔다(git 에 올리지 않는다).

## 알려진 한계·가정

- 세로 거치만 지원한다(target rotation 고정). 가로 거치는 upright 기준이 어긋나 yaw/roll 이 뒤바뀐다.
- 프리셋 F(ROI 크롭)는 구현하지 않았다(위 사유). D 의 GPU delegate 와 G 의 hint 세션은 기기가 거부할 수 있고, 그 사실은 자가 점검 줄과 header 에만 남는다(자동 대체 없음).
- `frames_analyzer_received` 가 `frames_requested` 보다 큰 초(capture result 가 300ms 안에 안 온 경우)는 백프레셔를 0 으로 자른다; 세션 총계에서 같은 일이 생기면 `계수 불일치: frames_requested < frames_analyzer_received` 로 드러난다.
- Pose 워커의 대기 슬롯이 1 이라 얼굴 미검출 중 ≥3fps 요청은 워커가 밀리면 `pose_superseded` 로 줄어든다.
- `imu_state`·`zone_status`·`power_state` 는 위 표의 값이다. 판정 코드가 이 값을 읽으면 안 된다(V0-C/D 에서 채운다).
- j 는 similarity 정합 잔차 정의다(위). baseline 도 같은 방법으로 재야 한다.
- Face 추론 p95 는 초당 평균값의 p95 다. 프레임 단위 p95 는 로그에 남기지 않는다(초당 레코드에 배열을 두지 않는 규칙).
- 24fps 도 30fps 도 지원 목록에 없으면 fps 범위를 지정하지 않고 HAL 기본값으로 돈다. events.log 의 `session_start` 줄에 `fps_selected=unset` 으로 남는다.
- 앱을 지우면 세션 로그도 지워진다. 재설치 전에 `adb pull` 한다.
