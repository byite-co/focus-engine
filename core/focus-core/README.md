# core/focus-core

순수 로직 코어(Kotlin JVM). 스펙 v0.2.0 의 기록 스키마, StateFinalizer, 로그 재생, GT 대조 도구를 담는다.
게이트 규칙(G1·G2·폰 사용)은 아직 없고 `GateEngine` 인터페이스와 비교용 `NaiveBaselineEngine` 만 있다.
지시문은 `directives/B-focus-core.md`, 정본은 `docs/focus/` 이다.

## 구성

| 경로 | 내용 |
|---|---|
| `focus-core/` | `:focus-core` 라이브러리. Android API·`java.*` 의존 없음(`NoJavaImportTest` 가 검사). KMP 이전 대비 |
| `gt-diff/` | `:gt-diff` JVM 전용 CLI. 파일 IO 는 여기에만 둔다 |
| `samples/` | 합성 T2 세션 로그, GT, 기본 ParameterSet. 실측 로그가 아니다 |

패키지(`co.byite.focus.core`):

| 패키지 | 내용 |
|---|---|
| `model` | `State`, `SessionHeader`, `SecondRecord`(v0 부분집합), `IntervalRecord`, `SessionEnd`, `ParameterSet`, `FocusSchema` |
| `engine` | `GateEngine`, `GateDecision`, `NaiveBaselineEngine` |
| `finalizer` | `StateFinalizer`: raw/final 분리, 30초 확정 버퍼, 소급, flushNow, lifecycle gap |
| `log` | `JsonlCodec`, `SessionLog`, `FocusJson` |
| `replay` | `ReplayRunner`: 로그 → 엔진 → finalizer 재실행 |
| `gt` | GT 파서, behavior 카탈로그, expected_state 생성기, 초 단위 diff, 합격선, 콘솔 표 |

## 빌드·테스트

```sh
cd core/focus-core
./gradlew :focus-core:test          # 코어 테스트 (CI 와 같은 명령)
./gradlew :gt-diff:test             # CLI 테스트
./gradlew :gt-diff:installDist      # gt-diff/build/install/gt-diff/bin/gt-diff
```

JDK 17 이상. Kotlin 2.2, kotlinx-serialization 1.9, kotlin.test.

## 시간·상태 규약

- 모든 지속시간·윈도우·전이는 `t_mono_ms` 로 계산한다. `t_utc_ms` 는 표시용이다.
- `t_mono_ms = t` 인 초당 레코드는 `[t, t + 1000)` 구간을 요약한다(`FocusSchema.RECORD_PERIOD_MS`).
  스펙은 시작·끝 중 어느 쪽 시각인지 정하지 않아서 이 코드가 정한 가정이다.
- "N초 이상 연속" 은 `(t_now − 후보 시작) + 1000 ≥ N·1000` 으로 센다. 1Hz 에서 얼굴 미검출 레코드 3개면 ABSENT 확정.
- `raw_state` 는 그 초에 앱이 알던 상태, `final_state` 는 소급을 반영한 상태다.
- `StateFinalizer`
  - 레코드는 30초(`finalize_delay_ms`) 더 새로운 레코드가 오면 확정된다. 확정된 레코드는 다시 바뀌지 않는다.
  - 엔진이 ABSENT·PRONE·PHONE 을 확정하며 `candidateStartMonoMs` 를 넘기면 `[max(후보 시작, now − max_backdate_ms), now)` 의
    미확정 레코드가 그 상태로 바뀐다. AWAY·PAUSED 는 후보 시작 시각이 있어도 소급하지 않는다.
  - `flushNow()` / `onBackground()` 는 미확정 레코드를 그 시점 정보로 즉시 확정한다.
  - `onForeground(t, utc, reason)` 은 진입·복귀 시각으로 `IntervalRecord` 를 만든다(30초 제한 없음).
    `APP_SWITCH → PHONE`, `SCREEN_LOCK → PAUSED`. 갭이 `lifecycle_gap_session_end_ms`(10분)를 넘으면 진입 시각에 세션을 종료한다.
  - `endSession(t, utc, reason)` 은 미확정 레코드를 비우고 `SessionEnd` 를 남긴다. 프로세스 종료 복구는 마지막 기록 시각으로 이 함수를 부른다.
- 결정성: 시계·난수를 쓰지 않는다. 같은 로그를 재생하면 레코드·interval·SessionEnd 가 완전히 같다(`ReplayResult.sameOutcomeAs`).

## 데이터 경계

로그 모델(`SessionHeader`, `SecondRecord`, `IntervalRecord`, `SessionEnd`, `ParameterSet`)의 직렬화 필드는 스칼라·enum·문자열·사건 목록뿐이다.
`SchemaBoundaryTest` 가 `SerialDescriptor` 를 훑어 배열형 원시 데이터, 중첩 객체, 맵을 거부한다. `SecondRecord` 필드 목록도 고정 검사한다.

## 세션 JSONL 형식

첫 줄은 세션 header, 이후 한 줄에 객체 하나. `type` 키로 구분한다(없으면 키로 추론). 빈 줄과 모르는 키는 무시한다.

```jsonl
{"type":"header","session_id":"S1","participant_id":"P1","spec_version":"0.2.0","algorithm_version":"abc123","feature_schema_version":"v0.1","parameter_set_id":"ps-v0.2.0-default","device_model":"SM-S931N","os_version":"16","camera_resolution":"640x480","nominal_fps":24,"calibration_id":"C1","calibration_snapshot_version":"1","task_mode":"VISUAL"}
{"type":"second","t_mono_ms":4000000,"t_utc_ms":1789000000000,"raw_state":"PRESENT","final_state":"PRESENT","face_detect_ratio":0.98,"torso_match":true,"head_landmark_present":true,"head_below_shoulder":false,"yaw_mean":1.2,"pitch_mean":-7.5,"roll_mean":0.3,"zone_id":0,"pose_motion":0.05,"scene_luma":118.0,"bg_tile_texture_ratio":0.0,"jitter_j":0.4,"face_width_px":210.0,"imu_state":"DOCKED","app_state":"SCREEN_OFF","fps_actual":24.0,"power_state":"P0"}
{"type":"interval","t_start_mono_ms":4100000,"t_end_mono_ms":4220000,"t_start_utc_ms":1789000100000,"t_end_utc_ms":1789000220000,"state":"PHONE","reason":"APP_SWITCH"}
{"type":"session_end","t_mono_ms":4300000,"t_utc_ms":1789000300000,"reason":"USER"}
```

`second` 필드(v0 부분집합, `feature_schema_version = v0.1`):

| 필드 | 형 | 뜻 |
|---|---|---|
| `t_mono_ms`, `t_utc_ms` | long | monotonic / wall clock. 레코드는 `[t, t+1000)` 요약 |
| `raw_state`, `final_state` | enum? | `PHONE, INVALID, ABSENT, PRONE, AWAY, PRESENT, PAUSED`. 판정 전 feature 레코드는 null |
| `face_detect_ratio` | double | 처리 프레임 중 얼굴 검출 비율 0..1 |
| `torso_match` | bool | Pose 상체가 캘리브레이션 torso ROI 와 일치 |
| `head_landmark_present` | bool | Pose nose/ear landmark 유효 |
| `head_below_shoulder` | bool? | head_y > shoulder_line_y + margin. 머리 landmark 없으면 null |
| `yaw_mean`, `pitch_mean`, `roll_mean` | double? | head pose 초 평균(도). 얼굴 없으면 null |
| `zone_id` | int? | 작업영역 id. null = 밖 또는 판정 불가 |
| `pose_motion` | double? | 어깨 중심 1초 변위 ÷ 어깨 폭. Pose 없으면 null |
| `scene_luma` | double | 전체 프레임 Y 평균 0..255 |
| `bg_tile_texture_ratio` | double? | 배경 tile 중 texture 가 기준의 25% 미만으로 떨어진 비율. proxy 사용 불가면 null |
| `jitter_j` | double? | 강체 잔차 j |
| `face_width_px` | double? | 얼굴 폭 px |
| `imu_state` | enum | `DOCKED, RESTING_OFF_DOCK, MOVING, LIFTED, UNKNOWN` |
| `app_state` | enum | `FOREGROUND, SCREEN_OFF, LOCKED, OTHER_APP` |
| `fps_actual` | double | 실제 처리 프레임 수 |
| `power_state` | enum | `P0, P0_PRIME, P1_PRIME, P1, P2, P3, P4, P5` (스펙 7장 P0′, P1′) |

`interval` 은 lifecycle gap 한 구간: 시작·종료 `t_*_mono_ms`(+utc), `state`(PHONE 또는 PAUSED), `reason`(`APP_SWITCH`, `SCREEN_LOCK`).
`session_end` 는 선택이며 `reason` 은 `USER, LIFECYCLE_GAP_TIMEOUT, PROCESS_DEATH_RECOVERED, UNKNOWN`.

`ParameterSet` JSON 은 `parameter_set_id` 만 필수이고 나머지 임계값은 스펙 초기값이 기본이다. 전체 목록은
`gt-diff --print-default-params` 또는 `samples/params.json`.

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
- `expected_state` 는 도구가 `behavior` 에서 생성한다. 파일에 적힌 값은 참고용이며 생성값과 다르면 경고만 낸다.
  카탈로그에 없는 behavior 는 `expected_state` 가 있어야 읽을 수 있다.
- 각 큐 뒤 3초(반응 허용)와 void 구간은 채점에서 뺀다.

behavior → 기대 상태(`BehaviorCatalog`, v0-plan 6장 표):

| behavior | 기대 상태 | 시나리오 |
|---|---|---|
| `study_in_zone` | PRESENT | T1 |
| `leave_seat`, `leave_seat_plain_background`, `leave_seat_jacket_on_chair` | 지속 ≥ `absent_confirm_ms`(3초)면 ABSENT, 아니면 INVALID(확정되지 않은 얼굴 미검출) | T2, T7c, T8 |
| `deep_bow_writing` | INVALID | T3 |
| `prone_head_visible` | PRONE | T4a |
| `prone_head_out_of_frame` | INVALID, `auto_pause_ms`(120초)부터 PAUSED | T4b |
| `phone_pickup_use` | PHONE | T5a |
| `phone_redock_recalibrating` | INVALID | T5a |
| `phone_use_on_flat_surface` | INVALID, `redock_pending_invalid_ms`(60초)부터 PHONE | T5b |
| `desk_bump` | INVALID | T5c |
| `look_away_unregistered` | 처음 `away_grace_ms`(4초) PRESENT, 이후 AWAY | T6a·b·c |
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
| `--params` | ParameterSet JSON. 없으면 스펙 기본값 |
| `--engine naive\|logged` | `naive`(기본): NaiveBaselineEngine 으로 재생. `logged`: 로그에 적힌 raw/final 을 그대로 채점(재생 없음) |
| `--out` | 리포트 JSON 파일. 없으면 stdout |
| `--quiet` | 콘솔 표 생략 |
| `--print-default-params` | 기본 ParameterSet 출력 |

종료 코드: 0 실행 완료(PASS 또는 INCONCLUSIVE), 1 사용법 오류, 2 입력 오류, 3 실행 완료이고 FAIL.

리포트(JSON, 콘솔 표 동일):

1. `seconds`: 레코드 수, 채점 초, 반응·void·미채점 제외 수, GT 밖 레코드, 채점 구간의 누락 레코드.
2. `confusion[expected][final]`(초), `per_state` 의 precision·recall·상태 비율 오차(측정 − 기대, %p).
3. `detection_latency`: 기대 상태가 바뀌는 interval 마다 큐 → 첫 `raw_state` 전이까지. 중앙값, P95(nearest-rank), 미검출 수, 표본.
4. `flapping`: 기대 상태가 일정한 구간 안에서 채점 초끼리 `final_state` 가 바뀐 횟수와 10분당 비율.
5. `false_invalid`, `false_away`: 기대가 그 상태가 아닌데 그 상태인 초의 비율. `invalid_ratio_by_expected` 는 T11 자료.
6. `reproducibility`: 같은 로그 2회 재생 일치 여부, 로그에 적힌 `final_state` 와의 일치율.
7. `baseline_comparison`: 대상 엔진과 NaiveBaselineEngine 의 false ABSENT·false INVALID·missed ABSENT 초.
8. `pass`: v0-plan 7장 합격선 항목별 PASS/FAIL/n/a 와 종합(`overall`: true/false/null). `scenario_id` 로 적용 항목을 고른다(`T4b` 처럼 변형 글자 허용).
   스키마로 직접 잴 수 없는 항목은 proxy 로 계산하고 note 에 적는다: T5b 재거치 확정 0회(PHONE·INVALID 밖 구간 수), T7c camera_occluded 0초(채점 INVALID 초).
9. `warnings`: parameter_set_id·session_id 불일치, GT 경고, 세션 종료 뒤 버린 레코드.

## 라이브러리 사용

```kotlin
val params = ParameterSet.DEFAULT
val log = JsonlCodec.decode(text)
val result = ReplayRunner(NaiveBaselineEngine(params), params).run(log)   // records, intervals, sessionEnd
val report = GtDiff(params).diff(GtParser.parse(gtText), result, baseline = result, loggedRecords = log.records,
    replayTwiceIdentical = result.sameOutcomeAs(ReplayRunner(NaiveBaselineEngine(params), params).run(log)))
println(ConsoleReport.render(report))
```

실시간 경로도 같은 클래스를 쓴다: 초당 레코드마다 `engine.judge(record)` → `finalizer.push(record, decision)`, 백그라운드 진입에 `onBackground`, 복귀에 `onForeground`.
