# android/focus-engine

V0-A/B: Android 측정 엔진 골격과 Face/Pose raw feature 기록, R4 성능 실험(프리셋 비교 계측, 카메라 cadence 실험). 게이트 판정은 없다.
지시문은 `directives/C-v0ab-raw-features.md`, `directives/D-r4-perf-experiments.md`, `directives/E-camera-fps-and-skip-fix.md`, 정본은 `docs/focus/spec-v0.2.0.md` 1·6·7·9장과
`docs/focus/v0-plan-and-gt.md` 2~4장, 기록 스키마는 `core/focus-core` (0.2.4).
spike-r1(R1~R3)의 foreground service, Camera2Interop fps 고정, 요약 복원·클립보드 복사,
Release 게시 워크플로를 그대로 이어받았다. 스키마·정의 결정은 `CHANGELOG.md` v0.2.2·v0.2.3·v0.2.4 와
`docs/research-notes/RN-002-mediapipe-telemetry-and-jitter.md`, `RN-003-r4-presets-counters-and-threads.md`.

## 구성

| 경로 | 내용 |
|---|---|
| `engine/` | `:engine` Android 라이브러리. `CaptureService`(camera 타입 FGS, 서비스 lifecycle 에 CameraX 바인딩, wakelock, 스레드 모델·정지 순서), `CapturePreset`(프리셋 A~E·E15·G·H12·H15·Hvar, `CameraFpsRequest`, `PresetResolution`), `CameraCapabilities`(전면 카메라 AE range 조회), `pipeline/camera/CameraPipeline`(분석 스레드; focus-core `FrameScheduler` 로 처리 슬롯 결정·계수), `pipeline/face/FacePipeline`(+`HeadPose`, `RigidJitter`), `pipeline/pose/PosePipeline`(+`PoseGeometry`)·`PoseWorker`(비동기 Pose, 큐 깊이 1), `pipeline/scene/SceneQuality`(+`SceneGrid`), `MotionPipeline`(IMU 5Hz), `timebase/Timebase`(+`ClockOffsetEstimator`; fence 시 offset freeze), `FeatureLogger`(JSONL, IO 스레드 직렬화), `SessionRecovery`, `DeviceStatusReader`(힌지 초기값 규칙 포함) |
| `engine/src/main/assets/` | MediaPipe 모델 파일 2개 (아래 표) |
| `engine/src/main/java/com/google/android/datatransport/` | MediaPipe 가 끌고 오는 Google 텔레메트리 라이브러리의 no-op 스텁 8개 (아래 "MediaPipe 원격 통계 로깅 차단") |
| `devapp/` | `:devapp` 개발용 앱. 프리셋 선택(세션마다 하나; 전면 카메라가 지원하는 H 변형만 목록에, E15 는 항상), 시작/정지, 구간 마커 6개(정면, 가만히, 숙임, 엎드림, 자리비움, 빈의자), 상태(1Hz), 요약, 요약 복사. edge-to-edge 인셋 처리 |
| `../../core/focus-core` | composite build (`includeBuild`) 로 참조하는 순수 로직. 처리 슬롯 스케줄러 `FrameScheduler`, 초당 집계 `FeatureAggregator`(+`CounterTotals`, `CounterConsistency`, `GapThresholds`, `StopSequence`), 로그 모델, 요약 `V0bReport` |

패키지 `co.byite.focus.engine` (엔진), `co.byite.focus.devapp` (앱, applicationId 도 같다).
minSdk 29, compileSdk 37.2, targetSdk 37. AGP 9.4.1, Kotlin 2.4.20, CameraX 1.6.2, MediaPipe Tasks Vision 1.0.0(`strictly` 고정).
APK 는 arm64-v8a 만 담는다(실측 기기 기준, `devapp/build.gradle.kts` 의 `abiFilters`).

기기 층(이 모듈)은 프레임과 센서 값을 스칼라로 줄여 aggregation 큐에 post 하고, 그 큐를 소유한 스레드가 `FeatureAggregator`(focus-core)로
집계한 `SecondRecord` + `v0b_raw` 를 `FeatureLogger` 가 IO 스레드에서 JSONL 로 쓴다. 판정·캘리브레이션은 다음 단계다.

## 프리셋 (R4 실험, 지시문 D·E)

세션마다 devapp 에서 하나를 고른다. A 가 기준이고 나머지는 A 에서 최소한만 바꾼다. header 의 `capture_preset` 에 기록되고 요약 첫머리에 나온다.

| id | A 와 다른 점 | header (0.2.4) |
|---|---|---|
| A | 1280x720(16:9), Face CPU, blendshape on, Face 매 프레임, 카메라 `[24,24]`(없으면 `[30,30]`) | `face_schedule every_frame`, `face_process_period_ns 41666667`, `frame_gap_threshold_ns 62500000`(ms 필드 63), `frame_long_gap_threshold_ns 187500001`(ms 188), `camera_fps_request_lower/upper 24/24` |
| B | blendshape off | `face_blendshapes false` |
| C | 640x360(16:9) 요청. 기기의 YUV 출력 크기에 640x360 이 없으면 면적이 가장 가까운 16:9 크기, 그것도 없으면 640x480 을 **C2** 로 표시(`PresetResolution.choose`) | `capture_preset C` 또는 `C2` |
| D | Face GPU delegate(`Delegate.GPU`). 생성 실패는 자가 점검이 잡는다 | `face_delegate GPU` |
| E | 카메라 24fps, Face 12Hz **처리 슬롯**(3장 규칙, 주기 83.3ms; 아래 "처리 슬롯"). 슬롯이 아닌 수신 프레임은 `frames_skipped_intentional` | `face_schedule slot`, `face_process_period_ns 83333333`, 갭 임계 125/375ms, `frame_process_divisor 2`(옛 필드) |
| E15 | 카메라 24fps, Face 15Hz 처리 슬롯(주기 66.7ms). 나머지는 E 와 같다 | `face_schedule slot`, `face_process_period_ns 66666667`, 갭 임계 100/300ms |
| G | Face 분석 스레드(`focus-analysis`)에만 `PerformanceHintManager` 세션(목표 35ms). 매 Face 사이클(analyzer 콜백 1회: wrap + Face + post + scene + pose copy) 뒤 `reportActualWorkDuration` 으로 실제 처리 시간을 보고한다. Pose 스레드는 세션에 넣지 않고 스레드 우선순위는 바꾸지 않는다. API 31 미만이거나 세션 생성이 null 이면 자가 점검 줄에 남기고 header `perf_hint_target_ms` 는 null | `perf_hint_target_ms 35` |
| H12 | 카메라 자체 fps 를 고정 `[12,12]` 로 낮춘다. Face 매 프레임, Pose 계약 그대로(평상시 1fps, 얼굴 미검출 중 3fps). 독립변수는 카메라 fps 하나 | `camera_fps_request 12/12`, `face_process_period_ns 83333333`, 갭 임계 125/375ms |
| H15 | 고정 `[15,15]`, 나머지 H12 와 같다 | `camera_fps_request 15/15`, `face_process_period_ns 66666667`, 갭 임계 100/300ms |
| Hvar | 상한 15 인 **가변** range(기기가 주는 것 중 하한이 가장 낮은 것, 예: `[7,15]`). Face 매 프레임. 슬롯 계수·갭 계수는 상한 15fps 기준 진단값으로만 기록하고 합격·cadence 판정을 적용하지 않는다(요약에 갭 임계 n/a) | `camera_fps_request 7/15`, `nominal_fps 15` |
| F | ROI 크롭. **구현하지 않았다**(아래 "프리셋 F") | — |

비교 짝(지시문 E): **H12 ↔ E**(Face 12Hz), **H15 ↔ E15**(Face 15Hz). Face 빈도가 같은 짝만 카메라 cadence 효과로 해석하고 H15 를 E 와 직접 비교하지 않는다. devapp 는 `CameraCapabilities.frontCameraFpsRanges` 로 전면 카메라의 AE range 를 조회해 `[12,12]` 가 없으면 H12 를, `[15,15]` 가 없으면 H15 를, 상한 15 가변 range 가 없으면 Hvar 를 목록에서 뺀다(E15 는 항상). 요청이 불가능한 프리셋으로 서비스가 시작되면 `fps_range_unavailable` 을 남기고 세션을 끝낸다.

**갭 임계(0.2.4)** 는 프리셋 상수가 아니라 공식이다(`GapThresholds`): `gap_threshold = 기대 처리 간격 × 1.5`, `long_gap_threshold = × 4.5`. 기대 처리 간격은 Face 매 프레임 구성에서 카메라 프레임 간격(요청 range 상한 기준), 슬롯 구성에서 슬롯 주기다. ns 로 계산해 header `frame_gap_threshold_ns`·`frame_long_gap_threshold_ns` 에 쓰고 요약에서만 ms 로 표시한다(옛 `_ms` 필드는 반올림값). 24fps 매 프레임에서는 기존 80/200ms 와 같은 프레임 수에서 갈린다.

요약과 header 의 해상도는 요청 해상도가 아니라 CameraX 가 실제로 정한 해상도(`ImageAnalysis.resolutionInfo`, 첫 프레임으로 재확인)이고 종횡비를 같이 표시한다(`1280x720 (16:9)`).
header 에는 카메라 id(`camera_id`)와 렌즈 방향(`lens_facing`, `LENS_FACING`), 힌지 센서 감지 여부(`hinge_sensor` = `TYPE_HINGE_ANGLE` 센서 존재)도 들어가고, 센서가 있으면 `focus-status` 스레드가 hinge 각을 받아 초당 `hinge_angle_deg` 로 남긴다.
요약은 이를 `카메라 id 1 (FRONT) 1280x720 (16:9) @ 24fps` 와 `힌지 센서 감지: 펼침 100% (hinge 평균 179°)`(< 30° 접힘, < 150° 반접힘, 그 외 펼침)로 보인다. 센서가 없으면 `힌지 센서 없음(접힘 상태 미상)` 이다 — 센서가 없다는 사실이지 "폴더블이 아니다" 는 뜻이 아니다.
events.log 의 `resolution_choice`(요청·사유·기기의 YUV 출력 크기 목록)와 `camera_bound`(실제 선택) 줄로 확인한다.

**프리셋 F(ROI 크롭)를 건너뛴 사유**: 정정 2 의 규칙대로 하면 2D 랜드마크·얼굴 폭·위치 스칼라는 crop 의 위치·크기·배율로 역변환하면 되지만, head pose 는 좌표 변환으로
동일성을 가정할 수 없어(크롭 창이 중심에서 벗어나면 주점이 옮겨져 transformation matrix 의 yaw·pitch 가 치우친다) 같은 자세에서 A 와 F 를 실기기로 반복 측정해 편향을
재야 한다(허용 2°). 이 세션에는 실기기가 없고 A~E·G 와 계수·스레드 모델의 범위가 이미 크다. 구현한다면: `FacePipeline.infer` 앞에서 직전 얼굴 박스 주변을 잘라 별도 버퍼에
복사하고 `extract` 에서 `x·cropW + cropX` 로 되돌린다, 편향 측정 절차를 아래 실측 절차에 추가한다, 허용 오차를 넘으면 F 의 yaw·pitch 를 G2 입력으로 쓰지 않는다고 PR 본문에 적는다.

## 스레드 모델 (지시문 D 정정 3)

| 스레드 | 소유 | 큐로 넘기는 것 |
|---|---|---|
| `main` | 서비스 lifecycle, CameraX bind/unbind, 알림 | — |
| `focus-analysis` (`THREAD_PRIORITY_FOREGROUND`) | CameraX analyzer 와 CaptureResult 콜백(둘 다 이 스레드로 post): `FrameScheduler`(처리 슬롯 결정, 시작 전 CaptureResult 보관·replay, 카메라 계수·슬롯 계수 방출, fence·CLOSE), wrap, Face 추론·후처리, Scene(1Hz), Pose 프레임 deep copy, G 의 hint 세션, landmarker 생성·해제 | `CameraCounterSink`(requested/received/skipped/오류/slot expected·filled·missed/after_close, 모두 `CameraStamp`), `ProcessedFrame`(Face 결과 + 단계 시간), `SceneSample`, `onPoseRequested/Superseded` → aggregation 큐 |
| `focus-pose` | `PoseWorker`: 대기 슬롯 1 + 실행 중 1, 픽셀 버퍼 2개(밖으로 안 나감, `release` 에서 해제), Pose landmarker 추론 | `PoseSample`, `onPoseError` → aggregation 큐 |
| `focus-aggregate` | **`FeatureAggregator` 의 유일한 접근자**, 레코드 목록, 1Hz 버킷 닫힘(tick), timebase 줄, 상태 문자열, `FeatureLogger.append`(직렬화 없이 객체만 큐잉) | 레코드 → IO 큐 |
| `focus-io` (`THREAD_PRIORITY_BACKGROUND`) | JSONL 직렬화 + `session.jsonl` 쓰기(30초 배치), `events.log`, `summary.txt` | — |
| `focus-status` | 1Hz `DeviceStatusReader.read()`(thermal·배터리·keyguard binder 호출), IMU `SensorEventListener`, hinge 각 `SensorEventListener`(폴더블) | `DeviceSample`(최신값, hinge 각 포함), `ImuSample` → aggregation 큐 |
| `focus-stop` | 정상 종료의 `StopSequence` 실행(아래) | — |

분석 스레드에는 추론·스칼라 추출·memcpy 만 남고 파일 IO·직렬화·binder 호출은 없다. 어느 파이프라인도 집계기를 직접 호출하지 않는다(`DataBoundaryTest` 와 별개로 코드 구조로 보장: `CaptureService` 의 Listener 구현은 전부 `aggHandler.post`).

## 정상 종료 순서 (지시문 D 정정 5 + 지시문 E 5장, `StopSequence`)

논리 단계 ①~⑧ 은 events.log 의 `steps=`(`StopSequence.ORDER` 이름 10개: ① = `stop_inputs`>`raise_fence`>`await_analysis_idle`, ② = `close_slot_scheduler`, ③ = `close_pose_slot`, ④ = `await_pose_idle`, ⑤ = `drain_aggregation_queue`, ⑥ = `finish`, ⑦ = `check_counters`, ⑧ = `write_end`)에 대응한다.
`정지` 버튼(`ACTION_STOP`) → `focus-stop` 스레드에서 순서대로: ① `main` 에서 **fence 설정**(`chooseFence`, 한 번만): `Timebase.freezeCameraOffset()` 로 카메라 offset 을 고정(`offsetSnapshot`; 이후 추정기를 갱신하지 않고 모든 카메라 변환이 snapshot 을 쓴다), `stopFenceMonoNs = elapsedRealtime`, `stopFenceRawTs = stopFenceMonoNs − offsetSnapshot`, 그리고 **producer 를 멈추기 전에** `CameraPipeline.requestFence` 로 fence 를 넘긴다 — 분석 스레드는 다음 CaptureResult·프레임 진입 시 `FrameScheduler.fence` 를 적용하고 같은 FIFO 로 aggregation 큐에 `stopInputs(fence, fenceRaw)` 를 먼저 post 한다(`Listener.onStopFence`); 그 다음 IMU·hinge 해제·camera unbind. `rawSensorTs ≥ stopFenceRawTs` 인 카메라 입력은 슬롯도 아니고 계수에도 들지 않으며 분석기도 거부한다. **CaptureResult 콜백 drain**: unbind 뒤 카메라 HAL 이 아직 배달 중인 CaptureResult 를 기다린다 — CameraX `CameraState.CLOSED`(상한 1.5초) 뒤 CaptureResult 가 프레임 간격 3개 동안 오지 않을 때까지(상한 500ms); 그 뒤 분석 스레드에 marker 를 post 해 실행 중인 Face·Scene 작업이 끝나길 기다린다(상한 500ms). 걸린 시간은 `stop_sequence` 줄의 `capture_result_drain` 에 남는다. 넘으면 `AnalysisGate.cancel()` 이 그 작업의 generation 을 올려 결과가 큐에 post 되지도 계수에 들지도 않게 하고, `received` 를 이미 낸 프레임 하나가 `frames_cancelled_at_stop` 이 된다(그 슬롯은 CLOSE 에서 missed) → ② 분석 스레드에서 **슬롯 scheduler CLOSE**: 미해결 슬롯을 전부 `missed` 로 종결한다. CLOSE 뒤 도착한 CaptureResult 는 fence 전 캡처라도 expected 에 넣지 않고 `capture_results_after_close` 로 센다 — 정상 종료에서는 0 이어야 하며 0 이 아니면 `stop_integrity_failed` → ③ Pose 대기 슬롯 폐쇄(대기 중 요청은 `pose_cancelled_at_stop`) → ④ 실행 중 Pose 를 상한 500ms 기다린다. 넘으면 `PoseWorker.awaitIdle` 이 generation 을 올려 그 결과는 post 되지 않고(`pose_completed` 에 들지 않고) `pose_cancelled_at_stop` 으로만 센다 → ⑤ status 스레드 marker → aggregation 큐 barrier(제한 3초) → ⑥ 집계 스레드에서 `StopFinalizer.finish(fence)`: fence 이전에 끝난 완전한 버킷만 닫고 `session_end` 를 fence 시각으로 만든다(`t_utc = t_start_utc + (fence − t_start_mono)`; `session_end` 줄에 `capture_results_before_start/after_fence/after_close` 와 `stop_integrity_failed` 도 적는다); 이 단계가 fence 뒤 몇백 ms 에 돌아도 fence 이후 버킷은 생기지 않는다 → ⑦ `CounterConsistency.check(totals, cancelled…)`(슬롯 보존식 `expected = filled + missed` 포함) → ⑧ `session_end` 줄, 요약(첫머리에 검사 결과·cadence 불일치·stop_integrity_failed), `summary.txt`. 그 뒤 분석 스레드에서 Face landmarker·hint 세션 해제(GPU delegate 는 만든 스레드에서 닫는다)와 `PoseWorker.release()`; Pose landmarker 는 실행 중인 추론이 없으면 그 자리에서, 있으면 워커가 추론이 돌아온 뒤 스스로 닫는다(추론 중 close 금지, 종료 경로는 기다리지 않는다). events.log 에 `stop_sequence steps=… fence=… fence_raw_ns=… offset_snapshot_ns=… frames_cancelled=… slots_closed_as_missed=… pose_cancelled=… drained=… mismatches=… capture_after_close=… stop_integrity_failed=…`, `counter_mismatch …`, 그리고 `frames …` 통계 줄에 스케줄러 계수(`slots expected/filled/missed`, `capture_after_close`, `frames_rejected_after_fence`)가 남는다.
요약의 세션 길이·처리 fps 분모·마지막 화면 상태 구간·전력 평균은 모두 fence 까지의 레코드로 계산된다. 이 마무리는 순수 로직(`StopFinalizer`)이라 `CaptureService` 와 `StopFinalizerTest`·`StopSequenceTest` 가 같은 코드를 쓴다.
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
  Face Landmarker: num_faces 1, blendshape·transformation matrix on, 매 프레임(슬롯 프리셋 E·E15 는 3장 규칙의 슬롯 프레임만).
  Pose Landmarker lite: 각 1초 버킷의 첫 프레임에서 1회(1fps). 얼굴이 미검출인 프레임에서는 직전 Pose 뒤 300ms 이상이면 다시 실행(≥3fps).
  SceneQuality: 각 버킷의 첫 프레임(1Hz).
- **처리 슬롯**(지시문 E 3장, focus-core `FrameScheduler`, 분석 스레드): 입력은 CaptureResult 의 raw `SENSOR_TIMESTAMP` 스트림이다(ImageAnalysis 수신 순번이 아니다; CaptureResult 보다 먼저 온 ImageAnalysis 프레임은 그 raw ts 로 스트림에 들어가고 뒤에 온 CaptureResult 는 중복이다). anchor = 세션 창 안 첫 CaptureResult raw ts, `next_due = anchor`. `ts ≥ next_due − 카메라 프레임 간격/2` 를 처음 만족하는 프레임이 처리 기회(`processing_slots_expected` +1)이고, 선택 뒤 `next_due` 는 선택 프레임에 재앵커하지 않고 처리 주기만큼 전진하며 캡처 공백으로 뒤처졌으면 `next_due > ts` 가 될 때까지 주기 단위로 catch-up 한다(목표 위상 유지: 24fps → 15Hz 는 0, 83.3, 125, 208.3, 250, 333.3 …, 평균 66.7ms; 재앵커 방식은 24 → 15Hz 가 12Hz 로 붕괴한다). Face 매 프레임 구성(A~D, G, H*)은 창 안의 모든 CaptureResult 가 슬롯이다. 슬롯의 프레임이 Face 추론에 성공하면 `filled`, backpressure 로 분석기에 못 오거나(뒤 프레임 수신 시 확정) pre-face/Face 오류이거나 CLOSE 까지 미해결이면 `missed`. 세 계수는 독립 terminal counter 이고 `expected = filled + missed` 를 종료 검사에 넣는다. 세션 시작 전 CaptureResult 는 raw 와 함께 보관했다가 첫 프레임에서 replay 한다(첫 프레임의 CaptureResult 가 첫 슬롯).
- Face·Scene 은 분석 스레드(`focus-analysis`), Pose 는 `PoseWorker`(`focus-pose`)가 비동기로 돈다. 슬롯 프레임마다 wrap → Face 추론 → 후처리 → (Pose 프레임 deep copy·요청) → (Scene) 순서이고 단계마다 wall time 을 잰다("단계별 계측").
  Pose 요청은 버킷의 첫 프레임(1fps)과 얼굴 미검출 중 직전 요청 뒤 300ms 이상(≥3fps); 워커가 바쁘면 대기 슬롯의 이전 요청을 최신 프레임으로 교체한다(`pose_superseded`).
  Pose 결과는 프레임의 capture timestamp 가 속한 버킷에 반영되고, 버킷이 닫힌 뒤(끝 + 300ms) 도착하면 버리고 `pose_late_dropped` 로 센다.
- **프레임 계수**(CHANGELOG v0.2.3 (a); 정의는 `core/focus-core/README.md`): `frames_requested`(CaptureResult), `frames_analyzer_received`(analyzer 콜백), `frames_skipped_intentional`(E·E15 의 슬롯 아닌 수신 프레임),
  `frames_processed`("Face 추론이 성공했다"는 뜻의 카운터. 분석 스레드는 Face 성공 직후 그 사실을 `ProcessedFrame` 하나에 담아 post 하고, 실제 증가는 aggregation 스레드가 그 메시지를 적용할 때 일어난다 — 후처리·Scene·Pose 복사가 실패해도 처리 프레임이다), `frames_sample_applied`·`frames_sample_late_dropped`. 파생: 백프레셔 = requested − received(KEEP_ONLY_LATEST 가 버린 것),
  미처리(예상 밖) = received − skipped − processed(`face_inference_errors` + `pre_face_errors`(timestamp 역행, wrap 실패, 파이프라인 미준비)), Face 이후 실패 = processed − 반영 − 늦음. `frames_dropped` = 이 넷의 합, 드롭 비율 = ÷ (requested − skipped).
  capture result 가 한 번도 오지 않은 세션은 requested = received. 갭은 처리 프레임의 capture timestamp(mono) 차이(버킷 경계를 넘는 갭은 뒤 프레임의 버킷에): `gaps_over_80ms`, 공식 임계 `gaps_over_threshold`(기대 처리 간격 × 1.5: 24fps 62.5ms, 15Hz 100ms, 12Hz 125ms), 긴 갭 `gaps_over_long_threshold`(× 4.5: 187.5 / 300 / 375ms; 합격선은 0). CaptureResult 사이의 raw 간격은 `capture_interval_ms_median/p95/max` 로 남겨 요약의 실측 cadence 가 된다.
- **갭 원인**(원래 지시문 2번, `v0b_raw` `gap_cause_*`): 임계를 넘은 갭마다 직전 처리 프레임 사이클의 가장 긴 단계(변환·전처리, Face = 추론 + 후처리, scene, Pose 복사, 큐 적재)를 원인으로 센다.
  직전 사이클 전체가 기대 간격(임계 ÷ 2)보다 짧았거나 표본이 없으면 분석 스레드 탓이 아니므로 "그 외"(카메라·시스템)로 센다. 요약의 비교 행마다 상위 원인을 적는다.
  한계: 큐 적재 시간은 마지막 `ProcessedFrame` post 를 다음 프레임에 계상하므로 "큐 적재" 원인은 한 프레임 밀려 귀속될 수 있다(µs 단위라 순위에 영향은 거의 없다).
- **단계별 계측**(`v0b_raw`, 각 평균·p95·최대): `stage_wrap_ms_*`(변환·전처리: ImageProxy → RGBA, zero-copy 검사 또는 행 복사), `face_infer_ms_*`(landmarker), `stage_face_post_ms_*`(HeadPose·폭·j), `stage_scene_ms_*`(1Hz),
  `stage_enqueue_ms_*`(큐 적재: 이 프레임의 메시지 post 시간 합; 마지막 `ProcessedFrame` post 는 다음 프레임에 계상), `pose_frame_copy_ms_*`(deep copy, ~3.7MB @1280x720), `frame_total_ms_*`(analyzer 콜백 진입부터 aggregation 큐 post 직전까지 = Face 사이클; G 가 보고하는 값),
  `pose_wait_ms_mean`(요청 → 워커 추론 시작), `pose_infer_ms_mean/p95/max`.
- **timestamp 영역과 세션 창**(지시문 E 4장, CHANGELOG v0.2.4 (b)): 모든 카메라 기원 이벤트는 `CameraStamp(rawSensorTs, captureMonoNs)` 를 가진다. **raw**(`CaptureResult.SENSOR_TIMESTAMP` = `ImageProxy.imageInfo.timestamp`)가 프레임 identity 이고 세션 소속·슬롯·카메라 계수 보존식을 정한다; **mono**(`Timebase` 변환값)는 버킷 위치·갭·지연·JSONL 시간축이다. 변환된 mono 로 프레임을 대응시키거나 소속을 판정하지 않는다(`cameraToMono` 는 추정기를 먹이고 `cameraToMonoNoSample` 은 아니어서 UNKNOWN 기기에서 같은 프레임에 다른 값을 줄 수 있다).
  `t_start_mono_ms` = **첫 수신 프레임의 capture timestamp**(카메라 바인딩 시각이 아니다), `sessionStartRawTs` = 그 프레임의 raw timestamp. 소속 판정은 `sessionStartRawTs ≤ rawSensorTs < stopFenceRawTs` 하나뿐이고, 창 안으로 인정된 입력의 버킷은 mono 위치를 [첫 버킷, 마지막 부분 버킷] 으로 clamp 해 정한다(첫 프레임의 CaptureResult 가 옛 offset 으로 시작 mono 보다 앞서도 첫 버킷, fence 전 프레임의 mono 가 fence mono 를 넘어도 마지막 부분 버킷에 applied). clamp 는 late_dropped 와 별개다(원래 버킷이 300ms close delay 뒤 이미 닫혔으면 기존대로 늦어 폐기). IMU·기기 상태처럼 raw 카메라 timestamp 가 없는 입력은 mono fence 를 쓴다.
  버킷은 시작에 정렬한 `[t, t+1000)` 이고 버킷 끝 + 300ms 뒤에 닫아 늦게 오는 입력을 기다린다. raw 창 밖의 입력만 `inputsBeforeStart`/`inputsAfterFence`(CaptureResult 는 따로 `capture_results_before_start/after_fence`; 요약 ※ 줄과 `session_end`).
  프레임이 없는 초도 레코드를 남긴다(`frames_processed 0`). 첫 버킷은 항상 scene 표본을 가지므로 `scene_luma` 는 언제나 측정값이다.
- IMU: 가속도계 5Hz(200,000µs), `focus-status` 스레드에서 받아 aggregation 큐에 post. 캘리브레이션(거치 자세)이 없어 `imu_state` 는 분류하지 않고 원시 통계만
  `v0b_raw` 에 남긴다.
- Timebase: camera `SENSOR_INFO_TIMESTAMP_SOURCE` 가 REALTIME 이면 offset 0(R1 결과). 아니면 처음 100프레임의
  min(콜백 elapsedRealtimeNanos − 프레임 ts) 를 offset 으로 쓰고 1분마다 다시 잰다. 정지 fence 에서 `freezeCameraOffset()` 으로 고정한다. IMU 는 항상 25표본으로 추정(대개 0 근처).
  `timebase` 줄을 세션 시작과 1분마다 쓴다.
- 힌지 센서(지시문 E 6장): `TYPE_HINGE_ANGLE` 은 변화 시에만 이벤트를 주므로 등록 뒤 2초 안에 이벤트가 없으면 마지막으로 알려진 값(프로세스 또는 `EnginePrefs.lastHingeAngleDeg` 에 남은 이전 세션 값)을 쓰고, 그것도 없으면 "미상"(null)으로 둔다. 세션 간에 마지막 값을 유지한다. events.log 의 `hinge_initial source=event|last_known|unknown`.

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

- `second` 줄은 focus-core `SecondRecord` 그대로다(header 는 스키마 0.2.4; `second` 필드는 0.2.3 과 같고 0.2.4 추가분은 `v0b_raw`·`session_end`·header 에 있다). 캘리브레이션과 게이트가 없어 다음을 비운다: `raw_state`,
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
- header 에 프리셋(`capture_preset`, `frame_process_divisor`, `frame_gap_threshold_ms`, `face_delegate`, `face_blendshapes`, `perf_hint_target_ms`)과 0.2.4 의 카메라 cadence·스케줄(`camera_fps_request_lower/upper`, `camera_fps_ranges_supported`, `face_schedule`, `face_process_period_ns`, `frame_gap_threshold_ns`, `frame_long_gap_threshold_ns`)이 들어간다. `v0b_raw` 에 슬롯 계수 3종과 `capture_interval_ms_*`, `session_end` 에 진단 계수 3종과 `stop_integrity_failed`.
- 마커는 버킷 시작 시각에 활성이던 마커를 그 초에 붙인다. 마커 변경 시각은 events.log 에도 남는다.
- 요약 첫 줄은 엔진 자가 점검 결과(`자가 점검: OK (프리셋 …, perf hint …)` 또는 실패 내용)이고 둘째 줄이 계수 검증 결과다. 복원한 요약도 같다.
- flush: 닫힌 레코드 30개(= 30초)마다 IO 스레드에서 직렬화해 파일에 쓰고 flush 한다. header·timebase·session_end 는 즉시. 프로세스가 죽으면
  마지막 flush 이후 최대 30초를 잃는다.
- 복원(R6): 앱을 다시 열면 `session_active` 플래그가 남은 세션을 감지해 `session.jsonl` 을 읽고(잘린 마지막 줄은 버린다),
  마지막 레코드 시각에 `session_end(PROCESS_DEATH_RECOVERED)` 를 덧붙인 뒤 같은 `V0bReport` 로 요약을 만든다.
  시스템이 서비스만 내리는 경우(`onDestroy`)에도 버퍼를 flush 만 하고 종료 줄은 다음 실행의 복원이 쓴다(v0.2.1 판정 10).

## 요약 (화면 + summary.txt + 클립보드)

`core/focus-core` 의 `V0bReport` 가 `session.jsonl` 과 같은 레코드로 만든다(실시간·복원 동일; 정지 정보만 실시간 경로가 더한다).

- 첫머리: 자가 점검 줄 다음에 `계수 보존식: 이상 없음` / `계수 불일치: <항목> …`(줄마다 하나) / `계수 검증 생략(비정상 종료)`, 그 다음 해당 시 `cadence 불일치: …`(고정 range 요청인데 CaptureResult 실측 cadence 가 요청값과 3% 넘게 다름; 짝 비교 제외), `정상 종료 무결성 실패(stop_integrity_failed): …`(CLOSE 뒤 CaptureResult 도착; 짝 비교 제외), `경고: 배터리 20% 미만`. 이어서 `프리셋 <id> (…)` 로 시작하는 제목 줄.
- 카메라 cadence 줄: 요청 AE range(고정/가변), 지원 range 전체, CaptureResult 실측 cadence(평균 = Σ 요청 ÷ 초; 간격 중앙값 = 초당 중앙값의 중앙값, p95 = 초당 p95 의 nearest-rank p95, 최대), 분석기 수신 fps, Face 처리 fps(기대 Hz).
- 전체: 세션 길이, 초당 레코드 수, 프레임 계수(요청·분석기 수신·의도적 건너뜀·처리·표본 반영·표본 늦어 폐기)와 드롭 분해(백프레셔 / 미처리(예상 밖: Face 추론 오류·Face 이전 오류) / Face 이후 실패 / 늦어 폐기), 드롭 비율(÷ 요청−건너뜀), 처리 fps(처리 프레임 ÷ 레코드 수),
  처리 슬롯(expected·filled·missed, slot_miss_ratio = missed ÷ expected; 정상 정지에서는 fence 까지의 세션 총계도), 진단 계수(capture_results_before_start/after_fence/after_close)와 stop_integrity_failed, 갭 임계 실제값(ms; Hvar 는 n/a 와 상한 기준 진단값),
  임계 초과 갭(참고로 80ms 초과), 최대 갭, 누락된 초(= 레코드 없는 초 + 프레임 0인 초), Face 추론 ms(평균 = 프레임 가중 평균, p95 = **초당 평균값의** nearest-rank p95,
  최대 = 초당 최댓값의 최대), Pose 계수(요청·대기 중 교체·완료(반영·늦어 폐기)·오류·종료 시 취소)와 추론 ms, 최고 thermal status, 배터리 % 시작→끝, 평균 전류(**mA** 를 앞에, µA 원값 병기),
  추정 평균 전력(mW = |전류 µA| × 전압 mV ÷ 10⁶ 의 초 평균), 화면 off 행, idle 행.
- **[비교 통계]** 세션 시작 뒤 60초(워밍업)와 화면 상태가 바뀐 직후 5초(전환)를 뺀 초를 **화면 off 행**과 **화면 on 행**으로 나누고, 워밍업·전환은 각각 한 행으로 따로 보인다. 행마다 세 줄:
  (1) 초, fps, slot miss%(missed/expected), 드롭%(백프레셔/미처리/Face후/늦음), 갭>임계 %(= gaps_over_threshold ÷ 처리 프레임)와 수, 갭>긴 임계 수, 최대 갭, Face ms 평균/p95/최대, 사이클 ms 평균/p95/최대, mA, mW, thermal, 카메라 fps(간격 중앙값/p95/최대 ms);
  (2) 단계 ms(각 평균/p95/최대): 변환·전처리, face_post, scene, 큐 적재, Pose 복사, Pose 추론(+ 대기 평균);
  (3) 갭>임계 원인: 많은 순서(예: `Face 5, 그 외 3, scene 1`). p95 는 초당 평균값의 nearest-rank p95, 최대는 초당 최댓값의 최대.
- **합격(프리셋 X, 화면 off 행 기준)**: 처리 fps ≥ 기대 처리율 × 23.5/24(A 23.5, E·H12 11.75, E15·H15 14.69), 갭 초과 비율 < 1%, **드롭 = slot_miss_ratio < 1%**(raw 드롭 비율은 옆에 진단값; 슬롯 계수가 없는 옛 로그는 raw 드롭), 긴 갭(long_gap_threshold 초과) 0 을 모두 만족하면 합격. 옆에 화면 off 평균 전류(mA)·전력(mW)과 그 행의 최고 thermal 을 같이 적는다 — G 는 성능과 전력·발열을 함께 본다. off 행이 없으면 "판정 불가". **Hvar** 는 `합격 판정 안 함(가변 cadence)` 으로 처리 fps·slot_miss_ratio(진단)·raw 드롭·CaptureResult 간격 분포·처리 프레임 갭 분포만 보고한다.
- **[화면 상태 구간]** 연속 on/off 구간마다 시작·끝(세션 시작 기준 초), 상태, 초(괄호 안은 워밍업·전환으로 제외한 초), Face ms 평균/p95, 드롭%, mW.
- **[N초 추이]** 10초 창마다 초 수, 처리 fps, face 비율, yaw/pitch/roll 평균, 드롭%, 갭>임계 수, Face ms, 사이클 ms, Pose ms, thermal, mA, 화면 on 초. 60행을 넘으면 창을 10초 단위로 늘린다(30분 세션 → 30초 창 60행).
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
2. **평소처럼 공부 30분.** 마커 없이 둔다. 기준: 합격 줄(화면 off 행: 처리 fps ≥ 기대 처리율 × 23.5/24(A 23.5), 갭 초과 < 1%, slot_miss_ratio < 1%(raw 드롭은 진단값), long_gap_threshold 초과 0(24fps 매 프레임 187.5ms)), thermal ≤ 1(LIGHT). 화면 off 평균 전류(mA)·전력(mW)과 갭 원인 줄을 기록한다.
3. **낮은 거치**에서 마커 순서대로: `정면` 1분 → `숙임`(필기) 1분 → `엎드림` 1분 → `자리비움` 30초 → `빈의자`(의자에 옷 걸기) 30초.
   `정지` 후 같은 순서를 **눈높이 거치**에서 반복한다(별도 세션). 마커별 face·머리 landmark·어깨 vis·head_offset 통계가 V0-C/D 설계 자료다.
4. **프리셋 비교(R4).** 같은 자리·같은 조명에서 A → B → C → D → E → G 순서로 각 10분 이상, 화면 off 위주로 돈다(처음 60초는 워밍업으로 요약에서 빠진다).
   비교 중 화면을 켜야 하면 한 번에 5초 이상 켜 두어 전환 구간(5초)과 on 행이 구분되게 한다. 프리셋마다 요약의 합격 줄, 화면 off 행, `[화면 상태 구간]`, 자가 점검 줄
   (D: GPU 생성 여부, G: hint 세션 생성 여부)을 기록한다. C 는 header `capture_preset` 이 C 인지 C2 인지, `camera_resolution` 이 무엇이었는지 적는다.
   화면 on/off 에 따른 Face 시간 차이는 같은 세션의 on 행과 off 행으로 비교한다(세션을 나누지 않는다). A·B·C·D·E·G 의 첫 실측 결과는 RN-003 "실측" 절에 있다.
5. **카메라 cadence 실험(지시문 E).** 50% 이상 충전하고 thermal NONE 뒤 시작한다. 접은 상태, 거치·책·자세 고정, 충전기 제거, 세션 사이 충전 금지.
   - `[12,12]` 지원 기기: A(5분) → E(8분) → H12(8분) → E(8분) → A(5분).
   - `[15,15]` 만 지원: A(5분) → E15(8분) → H15(8분) → E15(8분) → E(8분) → A(5분).
   A 는 워밍업 60초 뒤 화면 ON/OFF 60초 교대. 첫 A 의 워밍업에서 정면 → 왼쪽 → 정면 → 오른쪽 → 내려다보기(10초 구간 하나를 채움) → 정면 → 위. E·E15·H 는 워밍업 60초 뒤 화면 off 7분. 세션마다 요약을 복사한다.
   짝 비교는 H12 ↔ E, H15 ↔ E15 만이고(Face 빈도가 같은 짝만 카메라 효과), 요약 첫머리에 `cadence 불일치` 또는 `stop_integrity_failed` 가 있는 세션은 짝 비교에 쓰지 않는다. Hvar 는 고정 cadence 와 따로 표시하고 요청 range·실측 cadence 분포·갭 분포만 본다.

세션이 끝나면 `adb pull` 로 로그를 받아 `gt/sessions/` 에 둔다(git 에 올리지 않는다).

## 알려진 한계·가정

- 세로 거치만 지원한다(target rotation 고정). 가로 거치는 upright 기준이 어긋나 yaw/roll 이 뒤바뀐다.
- 프리셋 F(ROI 크롭)는 구현하지 않았다(위 사유). D 의 GPU delegate 와 G 의 hint 세션은 기기가 거부할 수 있고, 그 사실은 자가 점검 줄과 header 에만 남는다(자동 대체 없음). H12/H15/Hvar 는 카메라가 그 AE range 를 주지 않으면 목록에서 빠진다.
- Hvar 의 range 는 상한 15 인 가변 range 중 하한이 가장 낮은 것이다(둘 이상이면 AE 가 가장 많이 움직일 수 있는 것). 기록된 `gaps_over_threshold` 와 슬롯 계수는 상한 15fps 기준 진단값이다.
- 캡처 공백 뒤 첫 프레임은 공백 중 due 였던 슬롯을 채우고(선택), 그 다음부터 목표 위상으로 돌아간다. 공백 자체는 슬롯이 아니라 긴 갭으로 드러난다.
- 뒤 프레임보다 늦게 도착한 창 안 CaptureResult(순서 역전): Face 매 프레임 구성에서는 그래도 처리 기회(expected)이고, 뒤 프레임이 이미 수신됐으면 그 자리에서 missed(backpressure 손실); 슬롯 구성(E·E15)에서는 규칙이 이미 지나갔으므로 선택하지 않고 손실은 갭으로 드러난다(`frames …` 줄의 `capture_out_of_order`, `slots_from_out_of_order`).
- CLOSE 뒤 도착한 CaptureResult 중 raw ≥ fence 인 것(카메라가 닫히는 동안 찍힌 프레임)은 after_fence 로만 세고 `stop_integrity_failed` 로 보지 않는다. 지시문 5장의 "fence 전 캡처라도" 는 fence 전 프레임을 포함한다는 뜻으로 읽었고, 무결성 문제는 expected 에서 빠진 fence 전 프레임에만 있기 때문이다(`capture_after_close_post_fence` 로 따로 남긴다).
- `[24,24]` 도 `[30,30]` 도 없는 카메라(fps 요청 없음, HAL 기본값)에서는 기대 처리 간격을 30fps 기준으로 둔다(스펙 7장 "24fps 미지원 기기는 30fps"). 실측 cadence 가 그보다 낮으면 갭 임계·fps 하한이 엄격해진다; 이 경우는 요약의 cadence 줄(요청 unset)로 알 수 있다.
- 슬롯의 `filled`/`missed` 이벤트와 `ProcessedFrame` 은 같은 raw ts 를 가지지만 버킷 위치(mono)는 CaptureResult 경로와 프레임 경로가 다른 offset 을 쓴 경우 ±1초 어긋날 수 있다. 세션 총계는 보존된다.
- `frames_analyzer_received` 가 `frames_requested` 보다 큰 초(capture result 가 300ms 안에 안 온 경우)는 백프레셔를 0 으로 자른다; 세션 총계에서 같은 일이 생기면 `계수 불일치: frames_requested < frames_analyzer_received` 로 드러난다. 첫 프레임의 CaptureResult 가 옛 offset 으로 mono 시작보다 앞서는 경우(UNKNOWN 기기)는 raw 창과 시작 clamp 로 첫 버킷에 들어가 이 불일치가 나지 않는다.
- Pose 워커의 대기 슬롯이 1 이라 얼굴 미검출 중 ≥3fps 요청은 워커가 밀리면 `pose_superseded` 로 줄어든다.
- `imu_state`·`zone_status`·`power_state` 는 위 표의 값이다. 판정 코드가 이 값을 읽으면 안 된다(V0-C/D 에서 채운다).
- j 는 similarity 정합 잔차 정의다(위). baseline 도 같은 방법으로 재야 한다.
- Face 추론 p95 는 초당 평균값의 p95 다. 프레임 단위 p95 는 로그에 남기지 않는다(초당 레코드에 배열을 두지 않는 규칙).
- 24fps 도 30fps 도 지원 목록에 없으면 fps 범위를 지정하지 않고 HAL 기본값으로 돈다. events.log 의 `session_start` 줄에 `fps_selected=unset` 으로 남는다.
- 앱을 지우면 세션 로그도 지워진다. 재설치 전에 `adb pull` 한다.
