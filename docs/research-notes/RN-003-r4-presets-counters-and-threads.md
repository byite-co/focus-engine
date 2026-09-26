# RN-003 R4 성능 실험: 프리셋 비교 계측, 계수 정의, 스레드 모델

- 날짜: 2026-09-20
- 작성자: CC 세션 D (`claude/hopeful-hamilton-hw6wu1`), 정리·결정 김요섭 (발견: 외부 리뷰)
- 관련 GT: V0-A 통과 기준 재측정(처리 fps·드롭·갭; `docs/focus/v0-plan-and-gt.md` 2장), R4 예산 확인, T1~T3 자료 수집
- 관련 지시문·기록: `directives/D-r4-perf-experiments.md`(정정 1~5 통합본), CHANGELOG v0.2.3 (a)(b), `android/focus-engine/README.md`, `core/focus-core/README.md`; 실측 추가분과 후속 설계는 `directives/E-camera-fps-and-skip-fix.md`, CHANGELOG v0.2.4 (a)~(d)

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

## 실측 (지시문 E 도착 시점, SM-F966N, 화면 off 행)

지시문 E 의 실측 근거(A·B·C·D·E·G, 같은 기기·같은 자리, 요약의 화면 off 행 기준). 세션 요약 전문은 `gt/sessions/` 의 로그와 함께 두고 git 에는 올리지 않는다.

| 프리셋 | 평균 전류 (mA) | Face ms 평균/p95 | 드롭 | 처리 fps | 비고 |
|---|---|---|---|---|---|
| A | 431 | 38.3 / 41.8 | 2.0% | – | 기준 |
| B | 418 | – | – | – | blendshape off: 전류 차이 없음 |
| C | 428 | – | – | – | 640x360: 전류 차이 없음 |
| D | 418 | – | – | – | GPU delegate: 전류 차이 없음 |
| E | 359 | 56.6 / 60.1 | – | 12.00 | Face 절반: 전류 16% 감소, Face 추론 시간은 길어짐 |
| G | 496 | – | – | – | PerformanceHint: 전류 15% 증가 |

"–" 는 지시문에 값이 없는 칸이다(지시문은 전류와 A·E 의 Face ms, A 의 드롭, E 의 fps 만 준다).

확정 사실(실측):

1. 해상도(C)·blendshape(B)·GPU delegate(D)는 전류를 바꾸지 못했다. Face 처리 빈도를 절반으로 줄인 E 만 16% 줄었다. 스펙 7장의 부품별 전력 모델은 추론 비용을 과소평가했고 총 전류는 추론 변형에 둔감하다(CHANGELOG v0.2.4 (c)).
2. E 에서 Face 추론 시간이 A 보다 길다(38 → 57ms): 격프레임은 "같은 추론을 절반만" 하는 것이 아니다.
3. 화면 off 는 같은 세션 안 on/off 교대에서 Face 14~22%, Pose 25~40% 느리게 한다. 화면 상태와 추론 시간의 인과는 on/off 행 비교로 확인됐다(정정 1 의 미확정 항목 해소).
4. G 는 A 보다 전류가 15% 많다.

해석·가설(실측이 아닌 판단):

- E 의 Face 시간 증가는 프레임 사이 유휴가 길어지면 코어 클럭이 내려가기 때문일 수 있다. H 세션(카메라 cadence 자체를 낮춤)에서 같은 경향이 나오는지 본다.
- G 는 전력이 늘어 R4 예산에서 불리해 보인다.
- 남은 질문: Face 처리 빈도를 같게 두고 카메라 cadence 만 낮추면 전류가 줄어드는가. 이를 위해 지시문 E 가 H12(카메라 [12,12]) ↔ E, H15([15,15]) ↔ E15(24fps, Face 15Hz 슬롯) 짝을 정의했다. 짝 비교는 Face 빈도가 같은 짝만 카메라 효과로 해석한다.

지시문 E 구현(PR #8): 처리 슬롯 계수 3종(독립 terminal counter)과 `expected = filled + missed` 종료 검사, 카메라 timestamp 영역 계약(raw = identity·소속·슬롯, mono = 위치, fence 시 offset freeze, 양끝 버킷 clamp), 갭 임계 공식(1.5배·4.5배), H12/H15/Hvar/E15, 정지 순서(위 결정 3 의 7단계 → 8단계: fence 를 producer 정지 전에 전달하고 CaptureResult 콜백 drain 뒤 슬롯 scheduler CLOSE gate 추가), 힌지 초기값. 리뷰 반영(지시문 E2, 같은 PR): Hvar 는 상한 15 가변 range 중 가장 좁은 것; `[24,24]`·`[30,30]` 이 없는 카메라(fps unset)는 30fps 를 가정하지 않고 워밍업 60초 CaptureResult 간격 중앙값을 진단용 기대 간격으로 쓰며 판정하지 않는다; 종료 clamp 의 마지막 버킷은 `(fence − start − 1) ÷ period`; `stop_integrity_failed` 는 after_close > 0 OR CaptureResult drain 미완료 OR aggregation 큐 drain 미완료(원인별 보존); 슬롯 모드의 순서 역전 CaptureResult > 0 은 비교 불가; 요약 첫 줄은 `비교 가능` / `비교 불가: <사유들>` / `짝 비교 판정 비적용: Hvar 가변 cadence`. 실기기 H 세션 결과는 이 표 아래에 붙인다.

## 결정한 사람

김요섭
