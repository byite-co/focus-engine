[작업] D / R4 성능 실험: 프리셋 비교 계측, 비동기 Pose, 스레드 모델, 계수 정의, 요약.
이 파일은 원래 지시문과 정정 1~5(+명확화)를 합친 최종본이다. 세션
claude/hopeful-hamilton-hw6wu1 에는 원래 지시문의 원문이 도착하지 않아 4항의
항목(Pose 스레드 분리, 분석 스레드에서 IO 제거, 단계별 계측, 10초 추이 표)은
정정문이 인용한 이름과 구현으로 남긴다. 원문이 확보되면 이 절을 원문으로 바꾼다.

[수정 범위] android/focus-engine/**, core/focus-core/**(추가만, 기존 의미
유지), CHANGELOG.md(정정 1), directives/D-r4-perf-experiments.md,
docs/research-notes/RN-003(실측·결정 기록).
[정본] docs/focus/spec-v0.2.0.md 1·6·7·9장, docs/focus/v0-plan-and-gt.md 2~4장,
CHANGELOG.md v0.2.1·v0.2.2, core/focus-core/README.md,
android/focus-engine/README.md. 충돌·모호점은 구현으로 메우지 말고 보고한다.

[실측 근거] SM-F966N: 화면 on 위주 세션 Face 30~32ms, Pose 38~44ms, 드롭
1.7~2.5%. 화면 off 위주 세션(232/258초) Face 36.7ms(p95 41.1), Pose 51.6ms,
드롭 5.6%, 처리 22.66fps, 세션 전체 평균 전력 1.73W. 화면 상태와 추론 시간의
인과는 미확정이다. 같은 세션 안의 on/off 분리 계측으로 확인한다.

1. 프레임 계수(스키마 0.2.3; feature_schema_version 0.2.3, 추가만)
   - frames_requested: 카메라가 만든 프레임(CaptureResult 기준)
   - frames_analyzer_received: ImageAnalysis 콜백으로 받은 프레임
   - frames_skipped_intentional: 받았지만 의도적으로 건너뛴 프레임(격프레임,
     전력 상태에 의한 건너뜀)
   - frames_processed: Face 추론이 실제로 성공한 프레임. 추론이 성공한 직후에
     확정한다(listener.onFrame 도달 시점이 아니다).
   - face_inference_errors 는 따로 센다. 나머지 Face 이전 오류는 기타
     파이프라인 오류(pre_face_errors)다.
   - frames_sample_enqueued: Face 결과를 담은 FrameSample 이 aggregation 큐에
     정상 적재된 프레임. frames_sample_applied: 집계기의 버킷에 실제로 반영된
     표본. frames_sample_late_dropped: 닫힌 버킷에 도착해 버린 표본.
     aggregation 큐가 bounded 라서 적재를 거부할 수 있으면
     frames_sample_queue_dropped 를 따로 센다(현 구현의 Handler 큐는 unbounded).
   - 파생값: 백프레셔 드롭 = requested − analyzer_received,
     frames_unprocessed_unexpected = analyzer_received − skipped_intentional −
     processed("Face 실패"라는 이름은 쓰지 않는다),
     frames_post_face_failed = processed − sample_enqueued.
     frames_dropped 는 이들의 합으로 정의하고 요약에는 각각 따로 표시한다.
   - 드롭 합격선의 "드롭 < 1%" = (백프레셔 드롭 + frames_unprocessed_unexpected
     + frames_post_face_failed + sample_late_dropped + queue_dropped) ÷
     (requested − skipped_intentional).
   - 갭 통계는 "처리 대상 프레임" 기준 간격으로 계산한다. E 의 기대 간격은
     83.3ms 이므로 갭 임계는 기대 간격의 2배(167ms) 초과다. A~D, G 는 기존대로
     80ms 초과.
   - 보존 관계(PR 본문의 계수 표에 함께 적는다):
     requested = 백프레셔 드롭 + analyzer_received;
     analyzer_received = skipped_intentional + face_inference_errors +
     processed + (기타 Face 이전 오류); processed = sample_enqueued +
     post_face_failed; sample_enqueued = sample_applied + sample_late_dropped;
     pose_requested = pose_superseded + pose_completed + pose_errors +
     pose_cancelled_at_stop; pose_completed = pose_applied + pose_late_dropped.
   - 모든 프레임 보존식은 같은 세션 timestamp 창 안의 입력만 대상으로 한다.
     창은 콜백 도착 시각이 아니라 capture timestamp 로 정한다:
     sessionStartCaptureTs <= captureTs < stopFenceCaptureTs. 종료 시 먼저
     accept fence 를 세운다. 시작 전에 임시 보관한 requested 와 종료 뒤 도착한
     capture result 는 계수에서 뺀다.
   - 세션 종료 시 위 등식을 검사한다. 깨진 항목이 있으면 요약 첫머리에
     "계수 불일치: <항목>" 경고를 표시한다. 같은 검사를 단위 테스트로도 둔다.
     비정상 종료(프로세스 kill)로 복원한 요약에서는 계수 검증을 하지 않고
     "계수 검증 생략(비정상 종료)"라고 표시한다.
   - CHANGELOG v0.2.3 에 기록한다. 발견·결정자 칸: "발견: 외부 리뷰 /
     결정: 김요섭".
2. 프리셋
   - A: 1280x720(16:9), CPU, blendshape on, Face 24fps
   - B: A 에서 blendshape off
   - C: 640x360(16:9), 나머지는 A 와 같다. 기기가 640x360 을 주지 않으면
     가장 가까운 16:9 해상도를 쓰고, 그것도 없으면 640x480 을 C2 로 표시한다.
   - D: A 에서 Face 를 GPU delegate 로
   - E: A 에서 Face 12fps(격프레임)
   - G: A 에서 Face 분석 스레드에만 PerformanceHintManager 세션을 적용한다.
     목표 35ms, 매 Face 사이클의 실제 처리 시간을 보고한다. Pose 스레드는 이
     세션에 넣지 않는다. 스레드 우선순위 변경은 G 에 포함하지 않는다.
   - F(ROI 크롭)는 마지막에 하고 선택 사항이다. 구현한다면 2D 랜드마크, 얼굴
     폭, 위치 기반 스칼라는 crop 의 위치·크기·배율로 전체 프레임 좌표로
     역변환한다. head pose 는 좌표 변환으로 동일성을 가정하지 않는다. 같은
     자세에서 전체 프레임 결과(A)와 비교해 yaw·pitch 편향을 측정하는 절차를
     README 에 둔다. 허용 오차(초기값 2°)를 넘으면 F 에서는 transformation
     matrix 의 yaw·pitch 를 G2 입력으로 쓰지 않는다고 PR 본문에 명시한다.
     과하면 건너뛰고 사유를 보고한다.
   - 모든 프리셋의 요약에 요청 해상도가 아니라 CameraX 가 실제로 정한
     해상도와 종횡비를 표시한다.
3. 스레드 모델(필수)과 비동기 Pose
   - FeatureAggregator 는 단일 스레드 소유를 유지한다. 집계기를 소유한
     aggregation 스레드(큐) 하나만 집계기에 접근한다.
   - Face·Scene(분석 스레드), Pose 워커, IMU 는 결과를 스칼라 표본으로 바꿔 그
     큐에 post 할 뿐이다. 어느 것도 집계기를 직접 호출하지 않는다.
   - 파일 IO 와 JSONL 직렬화도 분석 스레드 밖에서 한다.
   - Pose 워커의 대기 큐 깊이는 1 이다. 실행 중에 새 요청이 오면 대기 중인
     오래된 프레임을 버리고 최신 프레임으로 교체한다(pose_superseded).
   - 넘기는 프레임은 deep copy 이고 capture timestamp 를 유지한다. 처리 완료
     즉시 폐기하며 워커 밖으로 노출하지 않는다.
   - Pose 결과는 capture timestamp 가 속한 버킷에 귀속한다. 버킷 닫힘 지연은
     기존 값(DEFAULT_CLOSE_DELAY_MS = 300ms)을 유지한다. 유예를 넘겨 도착한
     결과는 버리고 그 횟수를 센다(pose_late_dropped, 요약에 표시).
   - Pose 계수: pose_requested, pose_completed(추론이 끝난 요청),
     pose_applied(결과가 해당 버킷에 실제로 반영된 요청), pose_superseded,
     pose_late_dropped, pose_errors(추론 중 예외). 요약에 표시한다.
   - 단계별 계측에 pose_frame_copy_ms(평균, p95, 최대)를 독립 항목으로 둔다.
4. 정상 종료 순서(계약)
   1) Camera 와 IMU 에서 새 입력 수신을 멈추고 accept fence 를 세운다. 이미
      분석 스레드에서 실행 중인 Face·Scene 작업이 끝나거나 명시적으로 취소
      계수에 들어간 것을 확인한다.
   2) Pose 대기 슬롯은 더 받지 않는다.
   3) 실행 중인 Pose 는 짧은 상한 시간(초기값 500ms) 안에 끝내거나
      pose_cancelled_at_stop 으로 센다.
   4) Face·Scene·Pose·IMU 가 post 한 aggregation 큐에 barrier 를 넣고 전부
      drain 한다.
   5) 그 뒤에 FeatureAggregator.finish().
   6) 계수 보존식 검사.
   7) JSONL 의 session_end 와 요약 기록.
   - 정상 USER 종료에서는 cancelled_at_stop = 0 이 목표다.
   - 이 순서를 검증하는 테스트를 둔다(종료 시점에 Pose 실행 중, 큐에 표본 대기
     중인 경우).
5. 요약
   - 단계별 시간, 드롭, 갭, 처리 fps, 평균 전류·전력을 화면 on 행과 화면 off
     행으로 나눠 각각 표시한다.
   - 비교 통계는 세션 시작 뒤 60초를 워밍업으로 제외하고 계산한다. 워밍업
     구간의 값은 따로 한 줄로 표시한다.
   - 화면 on/off 비교 통계에서는 상태가 바뀐 직후 5초를 전환 구간으로 표시하고
     평균에서 제외한다. 구간별 표에는 제외한 초 수를 함께 적는다.
   - 화면 상태의 연속 구간별 표를 넣는다: 구간 시작·끝, on/off, Face 평균·p95,
     드롭, 평균 전력.
   - 프리셋별 합격 표시는 화면 off 행 기준이다: 처리 fps ≥ 23.5(E 는 ≥ 11.75),
     갭 초과 비율 < 1%, 드롭 < 1%. 합격 표시 옆에 화면 off 평균 전력과 최고
     thermal status 를 함께 보여 준다. G 는 성능만이 아니라 전력·발열과 함께
     판정한다.
   - 10초 추이 표를 넣는다(원래 지시문).
   - 구간 마커 "정지"의 이름을 "가만히"로 바꾼다.
6. 원래 지시문에서 이어받은 항목: Pose 의 스레드 분리, 분석 스레드에서 IO
   제거, 단계별 계측, 10초 추이 표.
7. PR 본문에 최종 스레드 모델(어떤 스레드가 무엇을 소유하고 무엇을 큐로
   넘기는지)과 계수 정의를 표로 적는다.

---
[정정 이력]
- 정정 1: "[추가 실측 근거와 항목]" 을 대체. CHANGELOG.md 를 수정 범위에 추가.
  frames_skipped_intentional·스키마 0.2.3, 프리셋 A~G(F 선택), 화면 on/off
  행과 합격선, 마커 이름 "가만히".
- 정정 2: 프레임 계수 4종(requested/analyzer_received/skipped/processed)과
  파생값(백프레셔·Face 실패), 비동기 Pose(큐 깊이 1, deep copy, 250ms 유예),
  F 의 head pose 는 편향 측정 절차로 검증, 요약의 60초 워밍업 제외·화면 상태
  구간표·합격 옆 전력·thermal.
- 정정 3: 집계기 단일 스레드 소유(모든 파이프라인은 큐에 post 만), 버킷 닫힘
  지연 300ms 유지(250ms 취소), Pose 계수 4종, frames_processed 는 Face 추론
  성공 직후 확정, "Face 실패" → frames_unprocessed_unexpected 와
  face_inference_errors 분리, pose_frame_copy_ms 독립 항목, 화면 전환 5초
  제외, PR 본문의 스레드 모델·계수 표.
- 정정 4: frames_sample_enqueued·post_face_failed, pose_completed/
  pose_applied/pose_errors, 보존 관계 5개와 세션 종료 시 검사·"계수 불일치"
  경고·단위 테스트.
- 정정 5(마지막 설계 변경): frames_sample_applied/late_dropped(/queue_dropped),
  정상 종료 순서 7단계와 pose_cancelled_at_stop, 세션 timestamp 창, 비정상
  종료 복원 요약의 "계수 검증 생략", 이 파일을 통합본으로 갱신.
- 명확화: 시간 창은 capture timestamp 기준(sessionStartCaptureTs ≤ captureTs <
  stopFenceCaptureTs, 종료 시 fence 먼저); 종료 1단계는 새 입력 차단만이
  아니라 실행 중인 Face·Scene 작업 종료(또는 취소 계수) 확인 뒤 barrier.
