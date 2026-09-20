# RN-003 R4 성능 실험: 프리셋 비교 계측, 계수 정의, 스레드 모델

- 날짜: 2026-09-20
- 작성자: CC 세션 D (`claude/hopeful-hamilton-hw6wu1`), 정리·결정 김요섭 (발견: 외부 리뷰)
- 관련 GT: V0-A 통과 기준 재측정(처리 fps·드롭·갭; `docs/focus/v0-plan-and-gt.md` 2장), R4 예산 확인, T1~T3 자료 수집
- 관련 지시문·기록: `directives/D-r4-perf-experiments.md`(정정 1~5 통합본), CHANGELOG v0.2.3 (a)(b), `android/focus-engine/README.md`, `core/focus-core/README.md`

## 가설

1. V0-B 엔진(PR #6)의 처리 fps 미달과 드롭은 단일 분석 스레드에서 Face 뒤에 Pose·Scene·집계·직렬화가 직렬로 붙어 생기는 backpressure 가 주원인일 것이다.
2. 화면 off 세션에서 Face 추론 시간이 길어지는 것은 화면 상태(전력 정책) 때문일 수 있으나, 같은 세션 안에서 on/off 를 분리해 재야 인과를 말할 수 있다.
3. 해상도(C)·blendshape(B)·delegate(D)·격프레임(E)·PerformanceHint(G) 중 어느 것이 화면 off 에서 처리 fps ≥ 23.5, 드롭 < 1%, 갭 초과 < 1% 를 만족시키는지는 실측으로만 정할 수 있다.

## 실험 (설계와 계측 수단; 실측은 이 노트 뒤에 붙인다)

실측 근거(정정 1, SM-F966N, PR #6 엔진): 화면 on 위주 세션 Face 30~32ms, Pose 38~44ms, 드롭 1.7~2.5%. 화면 off 위주 세션(232/258초) Face 36.7ms(p95 41.1), Pose 51.6ms, 드롭 5.6%, 처리 22.66fps, 세션 전체 평균 전력 1.73W. 화면 상태와 추론 시간의 인과는 미확정.

이번 세션은 실기기 없이 계측 수단을 만들었다.

1. 프리셋 A~E, G 를 devapp 에서 고르고 세션마다 하나를 돈다. F(ROI 크롭)는 구현하지 않았다.
2. 계수를 스키마 0.2.3 으로 다시 정의했다(CHANGELOG v0.2.3 (a)). 핵심은 "어디서 프레임이 사라지는가"를 백프레셔(카메라→분석기), 미처리(예상 밖; Face 추론 오류·Face 이전 오류), Face 이후 실패, 늦은 표본 폐기로 나눈 것과, 세션 종료 시 보존식을 검사해 계수 자체의 신뢰성을 요약 첫머리에 표시하는 것이다.
3. 스레드를 분리했다: `focus-analysis`(Face·Scene·계수·Pose 프레임 deep copy), `focus-pose`(Pose 워커, 대기 큐 깊이 1), `focus-aggregate`(집계기 단일 소유), `focus-io`(JSONL 직렬화·파일), `focus-status`(binder 호출·IMU). 분석 스레드에는 추론과 스칼라 추출, ~3.7MB memcpy(Pose 프레임 복사, 계측됨)만 남는다.
4. 단계별 계측: wrap, face_infer, face_post, scene, pose_frame_copy(평균·p95·최대), frame_total(Face 사이클 = analyzer 콜백 1회), pose_wait, pose_infer.
5. 요약: 화면 on/off 행(워밍업 60초·전환 5초 제외; 행마다 단계별 평균·p95·최대와 임계 초과 갭의 상위 원인), 프리셋 합격(off 행 기준; fps·갭·드롭·200ms 초과 갭 0; 옆에 off 평균 전력·최고 thermal), 화면 상태 구간표, 추이표(10초, 최대 60행), 카메라 id·렌즈 방향·접힘 상태.
6. 원래 지시문 원문은 뒤늦게 도착했다(보완 메시지). 정정에 없던 항목(갭 원인 집계, 긴 갭 합격선, 단계별 p95·최대·큐 적재, 추이표의 자세 평균·face 비율·60행 상한, 카메라 id·렌즈 방향·접힘 상태)을 추가했고, 원문의 C 640x480 은 정정 1 의 640x360 이 우선한다.

## 관찰

- 실기기 계측 전이라 관찰은 구현 중에 드러난 사실로 한정한다.
1. PR #6 의 `frames_dropped = requested − processed` 는 KEEP_ONLY_LATEST 가 버린 프레임과 분석기가 받고도 처리하지 못한 프레임을 구분하지 못했다. 새 정의로는 `frames_analyzer_received` 가 그 경계다.
2. Pose 결과가 캡처 시각의 버킷에 귀속되려면 워커 지연이 버킷 닫힘 지연(300ms) 안에 들어야 한다. Pose 51.6ms + 복사 + 대기이면 충분하지만, 워커가 밀리면 `pose_late_dropped` 가 그 사실을 남긴다.
3. MediaPipe GPU delegate(D)는 landmarker 를 만든 스레드에 EGL 컨텍스트가 묶인다. 생성·추론·해제를 모두 `focus-analysis` 에서 하도록 정지 순서에서 landmarker 해제를 분석 스레드로 보냈다.
4. PerformanceHintManager 세션(G)은 API 31+ 이고 `createHintSession` 이 null 을 줄 수 있다. 자가 점검 줄과 header `perf_hint_target_ms`(세션이 없으면 null)로 실제 적용 여부를 남긴다.
5. enum 상수 초기화 순서: Kotlin enum 항목 초기화 식에서 같은 enum 의 companion 상수를 읽을 수 없다(`UNINITIALIZED_ENUM_COMPANION`). 프리셋 G 의 목표 35ms 는 파일 상단 상수로 뺐다.

## 결정

CHANGELOG v0.2.3 (a)(b). `spec_version` "0.2.0" 유지, `feature_schema_version` "0.2.3"(추가만).

1. 계수 정의·보존식·세션 timestamp 창·요약 첫머리의 "계수 불일치"/"계수 검증 생략(비정상 종료)" 표시.
2. 프리셋 A~E, G 와 합격 규칙(off 행 기준, 전력·thermal 병기). F 는 보류: 정정 2 의 절차(A 와 같은 자세에서 yaw·pitch 편향 측정, 허용 2°)는 실기기 반복 측정이 필요하고, 크롭 창 이동에 따른 head pose 편향은 좌표 변환으로 복원할 수 없어(주점 이동) 실측 없이는 검증할 수 없다. 나머지 범위가 이미 크므로 건너뛰고 사유를 보고한다.
3. 스레드 모델(집계기 단일 소유, 모든 파이프라인은 큐에 post)과 정상 종료 순서 7단계(`StopSequence`).
4. 다음 실측에서 기록할 것: 프리셋별 요약 전문(합격 줄, on/off 행, 구간표), events.log 의 `stop_sequence`·`counter_mismatch` 줄, `resolution_choice`·`camera_bound` 줄(실제 해상도), 자가 점검 줄(G 의 hint 세션 생성 여부, D 의 GPU 생성 여부).

## 결정한 사람

김요섭
