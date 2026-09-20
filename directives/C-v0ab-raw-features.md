[작업] V0-A/B: Android 측정 엔진 골격 + Face/Pose raw feature 기록.
게이트 판정은 넣지 않는다.
[전제 확인] main 에 core/focus-core(스키마 0.2.1)가 있어야 한다. 없으면
중단하고 보고한다.
[첫 단계] 이 지시문 원문을 directives/C-v0ab-raw-features.md 로 저장한다.
[수정 범위] android/focus-engine/**, directives/C-v0ab-raw-features.md,
.github/workflows/focus-engine-android.yml, core/focus-core/** (추가만.
기존 의미를 바꾸지 않는다. Android 에서 쓸 수 있게 JVM 17 바이트코드로
맞추는 빌드 설정 수정은 허용).
[정본] docs/focus/spec-v0.2.0.md 1·6·7·9장, docs/focus/v0-plan-and-gt.md
2~4장, CHANGELOG.md v0.2.1, core/focus-core/README.md. 문서의 `\_` 는 `_`.
충돌이나 모호점은 구현으로 메우지 말고 보고한다.
[참고 구현] android/spike-r1 의 foreground service, Camera2Interop fps
고정, 요약 복원·클립보드 복사, Release 게시 워크플로를 재사용한다.
R1 실측 결과: SM-S948N / Android 16 에서 화면 off·Doze 중 24fps 유지,
timestamp source REALTIME.

구성: android/focus-engine (Gradle 루트)
- :engine (Android 라이브러리). core/focus-core 를 composite build 로 참조.
  Timebase, CameraPipeline, FacePipeline, PosePipeline, SceneQuality,
  MotionPipeline(IMU 5Hz), FeatureLogger, CaptureService(camera 타입 FGS,
  Activity 가 보일 때 시작, 서비스 lifecycle 에 CameraX 바인딩, wakelock).
- :devapp (개발용 앱). 시작/정지, 구간 마커 버튼(정면, 정지, 숙임,
  엎드림, 자리비움, 빈의자), 상태, 요약, 요약 복사. edge-to-edge 인셋 처리.
- 초당 집계(프레임·센서 스칼라 → SecondRecord)는 순수 로직으로
  core/focus-core 에 둔다(FeatureAggregator + 단위 테스트).

카메라·추론
- 전면 1280x720, [24,24] 없으면 [30,30], KEEP_ONLY_LATEST, Preview 없음.
  v0 는 ImageAnalysis 의 RGBA_8888 출력으로 시작한다(YUV + ROI 최적화는
  나중). 회전은 비트맵 복사가 아니라 ImageProcessingOptions 로 넘긴다.
- MediaPipe Tasks Vision, CPU delegate. Face Landmarker: num_faces=1,
  blendshape 와 transformation matrix 출력 on, VIDEO 모드로 프레임의
  capture timestamp(ms)를 넘긴다. 전 프레임 처리.
  Pose Landmarker lite: 1fps, 얼굴 미검출 중에는 3fps.
- 모델 파일(.task)은 앱 assets 에 포함하고 출처 URL·버전·sha256 을
  README 에 적는다. 런타임 다운로드 금지. INTERNET 권한 금지.
- 분석 스레드는 하나. 프레임당 추론 시간을 잰다. 요청·처리·드롭 프레임을
  구분해 센다.

Face 에서 뽑는 스칼라: 검출 여부, yaw·pitch·roll(transformation matrix,
부호 규약을 README 에 명시), 얼굴 폭 px, 강체 잔차 지터 j(스펙 6장 정의).
Pose 에서 뽑는 스칼라: shoulder_visibility_min, 어깨 중심 x·y 와 어깨 폭
(정규화), 머리 landmark 유무, head_offset_below_shoulder_ratio,
어깨 중심의 1초 간격 변위 ÷ 어깨 폭.
SceneQuality(1~2Hz, 64x48 서브샘플): 전체 휘도 평균, 4x4 tile texture 의
최솟값·중앙값.

데이터 경계 (위반 금지)
- 프레임, 비트맵, 랜드마크 배열은 Camera/Face/Pose/Scene 모듈 밖으로
  나가지 않는다. 모듈 출력은 스칼라뿐이다. 파일·로그·크래시 경로에
  쓰지 않는다. 이번 단계에서는 Pose 5점 좌표 기록(DEBUG 예외)도 쓰지
  않는다. 구간별 비율 통계로 대신한다.
- 로그 DTO 는 focus-core 모델만 쓴다. 이를 검사하는 테스트를 둔다.

기록 (앱 전용 저장소, 확정 레코드만 30초마다 flush)
- JSONL: header(spec_version 0.2.0, feature_schema_version 0.2.1,
  algorithm_version = git sha, parameter_set_id 기본값, 기기·OS·해상도·
  nominal_fps, task_mode=visual, t_start_*), timebase 줄, 초당 SecondRecord,
  session_end. 캘리브레이션이 아직 없으므로 그에 의존하는 필드
  (torso_center_offset_ratio, torso_width_ratio, zone_status, zone_id 등)는
  비운다. 게이트가 없으므로 상태 필드도 비운다. 스키마가 이를 허용하지
  않으면 구현하지 말고 보고한다.
- 캘리브레이션 전 단계용 원시 스칼라(어깨 중심·폭, tile texture 통계,
  추론 시간)는 별도 줄 타입 v0b_raw(스칼라만)로 남긴다.

요약(화면 + 파일 + 클립보드, 비정상 종료 시 CSV/JSONL 로 복원)
- 전체: 세션 길이, 요청·처리·드롭 프레임, 처리 fps, 80ms 초과 갭, 누락된
  초, Face 추론 ms(평균, p95, 최대), Pose 추론 ms, 최고 thermal status,
  배터리 % 시작→끝, 평균 전류(µA)와 추정 평균 전력(mW = 전류 × 전압),
  화면 off 행, idle 행.
- 구간(마커)별: 길이, face_detect_ratio, yaw·pitch·roll 평균과 표준편차,
  얼굴 폭 중앙값, j 중앙값·p95, shoulder_visibility_min 이 0.6 이상인 초의
  비율, 머리 landmark 가 있는 초의 비율, head_offset_below_shoulder_ratio
  중앙값, 휘도 평균.

CI: .github/workflows/focus-engine-android.yml
- PR: 단위 테스트 + assembleDebug. main push 와 workflow_dispatch:
  prerelease `focus-engine-dev-latest` 에 APK 게시(spike-r1 과 같은 방식).

README 에 실측 절차를 적는다 (사람이 수행, 화면 off)
 1) 정면 착석 10분. 그중 2분은 "정지" 마커로 가만히 있는다.
    기준: face_detect_ratio ≥ 99%, 정지 구간 yaw·pitch 표준편차 < 2°.
 2) 평소처럼 공부 30분. 기준: 처리 fps ≥ 23.5, 드롭 < 1%,
    thermal ≤ LIGHT. 평균 전력을 기록한다.
 3) 낮은 거치에서 마커 순서대로: 정면 1분 → 숙임(필기) 1분 → 엎드림 1분
    → 자리비움 30초 → 빈의자(옷 걸기) 30초. 눈높이 거치에서 반복.

완료 조건: 단위 테스트, assembleDebug, 데이터 경계 테스트 통과.
끝나면 PR 을 만들고 본문에 적는다: 빌드 결과, 모델 파일 출처와 해시,
스키마·스펙과 어긋나거나 모호했던 점, focus-core 에 추가한 것.
