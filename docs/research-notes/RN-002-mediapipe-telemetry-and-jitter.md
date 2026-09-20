# RN-002 MediaPipe 원격 통계 로깅 차단과 지터 j 정의

- 날짜: 2026-09-20
- 작성자: CC 세션 C (PR #6), 정리·결정 김요섭
- 관련 GT: V0-B 실측 절차(`android/focus-engine/README.md`; T1~T3 자료 수집, R4~R6)
- 관련 커밋: PR #6 `claude/exciting-edison-ifrx72` — `3f90c19`(focus-core v0b_raw·FeatureAggregator·V0bReport), `af30436`(android/focus-engine 최초 구현, 모호점 보고), 그 뒤의 v0.2.2 반영 커밋(CHANGELOG·이 노트·코드·테스트)

## 가설

1. MediaPipe Tasks Vision 을 그대로 쓰면 앱에 INTERNET 권한 없이 온디바이스 추론만 하는 구성이 될 것이다(스펙 9장 "추론 입력을 외부로 보내는 경로는 없다", 지시문 C "INTERNET 권한 금지").
2. 스펙 6장의 지터 j("transformation matrix 의 역변환으로 랜드마크를 canonical face 공간으로 옮기고 … 프레임 간 변위 RMS")는 Java API 로 그대로 구현할 수 있을 것이다.
3. 스키마 0.2.1 의 초당 레코드는 캘리브레이션·게이트가 없는 V0-B 단계에서도 "비운 채로" 기록할 수 있을 것이다.

## 실험

`android/focus-engine`(PR #6)을 구현하면서 `com.google.mediapipe:tasks-vision:1.0.0` 과 그 의존성 `tasks-core` 의 POM·AAR 매니페스트·바이트코드(`javap`)를 확인했다. 0.10.14, 0.10.26, 0.10.35, 1.0.0 네 배포 버전의 `TasksStatsLoggerFactory` 를 비교했다. 빌드 뒤 병합 매니페스트의 권한과 dex 안의 `com.google.android.datatransport` 클래스를 검사했다.

j 는 `FaceLandmarkerResult` 가 주는 것(478 정규화 랜드마크, 52 blendshape, 4x4 transformation matrix)만으로 정의를 시도했다.

레코드는 `FeatureAggregator`(focus-core)가 V0-B 입력만으로 `SecondRecord` 를 만들면서 null 을 허용하지 않는 필드를 모았다.

## 관찰

1. `tasks-core` 는 `com.google.android.datatransport:transport-api/runtime/backend-cct` 에 의존한다. `transport-backend-cct` 의 매니페스트는 `INTERNET` 과 `ACCESS_NETWORK_STATE` 를 선언하고, `transport-runtime` 은 JobService·BroadcastReceiver 컴포넌트를 병합한다. `tasks-core` 안에서 이 라이브러리를 참조하는 클래스는 `RemoteLoggingClient` 하나뿐이지만, 네 배포 버전 모두 `TasksStatsLoggerFactory.create` 가 `TasksStatsProtoLogger` 를 반환하고 그 생성자가 무조건 `new RemoteLoggingClient(context)` 를 실행한다(`TransportRuntime.initialize` → `CCTDestination` 으로 `Transport` 생성 → 사용 통계 `MediaPipeLogExtension` 을 `send`). GitHub 소스의 `TasksStatsDummyLogger` 분기는 Maven 배포본에 없다. 따라서 의존성만 제외하면 landmarker 생성 시 `NoClassDefFoundError` 가 난다.
2. `RemoteLoggingClient` 가 링크하는 datatransport 심볼은 8개다: `runtime.TransportRuntime`(initialize, getInstance, newFactory), `runtime.Destination`, `cct.CCTDestination.INSTANCE`, `TransportFactory.getTransport`, `Transport.send`, `Event.ofData`, `Encoding.of`, `Transformer.apply`(람다). 같은 FQCN 의 no-op 스텁을 `engine/src/main/java` 에 두고 라이브러리 그룹을 제외하니 빌드가 통과하고, 병합 매니페스트의 권한은 `CAMERA, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CAMERA, POST_NOTIFICATIONS, WAKE_LOCK` 뿐이며 dex 에는 스텁 클래스만 남았다. 실기기 실행은 아직 없다.
3. `FaceLandmarkerResult` 는 metric 랜드마크(face geometry 의 mesh)를 노출하지 않는다. transformation matrix 의 역변환을 canonical 공간의 metric 점에 적용하는 스펙 문장은 Java API 로는 그대로 구현할 수 없다. 처음 구현(PR #6 최초 커밋)은 화면 좌표에 역회전만 적용했는데, 이는 거리 변화(스케일)를 빼지 못하고 정규화 기준(얼굴 폭)이 회전에 따라 변한다.
4. 회전을 `ImageProcessingOptions` 로 넘기면 랜드마크와 행렬은 회전 전 버퍼 기준으로 나온다(face geometry 그래프는 norm_rect 를 받지 않는다). 절대 각(yaw·pitch·roll)은 `Rz(−θ)` 보정이 필요하지만, 두 프레임 사이의 상대 회전각은 보정과 무관하다.
5. 스키마 0.2.1 에서 null 을 허용하지 않는 필드: `zone_status`(in_zone/outside/no_head_pose 중 어느 것도 "작업영역 없음" 을 뜻하지 않음), `imu_state`, `power_state`, `scene_luma`, `face_detect_ratio`, header 의 `calibration_id`·`calibration_snapshot_version`. 최초 구현은 `zone_status = no_head_pose`, calibration 문자열 `""`, `scene_luma` 가 한 번도 없으면 `0.0` 을 썼다.

## 결정

CHANGELOG v0.2.2 (2026-09-20) 로 기록. `spec_version` 은 "0.2.0" 유지, `feature_schema_version` 은 "0.2.2"(추가만, 0.2.1 로그도 읽는다).

1. 자리표시 값. `ZoneStatus` 에 `uncalibrated` 를 추가하고 캘리브레이션 전과 진행 중인 초에 쓴다(V0-B 레코드는 전부). `imu_state = UNKNOWN`, `power_state = P0`, header `calibration_id`·`calibration_snapshot_version` = `"none"`. `scene_luma` 는 항상 측정값이고 그 초에 표본이 없을 때만 직전 값을 유지한다. 상수 자리표시는 금지: 세션은 첫 처리 프레임(항상 scene 표본을 가진다)에서 시작하고, 그 전에 버킷을 닫으려 하면 `FeatureAggregator` 가 예외를 낸다. 남은 자리표시(`face_detect_ratio` 는 처리 프레임 0이면 0.0, `frames_requested` 는 capture result 가 전혀 없으면 처리 수, `participant_id` "dev", `algorithm_version` 은 git 이 없으면 "unknown")는 PR #6 본문에 목록으로 둔다.
2. MediaPipe 원격 통계 로깅. 현재 방식(전송 라이브러리 제외 + no-op 스텁 + 병합 매니페스트에 INTERNET·ACCESS_NETWORK_STATE 가 없음을 CI 에서 검사)을 잠정 승인한다. 스텁은 `engine/src/main/java/com/google/android/datatransport/` 한 곳에 모으고(패키지 이름은 MediaPipe 바이트코드가 정하므로 3개 패키지·8개 파일), 대상 MediaPipe 버전(1.0.0, `strictly` 로 고정)·스텁 심볼 목록·확인 방법을 README 에 적는다. devapp 에 엔진 자가 점검을 둔다: 서비스 시작 시 Face·Pose landmarker 생성과 더미 1프레임 추론을 try/catch 로 감싸고, 실패하면 예외 클래스와 메시지를 상태와 요약에 표시하며, 요약 첫 줄에 "자가 점검: OK" 또는 실패 내용을 넣는다. 실기기에서 스텁 관련 크래시가 확인되면 대안(실제 라이브러리 유지 + 매니페스트에서 INTERNET 과 그 라이브러리의 컴포넌트 제거)으로 바꾼다.
3. 지터 j. 강체 부분집합(콧등, 눈꼬리·눈머리, 이마 14점)의 3차원 점을 연속 두 프레임 사이에 similarity 정합(스케일·회전·평행이동, Horn 폐형식)한 뒤 잔차 RMS 를 현재 프레임의 양안 거리로 정규화한다. 머리 각속도가 30°/s 를 넘는 프레임 쌍은 제외한다. 단위 테스트: 순수 강체 이동·회전·스케일에서 j ≈ 0, 랜드마크별 독립 잡음에서 j > 0, 빠른 회전 쌍 제외. 정확한 정의는 `android/focus-engine/README.md` 에 둔다.
4. `v0b_raw` 줄 타입은 캘리브레이션 전 단계 전용으로 스키마에 넣는다(CHANGELOG (a)).

## 결정한 사람

김요섭
