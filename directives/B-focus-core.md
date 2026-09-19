[작업] focus-core: 기록 스키마, StateFinalizer 골격, 로그 재생, GT 대조 도구
[첫 단계] 이 지시문 원문을 directives/B-focus-core.md 로 저장한다.
[수정 범위] core/focus-core/**, directives/B-focus-core.md,
.github/workflows/focus-core.yml 만. 나머지는 읽기 전용.
[정본] docs/focus/spec-v0.2.0.md 의 3장(판정 순서, 상태), 6장(초당 기록
스키마), 9장(시간 기준, 소급, lifecycle gap, 데이터 경계)과
docs/focus/v0-plan-and-gt.md 의 5~7장(GT 규칙, 시나리오, 합격선).
문서의 `\_` 는 내보내기 이스케이프이고 `_` 와 같다.
지시문과 충돌하면 문서가 우선이고, 구현 전에 보고한다.

원칙
- Kotlin JVM 라이브러리. Android API 의존 금지. main 소스에서는 java.*
  사용을 피해 Kotlin Multiplatform 으로 옮길 수 있게 둔다.
  JSON 은 kotlinx-serialization, 테스트는 kotlin.test.
- 데이터 경계: 어떤 모델에도 이미지, 랜드마크, 배열형 원시 데이터 필드를
  두지 않는다. 직렬화 필드가 스칼라, enum, 문자열, 사건 목록뿐임을
  검사하는 테스트를 둔다.
- 모든 시간은 t_mono_ms 기준. t_utc_ms 는 표시용으로만 보관.
- 결정적이어야 한다. 같은 입력이면 출력이 완전히 같다.

구현 범위 (게이트 규칙 자체는 이번 범위가 아니다)
1. model
   - State: PHONE, INVALID, ABSENT, PRONE, AWAY, PRESENT, PAUSED
   - SessionHeader: session_id, participant_id, spec_version("0.2.0"),
     algorithm_version, feature_schema_version, parameter_set_id,
     device_model, os_version, camera_resolution, nominal_fps,
     calibration_id, calibration_snapshot_version, task_mode
   - SecondRecord(v0 부분집합): t_mono_ms, t_utc_ms, raw_state,
     final_state, face_detect_ratio, torso_match, head_landmark_present,
     head_below_shoulder, yaw_mean, pitch_mean, roll_mean, zone_id(또는 밖),
     pose_motion, scene_luma, bg_tile_texture_ratio, jitter_j,
     face_width_px, imu_state, app_state, fps_actual, power_state
   - IntervalRecord(lifecycle gap): 시작·종료 t_mono_ms, state(PHONE 또는
     PAUSED), 사유
   - ParameterSet: 임계값 전체를 담는 불변 객체 + parameter_set_id
2. engine 인터페이스
   - GateEngine: (SecondRecord, 내부 상태) -> raw_state. 이번에는
     인터페이스와 비교용 NaiveBaselineEngine(얼굴 미검출 3초 -> ABSENT,
     그 외 PRESENT)만 구현한다.
3. StateFinalizer
   - raw_state / final_state 분리, 30초 지연 뒤 레코드 확정.
   - 소급: ABSENT, PRONE, PHONE(집어 듦)은 후보 시작 시각까지.
     AWAY, PAUSED 는 소급하지 않는다. 소급 범위는 최대 30초.
   - 후보 시작 시각은 엔진이 확정 시점에 함께 넘긴다.
   - flushNow(): 백그라운드 진입 시 미확정 레코드를 즉시 확정.
   - lifecycle gap: 진입·복귀 시각으로 IntervalRecord 생성. 30초 제한을
     적용하지 않는다. 10분 초과면 진입 시각에 세션 종료.
4. replay: JSONL(첫 줄 header, 이후 레코드)을 읽어 엔진과 finalizer 를
   돌리고 final_state 를 다시 계산한다.
5. gt
   - GT JSON 파서(v0-plan-and-gt.md 5장 형식: intervals 의 behavior,
     void 포함).
   - expected_state 생성기: 이탈 행동의 처음 4초는 PRESENT, 머리가 안
     보이는 저움직임은 120초부터 PAUSED, 각 큐 뒤 3초는 채점 제외.
     behavior -> 기대 상태 매핑은 6장 표를 따른다.
   - 초 단위 diff 와 리포트: 혼동 행렬, 상태별 precision/recall, 상태
     비율 오차, raw_state 기준 검출 지연(중앙값, P95), flapping, 거짓
     INVALID, 7장 합격선에 대한 합격/불합격.
   - baseline 비교: false ABSENT 초, false INVALID 초, missed ABSENT 초.
6. CLI: gt-diff --log session.jsonl --gt gt.json --params params.json
   -> 리포트 JSON + 콘솔 표.
7. .github/workflows/focus-core.yml: core/focus-core 변경 시
   ./gradlew :focus-core:test 실행.

테스트: 합성 로그로 ABSENT 소급, 30초 확정 지연, flushNow, lifecycle gap
(2분, 11분), 재생 결정성, expected_state 생성(유예 4초, 반응 3초),
diff 지표 계산.

완료 조건: 테스트 통과, README 에 JSONL·GT 형식과 CLI 사용법.
끝나면 PR 을 만들고 본문에 적는다: 스펙에서 모호하거나 서로 충돌하는
부분(구현으로 메우지 말고 보고), 스키마에서 빠졌다고 판단한 필드.
