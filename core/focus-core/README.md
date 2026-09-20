# core/focus-core

순수 로직 코어(Kotlin JVM). 스펙 v0.2.0 + CHANGELOG v0.2.1·v0.2.2·v0.2.3 의 기록 스키마(0.2.3; 0.2.1·0.2.2 로그도 읽는다), StateFinalizer, 로그 재생, GT 대조 도구를 담는다.
게이트 규칙 전체는 아직 없고 `GateEngine` 인터페이스, 비교용 `NaiveBaselineEngine`, 폰 게이트의 IMU 부분(`PhoneGateTracker`)만 있다.
지시문은 `directives/B-focus-core.md`(+ V0-B 추가는 `directives/C-v0ab-raw-features.md`, R4 계수·요약은 `directives/D-r4-perf-experiments.md`), 정본은 `docs/focus/`(동결), 설계 변경은 `CHANGELOG.md` v0.2.1·v0.2.2·v0.2.3 과 `docs/research-notes/RN-001-v0.2.1-spec-clarifications.md`, `RN-002-mediapipe-telemetry-and-jitter.md`, `RN-003-r4-presets-counters-and-threads.md`.

## 구성

| 경로 | 내용 |
|---|---|
| `focus-core/` | `:focus-core` 라이브러리. Android API·`java.*` 의존 없음(`NoJavaImportTest` 가 검사). KMP 이전 대비 |
| `gt-diff/` | `:gt-diff` JVM 전용 CLI. 파일 IO 는 여기에만 둔다 |
| `samples/` | 합성 T2 세션 로그(스키마 0.2.1), GT, 기본 ParameterSet. 실측 로그가 아니다 |

패키지(`co.byite.focus.core`):

| 패키지 | 내용 |
|---|---|
| `model` | `State`(우선순위 포함), `InvalidReason`, `Event`, `SessionHeader`, `SecondRecord`(스키마 0.2.1), `IntervalRecord`, `SessionEnd`, `CalibrationSnapshot`, `TimebaseRecord`, `V0bRawRecord`(V0-B 원시 스칼라 줄), `ParameterSet`, `BackdateRules`, `FocusSchema` |
| `engine` | `GateEngine`·`GateDecision`, `FaceBand`(얼굴 검출 2단 임계값), `NaiveBaselineEngine`, `PhoneGateTracker`(집어 듦·재거치·재캘리브레이션 상태 기계) |
| `finalizer` | `StateFinalizer`: raw/final 분리, 30초 확정 버퍼, 소급 덮어쓰기 표, flushNow, lifecycle gap, 프로세스 종료 복구 |
| `log` | `JsonlCodec`, `SessionLog`, `FocusJson` |
| `replay` | `ReplayRunner`: 로그 → 엔진 → finalizer 재실행 |
| `gt` | GT 파서·lint, behavior 카탈로그, expected_state 생성기, 초 단위 diff, 합격선, 콘솔 표 |
| `aggregate` | `FeatureAggregator`: 기기 층의 프레임·Pose·Scene·IMU 스칼라(`FrameSample`/`ProcessedFrame`, `PoseSample`, `SceneSample`, `ImuSample`, `DeviceSample`)를 세션 시작에 정렬한 1초 버킷으로 집계해 `SecondRecord` + `V0bRawRecord` 를 낸다(V0-A/B, 지시문 C·D). 단일 스레드 소유, 결정적, 시계 없음. `CounterTotals`·`CounterConsistency`: 세션 총계와 보존식 검사. `StopSequence`: 정상 종료 순서 7단계(지시문 D 정정 5) |
| `report` | `V0bReport`: V0-B/R4 세션 요약(계수 검증 줄, 전체, 화면 on/off 비교 행, 프리셋 합격, 화면 상태 구간표, 10초 추이표, 마커별). 실시간 경로와 프로세스 종료 복원이 같은 함수를 쓴다 |

## 빌드·테스트

```sh
cd core/focus-core
./gradlew :focus-core:test          # 코어 테스트 (CI 와 같은 명령)
./gradlew :gt-diff:test             # CLI 테스트
./gradlew :gt-diff:installDist      # gt-diff/build/install/gt-diff/bin/gt-diff
```

JDK 17 이상. Kotlin 2.2, kotlinx-serialization 1.9, kotlin.test.

## 시간·상태 규약 (v0.2.1 판정)

번호는 CHANGELOG v0.2.1 · RN-001 의 판정 번호다.

- **버킷(1)**: `t_mono_ms = t` 인 초당 레코드는 1초 버킷 `[t, t+1000)` 을 요약한다. 버킷은 세션 시작 시각(`header.t_start_mono_ms`)에 정렬하고 버킷이 끝날 때 기록한다. `t_utc_ms` 는 표시용이다.
- **N초 연속(2)**: 조건을 만족한 버킷 N개 연속. 소급이 있는 상태(ABSENT 3, PRONE 30, PHONE 집어 듦 3)는 N번째 레코드에서 확정하고 후보 시작 = 첫 버킷 시작. 소급이 없는 상태(AWAY, PAUSED)는 처음 N개 버킷 동안 기존 분류를 유지하고 N+1번째부터 그 상태다: AWAY 는 밖 4버킷 뒤 5번째부터, 장기 이탈은 16번째부터, PAUSED 는 120버킷 뒤 121번째부터. `ParameterSet.*Buckets` 가 ms 를 버킷 수로 바꾼다.
- **소급 덮어쓰기(3)** (`BackdateRules`):

  | 소급 상태 | 덮어쓰는 raw_state |
  |---|---|
  | ABSENT, PRONE | PRESENT, AWAY, INVALID(face_missing_unconfirmed, head_missing, face_unstable) |
  | PHONE | 위에 더해 INVALID(phone_shake) |

  어떤 소급도 PHONE, PAUSED, 환경 INVALID(low_light, camera_occluded, fps_low, quality_proxy, redock_pending, recalibration)는 덮지 않는다. 이미 PHONE·PAUSED 로 확정된 final_state 도 덮지 않는다. 환경 INVALID 버킷은 카메라 기반 후보(ABSENT, PRONE, AWAY 유예, 머리 미검출 저움직임 카운터)를 리셋하고 IMU 집어 듦 후보는 리셋하지 않는다(`BackdateRules.resetsCameraCandidates`). 소급 범위는 최대 30초.
- **상태와 우선순위(4)**: 상태는 7개다. `PHONE > PAUSED > INVALID(환경) > ABSENT > PRONE > AWAY > PRESENT` (`State.PRIORITY`). 자동 일시정지 중에도 초당 레코드는 계속 남기고 상태는 PAUSED(소급 없음)다. 레코드를 남길 수 없는 lifecycle gap 만 interval 로 남긴다. "세션 시간에서 뺀다"는 집계 규칙이다: PAUSED 와 INVALID 는 비율의 분모에서 빼고 초 수를 따로 보고한다.
- **T2 2초 자리 비움(5)**: 기대 상태는 INVALID. "ABSENT 0초" 같은 금지 조건은 반응 3초를 포함한 행동 구간 전체(void 만 제외)에서 센다.
- **폰 재거치(6)** (`PhoneGateTracker`): 거치 자세에서 벗어난 채 정지(`RESTING_OFF_DOCK`)하면 집어 듦 확정 여부와 무관하게 재거치 대기다. 그 첫 버킷부터 `redock_pending_invalid_ms`(60초)까지 INVALID(redock_pending), 이후 PHONE(소급 없음, `redock_pending_timeout` 사건). 다시 움직이면 7번 규칙으로 돌아가고 타이머는 다음 정지에서 새로 센다. 재거치 확정은 거치 자세 ±10° 안(`DOCKED`)에서 `redock_stationary_confirm_ms`(2000, 초기값) 연속 정지 또는 탭이고, 확정 뒤 `recalibration_ms`(20000, 초기값) 동안 INVALID(recalibration)다. 확정을 기다리는 정지 버킷은 INVALID(redock_pending)로 둔다(스펙에 없는 구현 가정).
- **집어 듦(7)**: `imu_state` 가 LIFTED 또는 MOVING 인 버킷이 `pickup_confirm_ms`(3초) 만큼 연속이면 확정(기울기 분기와 분산 분기 모두 3초). 3개 미만이면 그 버킷들은 INVALID(phone_shake)이고 끝날 때 `shake` 사건. IMU 가 UNKNOWN 인 버킷은 현재 단계를 유지한다(카운터를 늘리지도 끝내지도 않음, 구현 가정).
- **검출 지연(8)**: interval 시작 큐부터, raw_state 가 그 interval 의 최종 기대 상태로 처음 바뀐 레코드의 `t` 까지. 레코드가 1초 버킷이라 값은 1초 단위로 양자화된다.
- **flapping·비율(9)**: flapping 의 분모는 기대 상태가 일정한 구간의 채점 초 합. 상태 비율 오차는 기대·측정 각각 INVALID 와 PAUSED 를 분모에서 뺀 비율로 계산하고, INVALID·PAUSED 비중의 차이는 `excluded_shares` 로 따로 보고한다.
- **lifecycle gap(10, 11)**: 사유는 기기 층이 `GapReason` 으로 넘긴다. `APP_SWITCH → PHONE`, `SCREEN_LOCK → PAUSED` interval(30초 제한 없음). Android 는 화면이 꺼지거나 다른 앱을 써도 측정이 계속되므로 이 두 gap 을 내지 않는다(초당 레코드의 PHONE 으로 남는다). `PROCESS_DEATH` 는 interval 을 만들지 않고 마지막 기록 시각으로 `session_end(PROCESS_DEATH_RECOVERED)`. 갭이 `lifecycle_gap_session_end_ms`(10분)를 엄격히 초과하면 interval 없이 진입 시각에 `session_end(LIFECYCLE_GAP_TIMEOUT)`.
- **얼굴 검출 2단(12)** (`FaceBand`): `face_detect_ratio < face_missing_max_ratio`(0.2, 초기값)면 G1 의 "얼굴 미검출" 버킷, `>= face_present_min_ratio`(0.5)여야 방향(G2)을 판정한다. 사이는 INVALID(face_unstable), `zone_status = no_head_pose`. `NaiveBaselineEngine` 의 미검출 기준은 `face_missing_max_ratio`.
- **T7c·T5b(13)**: proxy 없이 `invalid_reason == camera_occluded` 초 수와 `redock_confirmed` 사건 수로 잰다.
- **사건의 입력·출력(2차 판정 5)**: 입력 사건은 사용자·기기가 만든 `user_redock_tap`, `zone_added`; 출력 사건은 게이트가 만든 나머지. 재생은 입력 사건만 엔진에 넣고 출력 사건은 다시 계산한 뒤 로그의 출력 사건과 종류·`t_mono_ms` 로 비교해 `reproducibility.output_event_mismatches` 에 남긴다. 불일치는 재현성 실패다. `redock_confirmed{by: tap}` 은 같은 버킷에 `user_redock_tap` 입력이 있을 때만 나온다(레코드 불변식).
- **재캘리브레이션 중단(2차 판정 3)**: 재캘리브레이션 중 움직임이나 off-dock 정지는 `recalibration_aborted` 를 남기고 7번(집어 듦) 또는 6번(재거치 대기) 규칙으로 돌아간다. 세션 종료 때 `PhoneGateTracker.finish(t)` 가 열린 재캘리브레이션을 중단 처리하므로 `recalibration_start` 는 항상 `recalibration_end` 또는 `recalibration_aborted` 와 짝을 이룬다.
- **GT 큐 정렬(2차 판정 7)**: 대본 GT 는 `start_cue_t_mono_ms == header.t_start_mono_ms` 이고 모든 큐 시각(interval 시작·끝)이 버킷 경계(1000ms 배수)여야 한다. 어긋나면 `GtParser` 가 오류를 낸다. 관찰 GT(`gt_type != scripted`)는 큐·void 시각을 가장 가까운 버킷 경계로 반올림하고 경고를 남긴다(`GtParser.alignToSession`, `GtDiff.diff` 가 호출).
- **PAUSED 복귀(14)**: PAUSED 로 끝난 interval 다음 큐 뒤 채점 제외는 6초(반응 3 + 얼굴 재검출 `auto_resume_face_ms` 3). 합격선은 "복귀 큐 뒤 7초 안에 재개"(`GtRules.resume_within_ms`, 초기값).
- 결정성: 시계·난수를 쓰지 않는다. 같은 로그를 재생하면 레코드·interval·SessionEnd 가 완전히 같다(`ReplayResult.sameOutcomeAs`).
- **V0-B 집계(`FeatureAggregator`)**: 버킷은 `t_start + k·1000` 에 정렬하고, 버킷 끝 + `closeDelayMs`(300) 뒤에 닫는다. 프레임이 없는 초도 레코드를 낸다.
  캘리브레이션·게이트가 없는 단계라 `raw_state`·`final_state`·`invalid_reason`·`candidate_*`·`events`·`torso_*_ratio`·`zone_id`·`bg_tile_texture_ratio` 는 비우고,
  null 을 허용하지 않는 필드는 v0.2.2 (a) 대로 `zone_status = uncalibrated`, `imu_state = UNKNOWN`, `power_state = P0`.
  `pose_motion` 의 기준 표본은 0.9~3초 전의 가장 최근 Pose 표본. `scene_luma` 는 항상 측정값이고 버킷에 표본이 없을 때만 직전 값; 표본이 한 번도 없는데
  버킷을 닫으면 예외(세션은 첫 처리 프레임에서 시작해야 한다는 계약).
- **계수(v0.2.3, 지시문 D)**: 집계기는 한 스레드(aggregation 큐)만 접근하고 파이프라인은 스칼라 표본을 그 큐에 post 만 한다. 입력 API 는 `onFrameRequested`(CaptureResult),
  `onFrameReceived`(analyzer 콜백), `onFrameSkipped`, `onFaceInferenceError`, `onPreFaceError`, `onFrameProcessed(ProcessedFrame)`(Face 추론 성공 = `frames_processed` 확정; 표본이 있으면 반영), `onPoseRequested(ns, copyMs)`, `onPoseSuperseded`,
  `onPoseError`, `onPose`, `onScene`, `onImu`, `stopInputs(fence)`. 세션 창 `sessionStartCaptureTs ≤ captureTs < stopFenceCaptureTs` 밖의 입력은 `CounterTotals.inputsBeforeStart`/`inputsAfterFence` 로 센다.
  닫힌 버킷에 도착한 **계수**는 가장 오래된 열린 버킷에 귀속해 총계를 보존하고, 닫힌 버킷에 도착한 **표본**은 버리고 센다(`frames_sample_late_dropped`, `pose_late_dropped`; scene·IMU 는 `lateInputs`).
  `frames_requested` 는 capture result 수(한 번도 없으면 수신 수). `frames_dropped` = 백프레셔 + unprocessed_unexpected + post_face_failed + sample_late_dropped(초당 값은 항마다 0 에서 자른다).
  갭은 처리 프레임(Face 추론 성공)의 capture timestamp 차이: `gaps_over_80ms`(고정 80ms)와 `gaps_over_threshold`(프리셋 임계, 생성자 `gapThresholdNs`).
  `totals` 는 버킷과 무관한 세션 총계이고 `CounterConsistency.check(totals, framesCancelledAtStop, poseCancelledAtStop)` 가 보존식 6개를 검사한다.
- **정상 종료 순서(`StopSequence`)**: ① 입력 정지 + fence + 분석 스레드 idle(상한 500ms, 넘으면 `frames_cancelled_at_stop`) → ② Pose 대기 슬롯 폐쇄 → ③ 실행 중 Pose 상한 500ms(`pose_cancelled_at_stop`) → ④ 큐 barrier·drain →
  ⑤ `finish()` → ⑥ 보존식 검사 → ⑦ `session_end`·요약. 람다로 기기 층이 채우고 순서 자체는 `StopSequenceTest` 가 검사한다.

## 데이터 경계

로그 모델의 직렬화 필드는 스칼라·enum·문자열·사건 목록(`events`: 스칼라 객체의 목록)뿐이다. `SecondRecord` 에는 수치 배열을 두지 않는다.
`calibration` 줄(`CalibrationSnapshot`)에 한해 이름이 정해진 고정 길이 수치 목록만 허용한다: `zones`(≤ 3), `dock_gravity_vector`(3), `bg_tile_texture_baseline`(16), `bg_tile_mask`(16).
`SchemaBoundaryTest` 가 `SerialDescriptor` 를 훑어 이 규칙을 검사하고 `SecondRecord`·`SessionHeader`·`CalibrationSnapshot`·`V0bRawRecord` 필드 목록을 고정한다. `v0b_raw` 줄은 배열·목록 없이 스칼라만 허용한다.

## 세션 JSONL 형식 (feature_schema_version 0.2.3)

첫 줄은 세션 header, 이후 한 줄에 객체 하나. `type` 키로 구분한다(없으면 키로 추론). 빈 줄과 모르는 키는 무시한다.
시간이 있는 줄(calibration, timebase, interval, second, v0b_raw)은 시간 순으로 쓴다. 같은 시각이면 second 뒤에 v0b_raw 가 온다.
`JsonlCodec.encodeHeader/encodeSecond/encodeV0bRaw/...` 는 줄 하나씩 만드는 인코더로, 기기 층의 스트리밍 기록이 쓴다.

```jsonl
{"type":"header","session_id":"S1","participant_id":"P1","t_start_mono_ms":4000000,"t_start_utc_ms":1789000000000,"spec_version":"0.2.0","algorithm_version":"abc123","feature_schema_version":"0.2.1","parameter_set_id":"ps-v0.2.1-default","device_model":"SM-S931N","os_version":"16","camera_resolution":"640x480","nominal_fps":24,"calibration_id":"C1","calibration_snapshot_version":"1","task_mode":"VISUAL"}
{"type":"calibration","calibration_id":"C1","version":"1","t_mono_ms":4000000,"zones":[{"zone_id":0,"yaw_center_deg":0.0,"pitch_center_deg":-8.0,"yaw_half_width_deg":12.0,"pitch_half_width_deg":12.0}],"torso_center_x":0.5,"torso_center_y":0.72,"torso_width":0.38,"m0_pose":0.05,"pose_jitter_floor":0.012,"m0":0.021,"jitter_floor":0.006,"jitter_j_baseline":0.41,"dock_gravity_vector":[0.12,7.21,6.63],"dock_accel_variance":0.0021,"scene_luma_baseline":118.0,"bg_tile_texture_baseline":[12.1, "…16개"],"bg_tile_mask":[true, "…16개"]}
{"type":"timebase","t_mono_ms":4000000,"camera_ts_source":"REALTIME","camera_to_mono_offset_ns":1500000,"imu_to_mono_offset_ns":-2000}
{"type":"second","t_mono_ms":4000000,"t_utc_ms":1789000000000,"raw_state":"PRESENT","final_state":"PRESENT","invalid_reason":null,"candidate_state":null,"candidate_start_mono_ms":null,"events":[],"face_detect_ratio":0.98,"shoulder_visibility_min":0.93,"torso_center_offset_ratio":0.04,"torso_width_ratio":1.01,"head_landmark_present":true,"head_offset_below_shoulder_ratio":-0.8,"yaw_mean":1.2,"pitch_mean":-7.5,"roll_mean":0.3,"zone_status":"in_zone","zone_id":0,"pose_motion":0.05,"scene_luma":118.0,"bg_tile_texture_ratio":0.0,"jitter_j":0.4,"face_width_px":210.0,"imu_state":"DOCKED","screen_state":"OFF","app_state":"BACKGROUND","frames_requested":24,"frames_processed":24,"frames_dropped":0,"max_frame_gap_ms":42,"gaps_over_80ms":0,"power_state":"P0"}
{"type":"interval","t_start_mono_ms":4100000,"t_end_mono_ms":4220000,"t_start_utc_ms":1789000100000,"t_end_utc_ms":1789000220000,"state":"PHONE","reason":"APP_SWITCH"}
{"type":"session_end","t_mono_ms":4300000,"t_utc_ms":1789000300000,"reason":"USER"}
```

`second` 필드:

| 필드 | 형 | 뜻 |
|---|---|---|
| `t_mono_ms`, `t_utc_ms` | long | monotonic 버킷 시작 / wall clock |
| `raw_state`, `final_state` | enum? | `PHONE, INVALID, ABSENT, PRONE, AWAY, PRESENT, PAUSED`. 판정 전 feature 레코드는 null |
| `invalid_reason` | enum? | `low_light, camera_occluded, fps_low, quality_proxy, phone_shake, redock_pending, recalibration, face_missing_unconfirmed, head_missing, face_unstable`. `raw_state != INVALID` 면 null |
| `candidate_state`, `candidate_start_mono_ms` | enum?, long? | 쌓이는 중인 후보(ABSENT, PRONE, PHONE 집어 듦, AWAY 유예, PAUSED 카운터)와 첫 버킷. 둘 다 있거나 둘 다 null |
| `events` | 사건 목록 | `{type, t_mono_ms, by?, zone_id?}`. 입력(사용자·기기): `user_redock_tap`, `zone_added{zone_id}`. 출력(게이트): `pickup_candidate, pickup_confirmed, shake, redock_confirmed{by: orientation\|tap}, redock_pending_timeout, notify_reposition, auto_pause_start, auto_resume, recalibration_start, recalibration_end, recalibration_aborted`. `redock_confirmed{by: tap}` 은 같은 버킷의 `user_redock_tap` 이 필요 |
| `face_detect_ratio` | double | 처리 프레임 중 얼굴 검출 비율 0..1 |
| `shoulder_visibility_min`, `torso_center_offset_ratio`, `torso_width_ratio` | double? | 양 어깨 visibility 최솟값, 어깨 중심 편차 ÷ 캘리브레이션 어깨 폭, 어깨 폭 비율. Pose 없으면 null. 일치 여부는 엔진이 ParameterSet 으로 계산 |
| `head_landmark_present` | bool | Pose nose/ear landmark 유효 |
| `head_offset_below_shoulder_ratio` | double? | (head_y − shoulder_line_y) ÷ 어깨 폭, 양수가 아래. 머리 landmark 없으면 null |
| `yaw_mean`, `pitch_mean`, `roll_mean` | double? | head pose 초 평균(도). 얼굴 없으면 null |
| `zone_status`, `zone_id` | enum, int? | `in_zone, outside, no_head_pose, uncalibrated`. `zone_id` 는 in_zone 일 때만. `uncalibrated`(0.2.2) = 작업영역이 아직 없음(캘리브레이션 전·진행 중) |
| `pose_motion` | double? | 어깨 중심 1초 변위 ÷ 어깨 폭 |
| `scene_luma` | double | 전체 프레임 Y 평균 0..255 |
| `bg_tile_texture_ratio` | double? | 배경 tile 중 texture 가 기준의 25% 미만으로 떨어진 비율. proxy 사용 불가면 null |
| `jitter_j`, `face_width_px` | double? | 강체 잔차 j, 얼굴 폭 px |
| `imu_state` | enum | `DOCKED, RESTING_OFF_DOCK, MOVING, LIFTED, UNKNOWN` |
| `screen_state`, `app_state` | enum | `ON_UNLOCKED, ON_LOCKED, OFF` / `FOREGROUND, BACKGROUND`. 앱 기준 PHONE = ON_UNLOCKED AND BACKGROUND |
| `frames_requested`, `frames_processed`, `frames_analyzer_received`, `frames_skipped_intentional`, `frames_sample_applied`, `frames_sample_late_dropped`, `frames_dropped`, `max_frame_gap_ms`, `gaps_over_80ms`, `gaps_over_threshold` | int × 7, long?, int, int | 프레임 계수(0.2.3, CHANGELOG v0.2.3 (a)). requested = CaptureResult, analyzer_received = ImageAnalysis 콜백, skipped_intentional = 의도적 건너뜀, processed = Face 추론 성공, sample_applied = 표본 반영, sample_late_dropped = 닫힌 버킷 도착 폐기. 파생(`SecondRecord`): `backpressureDrops`, `framesUnprocessedUnexpected`, `framesSampleEnqueued`, `framesPostFaceFailed`, `framesTargeted`; `frames_dropped` = 그 합. 0.2.2 로그는 received·applied = processed, skipped·late = 0, gaps_over_threshold = gaps_over_80ms 로 읽는다. `fps_actual` 은 없고 `SecondRecord.fpsActual` 이 처리 프레임 수에서 계산 |
| `power_state` | enum | `P0, P0_PRIME, P1_PRIME, P1, P2, P3, P4, P5` |

다른 줄:

- `calibration`: `calibration_id, version, t_mono_ms, zones(≤3: zone_id, yaw·pitch 중심·반폭), torso_center_x·y, torso_width(정규화), m0_pose, pose_jitter_floor, m0, jitter_floor, jitter_j_baseline, dock_gravity_vector(3), dock_accel_variance, scene_luma_baseline, bg_tile_texture_baseline(16), bg_tile_mask(16)`. 세션 시작과 재거치 재캘리브레이션 뒤.
- `timebase`: `t_mono_ms, camera_ts_source, camera_to_mono_offset_ns, imu_to_mono_offset_ns`. 세션 시작과 1분마다.
- `interval`: lifecycle gap 한 구간. `state` 는 `reason.state` 와 같아야 한다(`APP_SWITCH → PHONE`, `SCREEN_LOCK → PAUSED`).
- `session_end`: `t_mono_ms, t_utc_ms, reason ∈ {USER, LIFECYCLE_GAP_TIMEOUT, PROCESS_DEATH_RECOVERED, UNKNOWN}`.
- `v0b_raw` (V0-B 단계, 지시문 C·D; 같은 `t_mono_ms` 의 `second` 줄과 짝): 캘리브레이션 전 원시 스칼라. `segment_label`(개발 앱 구간 마커, string?),
  `shoulder_center_x`·`shoulder_center_y`·`shoulder_width`(upright 정규화, 버킷의 마지막 검출 Pose 표본), `pose_samples`(= `pose_applied`),
  `tile_texture_min`·`tile_texture_median`(4×4 tile 의 Y 표준편차), `scene_samples`, `face_infer_ms_mean`·`_p95`·`_max`, `pose_infer_ms_mean`·`_max`,
  `frame_latency_ms_mean`, 단계별 계측(0.2.3) `stage_wrap_ms_mean`·`stage_face_post_ms_mean`·`stage_scene_ms_mean`·`pose_frame_copy_ms_mean`·`_p95`·`_max`·`frame_total_ms_mean`·`_p95`·`_max`(Face 사이클)·`pose_wait_ms_mean`,
  Pose 계수(0.2.3) `pose_requested`·`pose_completed`·`pose_applied`·`pose_superseded`·`pose_late_dropped`·`pose_errors`, 오류 계수(0.2.3) `face_inference_errors`·`pre_face_errors`,
  `imu_samples`, `accel_x_mean`·`accel_y_mean`·`accel_z_mean`·`accel_variance`(축별 분산 합), `thermal_status`,
  `battery_pct`, `battery_current_ua`, `battery_voltage_mv`, `is_interactive`, `is_device_idle`. 재생·GT 대조는 이 줄을 읽지 않는다.
- header 의 0.2.3 추가(지시문 D): `capture_preset`(A, B, C, C2, D, E, G; 없으면 null), `frame_process_divisor`(1; E 는 2), `frame_gap_threshold_ms`(80; E 는 167 @24fps),
  `face_delegate`(CPU/GPU), `face_blendshapes`, `perf_hint_target_ms`(G 의 hint 세션이 실제로 만들어졌을 때만). `camera_resolution` 은 CameraX 가 실제로 정한 해상도이며
  `SessionHeader.cameraAspectRatio` 가 종횡비("16:9")를 계산한다.

재생은 로그의 `raw_state`·`final_state`·`invalid_reason`·`candidate_*` 와 출력 사건을 버리고 다시 계산한다. 입력 사건(`user_redock_tap`, `zone_added`)만 엔진에 넣는다. 다시 계산한 출력 사건은 로그의 출력 사건과 종류·`t_mono_ms` 로 비교해 리포트에 남긴다.

`ParameterSet` JSON 은 `parameter_set_id` 만 필수이고 나머지 임계값은 기본값(스펙 초기값 + v0.2.1 초기값)이다. 전체 목록은 `gt-diff --print-default-params` 또는 `samples/params.json`. 기본 id 는 `ps-v0.2.1-default`.

## GT JSON 형식

`docs/focus/v0-plan-and-gt.md` 5장 형식이다. 시각은 세션 시작 큐(`start_cue_t_mono_ms`) 기준 상대 ms.

```json
{
  "session_id": "S1",
  "scenario_id": "T2",
  "gt_type": "scripted",
  "start_cue_t_mono_ms": 4000000,
  "intervals": [
    {"t_start_ms": 0,      "t_end_ms": 60000, "behavior": "study_in_zone"},
    {"t_start_ms": 60000,  "t_end_ms": 70000, "behavior": "leave_seat"}
  ],
  "void": [{"t_start_ms": 40000, "t_end_ms": 45000, "reason": "큐를 놓침"}]
}
```

- 각 interval 의 시작이 큐다. intervals 는 정렬·비중첩이어야 한다. 사이의 빈 구간은 채점하지 않는다.
- 대본 GT(`gt_type: scripted`)는 `start_cue_t_mono_ms` 가 로그 header 의 `t_start_mono_ms` 와 같아야 하고, 모든 `t_start_ms`·`t_end_ms` 가 1000ms 의 배수(버킷 경계)여야 한다. 어긋나면 파서가 오류를 낸다.
- 관찰 GT(`gt_type` 이 `scripted` 가 아닌 것)는 시작 큐와 각 시각을 세션 버킷 경계로 반올림하고 리포트 `warnings` 에 남긴다. 반올림으로 interval 이 사라지면 오류.
- `expected_state` 는 도구가 `behavior` 에서 생성한다. 파일에 적힌 값은 참고용이며 생성값과 다르면 경고만 낸다. 카탈로그에 없는 behavior 는 `expected_state` 가 있어야 읽을 수 있다.
- 각 큐 뒤 3초(반응 허용)와 void 구간은 채점에서 뺀다. 직전 interval 이 PAUSED 로 끝났으면 6초를 뺀다.
- `phone_redock_recalibrating` 구간이 `redock_stationary_confirm_ms + recalibration_ms`(22000ms)보다 짧으면 경고한다.

behavior → 기대 상태(`BehaviorCatalog`, v0-plan 6장 표):

| behavior | 기대 상태 | 시나리오 |
|---|---|---|
| `study_in_zone` | PRESENT | T1 |
| `leave_seat`, `leave_seat_plain_background`, `leave_seat_jacket_on_chair` | 지속 ≥ 3버킷이면 ABSENT, 아니면 INVALID(확정되지 않은 얼굴 미검출) | T2, T7c, T8 |
| `deep_bow_writing` | INVALID | T3 |
| `prone_head_visible` | PRONE | T4a |
| `prone_head_out_of_frame` | INVALID 120버킷, 121번째부터 PAUSED | T4b |
| `phone_pickup_use` | PHONE | T5a |
| `phone_redock_recalibrating` | INVALID | T5a |
| `phone_use_on_flat_surface` | INVALID 60버킷(구간 시작 = 정지 시작), 61번째부터 PHONE | T5b |
| `desk_bump` | INVALID | T5c |
| `look_away_unregistered` | PRESENT 4버킷, 5번째부터 AWAY | T6a·b·c |
| `look_at_third_zone`, `eyes_only_away` | PRESENT | T6d·e |
| `lights_off`, `camera_covered` | INVALID | T7a·b |
| `other_app_unlocked`, `touch_screen_other_app` | PHONE | T9a·c |
| `notification_screen_on` | PRESENT | T9b |
| `app_killed` | 채점 안 함(T10 종료 검사에만 사용) | T10 |

## gt-diff CLI

```sh
./gradlew :gt-diff:installDist
gt-diff/build/install/gt-diff/bin/gt-diff \
  --log samples/T2-session.jsonl --gt samples/T2-gt.json --params samples/params.json --out report.json
```

| 옵션 | 뜻 |
|---|---|
| `--log` | 세션 JSONL (필수) |
| `--gt` | GT JSON (필수) |
| `--params` | ParameterSet JSON. 없으면 기본값 |
| `--engine naive\|logged` | `naive`(기본): NaiveBaselineEngine 으로 재생. `logged`: 로그에 적힌 raw/final 을 그대로 채점(재생 없음) |
| `--out` | 리포트 JSON 파일. 없으면 stdout |
| `--quiet` | 콘솔 표 생략 |
| `--print-default-params` | 기본 ParameterSet 출력 |

종료 코드: 0 실행 완료(PASS 또는 INCONCLUSIVE), 1 사용법 오류, 2 입력 오류, 3 실행 완료이고 FAIL.

리포트(JSON, 콘솔 표 동일):

1. `seconds`: 레코드 수, 채점 초, 반응·void·미채점 제외 수, GT 밖 레코드, 채점 구간의 누락 레코드.
2. `confusion[expected][final]`(초), `per_state` 의 precision·recall·상태 비율 오차(측정 − 기대, %p; 분모에서 INVALID·PAUSED 제외), `excluded_shares`(INVALID·PAUSED 의 기대·측정 비중과 차이).
3. `detection_latency`: 기대 상태가 바뀌는 interval 마다 큐 → 첫 `raw_state` 전이까지. 중앙값, P95(nearest-rank), 미검출 수, 표본. 1초 양자화.
4. `flapping`: 기대 상태가 일정한 구간 안에서 채점 초끼리 `final_state` 가 바뀐 횟수와 10분당 비율.
5. `false_invalid`, `false_away`: 기대가 그 상태가 아닌데 그 상태인 초의 비율. `invalid_ratio_by_expected` 는 T11 자료.
6. `reproducibility`: 같은 로그 2회 재생 일치 여부, 로그에 적힌 `final_state` 와의 일치율, 출력 사건 비교(`logged_output_events`, `replayed_output_events`, `output_event_mismatches` = 종류·`t_mono_ms` 다중집합의 대칭차). 합격선 `reproducibility` 는 재생 일치이고 불일치 0 일 때만 PASS.
7. `baseline_comparison`: 대상 엔진과 NaiveBaselineEngine 의 false ABSENT·false INVALID·missed ABSENT 초.
8. `pass`: v0-plan 7장 합격선(v0.2.1 정오 반영) 항목별 PASS/FAIL/n/a 와 종합(`overall`: true/false/null). `scenario_id` 로 적용 항목을 고른다(`T4b` 처럼 변형 글자 허용). T7c 는 `invalid_reason == camera_occluded` 초, T5b 는 `redock_confirmed` 사건 수, T4b 재개는 7초.
9. `gt_rules`: 이 리포트에 쓴 채점 규칙·합격선 수치. `warnings`: parameter_set_id·session_id 불일치, GT 경고·lint, 세션 종료 뒤 버린 레코드.

## 라이브러리 사용

```kotlin
val params = ParameterSet.DEFAULT
val log = JsonlCodec.decode(text)
val result = ReplayRunner(NaiveBaselineEngine(params), params).run(log)   // records, intervals, sessionEnd
val report = GtDiff(params).diff(GtParser.parse(gtText), result, baseline = result, loggedRecords = log.records,
    replayTwiceIdentical = result.sameOutcomeAs(ReplayRunner(NaiveBaselineEngine(params), params).run(log)))
println(ConsoleReport.render(report))
```

실시간 경로도 같은 클래스를 쓴다: 초당 레코드마다 `engine.judge(record)` → `finalizer.push(record, decision)`, 백그라운드 진입에 `onBackground`, 복귀에 `onForeground(t, utc, reason)`, 프로세스 종료 복구에 `onForeground(…, PROCESS_DEATH)` 또는 `endSession(lastRecordMono, lastRecordUtc, PROCESS_DEATH_RECOVERED)`.
폰 게이트는 `PhoneGateTracker.judge(t, imuState, record.inputEvents)` 가 버킷마다 PHONE / INVALID(phone_shake, redock_pending, recalibration) / 없음 과 출력 사건을 돌려주고, 이를 합치는 GateEngine 이 환경 INVALID 에서도 이 tracker 를 리셋하지 않으며 세션 종료 때 `finish(t)` 를 불러 열린 재캘리브레이션을 닫는다.
