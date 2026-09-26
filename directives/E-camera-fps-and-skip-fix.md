# 지시문 E — 카메라 fps 프리셋(H), 처리 슬롯 계수, timestamp 영역 계약

[작업] 프리셋 H·E15 추가, 처리 슬롯 계수 도입, 카메라 timestamp 영역 계약
확정, 힌지 센서 초기값. 새 PR 로 올린다.
[수정 범위] android/focus-engine/**, core/focus-core/**(추가만),
CHANGELOG.md, docs/research-notes/RN-003-*.md(실측 결과 추가).
feature_schema_version 은 0.2.4 로 올린다.
[정본] docs/focus/spec-v0.2.0.md, docs/focus/v0-plan-and-gt.md,
CHANGELOG.md v0.2.1~v0.2.3, core/focus-core/README.md. 충돌·모호점은
구현으로 메우지 말고 PR 본문에 보고한다.
[실측 근거] SM-F966N, 화면 off 행: A 431mA(Face 38.3/41.8ms, 드롭 2.0%),
B 418, C 428, D 418, E 359mA(Face 56.6/60.1, fps 12.00), G 496mA.
해상도·blendshape·GPU 는 전류를 바꾸지 못했고 Face 절반(E)만 16% 감소.
화면 off 는 같은 세션 안 교대로 Face 14~22%, Pose 25~40% 느리게 한다.
이번 실험의 질문: Face 처리 빈도를 같게 두고 카메라 cadence 만 낮추면
전류가 줄어드는가.

## 1. 프리셋

- 기존 A·B·C·D·E·G 는 유지한다.
- H 는 카메라 자체 fps 를 낮춘 구성이다. 1280x720, CPU, blendshape on,
  Face 전 프레임, Pose 는 현재 계약 그대로(평상시 1fps, 얼굴 미검출 중
  3fps). 독립변수는 카메라 fps 하나다. 실제 cadence 로 이름을 나눈다:
  H12(고정 [12,12]), H15(고정 [15,15]), Hvar(상한 15 인 가변 range).
- E15: 카메라 24fps, Face 15Hz 처리 슬롯(3장 규칙, 주기 66.7ms). 나머지는
  E 와 같다.
- 비교 짝: H12 ↔ E(카메라 24 / Face 12), H15 ↔ E15. Face 빈도가 같은
  짝만 카메라 효과로 해석한다. H15 를 E 와 직접 비교하지 않는다.
- Hvar 는 고정 cadence 프리셋과 분리해 표시한다. 슬롯 계수와
  slot_miss_ratio 는 진단값으로 기록하되 슬롯 합격·불합격 판정을
  적용하지 않고, 요청 range 와 실측 cadence 분포·갭 분포만 보고한다.
- devapp 는 지원 range 를 조회해 가능한 H 변형만 목록에 보여 준다.
  E15 는 항상 보여 준다.
- header·요약에 요청 AE range, 지원 range 전체, CaptureResult 실측
  cadence(평균, 간격 중앙값·p95), 분석기 수신 fps, Face 처리 fps 를 적는다.
  고정 range 요청에서 실측 cadence 가 요청값과 3% 넘게 다르면 요약
  첫머리에 "cadence 불일치" 를 표시한다. 그 세션은 짝 비교에 쓰지
  않는다. Hvar 에는 이 판정을 적용하지 않는다.

## 2. 계수

- 기존 frames_* 계수의 의미는 바꾸지 않는다. backpressure 드롭은 카메라
  → 분석기 손실 그대로다.
- 처리 슬롯 계수 세 개를 추가한다. 각각 독립적으로 증가하는 terminal
  counter 다.
  processing_slots_expected: 스케줄러가 처리 기회를 선택할 때 +1.
  processing_slots_filled: 그 rawSensorTs 의 프레임이 Face 추론에
  성공할 때 +1.
  processing_slots_missed: 선택된 슬롯이 Face 성공 없이 종결될 때 +1
  (backpressure 로 분석기에 못 옴, pre-face 오류, Face 추론 오류, 정상
  종료 시까지 대응되지 않은 슬롯). 종료 시 미해결 슬롯은 CLOSE 후
  일괄 종결한다.
  요약에서 missed 를 expected − filled 로 만들어 내지 않는다.
  slot_miss_ratio = missed / expected.
- 보존식 expected = filled + missed 를 종료 검사(CounterConsistency)에
  넣는다. 세 값이 독립이므로 슬롯 하나를 잃는 버그가 있으면 이 식이
  깨진다.
- Face 전 프레임 구성(A, B, C, D, G, H12, H15, Hvar)은 세션 창 안의
  모든 CaptureResult 가 처리 기회다. E·E15 는 3장 규칙으로 기회를 뽑는다.
- missed 의 원인은 기존 계수(backpressure, pre_face_errors,
  face_inference_errors)로 설명한다.
- 진단 계수: capture_results_before_start, capture_results_after_fence,
  capture_results_after_close.
- capture_results_after_close > 0 이면 정상 종료 무결성 실패다
  (stop_integrity_failed). 그 CaptureResult 는 fence 전 실제 프레임인데
  expected 에 없어 보존식이 통과할 수 있기 때문이다. 요약 첫머리에
  표시하고 그 세션은 짝 비교에 쓰지 않는다.
- 합격 표시의 "드롭" 항목은 Hvar 를 제외한 고정 cadence 프리셋에서
  slot_miss_ratio < 1% 로 판정하고, raw 드롭 비율은 진단값으로 옆에
  표시한다.
- 갭 임계와 긴 갭 임계는 프리셋별 상수가 아니라 공식이다. ns 로
  계산하고 요약에서만 ms 로 표시한다.
  gap_threshold = 기대 처리 간격 × 1.5 (24fps 62.5ms, 15fps 100ms,
  12fps 125ms), long_gap_threshold = 기대 처리 간격 × 4.5 (24fps 187.5ms,
  15fps 300ms, 12fps 375ms). "초과"는 엄격 초과다. 임계를 정수배 위에
  두지 않는 이유는 실제 timestamp 지터가 정수배 경계에서 판정을 뒤집기
  때문이다. 24fps 에서는 기존 80 / 200ms 와 같은 프레임 수에서 갈린다.
  이전 세션과 비교 가능하다. 슬롯 하나를 놓치면 갭 초과로 잡혀야 한다.
  합격 표시의 긴 갭 항목은 "long_gap_threshold 초과 0".
- Hvar 의 gap_threshold / long_gap_threshold 는 합격 판정에 쓰지 않는다.
  요약에서 n/a 로 표시하고 실측 갭 분포만 보고한다.
- E 의 건너뜀이 수신 카운터 홀짝 기준이면 3장 규칙으로 바꾼다.

## 3. 처리 슬롯 규칙(스케줄러)

- 입력은 CaptureResult 의 raw SENSOR_TIMESTAMP 스트림이다. ImageAnalysis
  수신 순번을 쓰지 않는다. 모든 시각과 임계는 raw 영역(ns)이다.
- anchor = 세션 창(4장) 안의 첫 CaptureResult raw timestamp.
  next_due = anchor.
- 각 CaptureResult ts 에 대해 ts ≥ next_due − (카메라 프레임 간격 / 2)
  를 처음 만족하는 프레임을 처리 기회로 선택한다.
- 선택 뒤 next_due 는 선택된 ts 로 재설정하지 않는다. next_due +=
  처리 주기 로 전진하고, 캡처 공백으로 뒤처졌으면 next_due > ts 가 될
  때까지 주기 단위로 catch-up 한다. 목표 위상을 유지하므로 24fps →
  15Hz 는 41.7 / 83.3ms 간격이 섞여 평균 66.7ms 가 된다(0, 83.3, 125,
  208.3, 250, 333.3 …). 실측 cadence 가 명목과 달라도 위상이 누적되지
  않는다.
- 세션 시작 전 도착한 CaptureResult 는 raw timestamp 와 함께 임시
  보관했다가 sessionStartRawTs 가 정해지면 세션 창으로 걸러 스케줄러에
  replay 한다. 첫 ImageAnalysis 프레임에 대응하는 CaptureResult 를 잃지
  않아야 한다.

## 4. timestamp 영역과 세션 창

- 모든 카메라 기원 이벤트(CaptureResult, ImageAnalysis 프레임,
  FrameSample, Pose 요청·결과)는 rawSensorTs 와 captureMonoNs 를 함께
  가진다.
- 역할 분리. raw = 프레임 identity(CaptureResult.SENSOR_TIMESTAMP ↔
  ImageProxy.imageInfo.timestamp 완전 일치), 세션 소속 판정, 슬롯
  스케줄러, 카메라 계수 보존식. mono = 1초 버킷 위치, 갭, 지연, JSONL
  시간축. 변환된 mono 값으로 프레임을 대응시키거나 소속을 판정하지
  않는다. cameraToMono 와 cameraToMonoNoSample 은 UNKNOWN 기기에서 같은
  프레임에 다른 값을 줄 수 있다.
- sessionStartRawTs = 세션을 시작시킨 첫 ImageAnalysis 프레임의 raw
  timestamp. sessionStartMonoNs 는 기존대로.
- 정지 시 accept fence: offsetSnapshot = fence 시점의 카메라 offset
  추정값을 고정하고 이후 추정기를 갱신하지 않는다(freeze).
  stopFenceMonoNs 를 정하고 stopFenceRawTs = stopFenceMonoNs −
  offsetSnapshot. 이후 모든 카메라 timestamp 변환은 offsetSnapshot 을
  쓴다.
- 세션 소속 판정은 sessionStartRawTs ≤ rawSensorTs < stopFenceRawTs
  하나뿐이다. requested, analyzer_received, skipped, Face 오류,
  processed, sample_enqueued/applied, Pose 계수, 슬롯 계수 모두 이 raw
  창으로 포함·제외한다. 분석기 수락도 raw < stopFenceRawTs 로 판정한다.
- raw 창 안으로 인정된 카메라 입력은 mono 버킷 위치 때문에 다시 세션
  밖으로 거부하지 않는다. 버킷 index 는 raw 소속을 먼저 확정한 뒤 mono
  위치를 [첫 버킷, 마지막 부분 버킷] 범위로 clamp 해 정한다.
  rawSensorTs ≥ sessionStartRawTs 인데 captureMonoNs < sessionStartMonoNs
  이면 첫 버킷, rawSensorTs < stopFenceRawTs 인데 captureMonoNs ≥
  stopFenceMonoNs 이면 마지막 부분 버킷에 귀속하고 applied 로 센다.
- clamp 는 late_dropped 와 별개다. raw 창 안이라도 원래 버킷이 300ms
  close delay 뒤 이미 닫힌 다음 결과가 도착하면 기존대로
  sample_late_dropped / pose_late_dropped 다.
- fence 전에 이전 offset 으로 변환된 in-flight Face 작업이 fence 뒤에
  완료돼도 rawSensorTs 가 창 안이면 정상 processed/applied 다.
- raw 창 밖의 입력만 before_start / after_fence 로 센다. IMU·상태처럼
  raw 카메라 timestamp 가 없는 입력은 기존 mono fence 를 유지한다.
- 버킷 닫힘 지연 300ms 는 유지한다.

## 5. 정지 순서(기존 7단계에 슬롯 gate 추가)

fence 설정(offset freeze, raw fence 계산) → 카메라 producer 정지 →
분석·CaptureResult 콜백 drain → 슬롯 scheduler CLOSE(미해결 슬롯을
missed 로 종결) → Pose 정리 → aggregation 큐 drain →
FeatureAggregator.finish(fence) → 보존식 검사(슬롯 보존식 포함) →
session_end·요약.
- CLOSE 뒤 도착한 CaptureResult 는 fence 전 캡처라도 expected 에 넣지
  않고 capture_results_after_close 로 센다. 정상 종료에서는 0 이어야
  하며, 0 이 아니면 stop_integrity_failed 다(2장).
- last_slot_capture_result_raw_ts 를 기록한다면 마지막 수락 CaptureResult
  의 raw timestamp 다.
- session_end.t_mono_ms = stopFenceMonoNs. t_utc_ms 도 fence 시각에
  대응해 계산한다(t_start_utc + (fence − t_start_mono)). 요약 작성
  시각을 session_end 에 쓰지 않는다. finish(fence)와 session_end 가
  같은 논리적 끝을 가리킨다(PR #7 의 StopFinalizer 계약 유지).

## 6. 힌지 센서

변화 시에만 이벤트를 주는 센서이므로 등록 뒤 2초 안에 이벤트가 없으면
마지막으로 알려진 값을 쓰고, 그것도 없으면 "미상"으로 둔다. 세션 간에
마지막 값을 유지한다.

## 7. 요약

- 프리셋 이름(H 는 H12/H15/Hvar, E15 포함)을 첫머리에 표시한다.
- 전류 mA 를 mW 앞에 두고 둘 다 남긴다. 배터리 % 가 20% 미만이면 경고
  줄을 넣는다.
- 슬롯 계수 3종과 slot_miss_ratio, 진단 계수 3종, 갭 임계 실제값(ms,
  Hvar 는 n/a), cadence 불일치 여부, stop_integrity_failed 여부를
  표시한다.
- 나머지(화면 on/off 행, 구간표, 추이표, 갭 원인, 합격 표시)는 기존
  형식을 유지한다.

## 8. 기록

- RN-003 에 A·B·C·D·E·G 실측 요약(off 행 표, 확정 사실)을 붙인다.
- CHANGELOG v0.2.4 (발견·결정자: "발견: 실측·외부 리뷰 / 결정: 김요섭"):
  (a) 스키마 0.2.4: 처리 슬롯 계수 3종(독립 terminal counter), 진단
      계수 3종, stop_integrity_failed, H 관련 header 필드. 갭 임계를
      프리셋 상수에서 기대 간격 공식(1.5배·4.5배)으로 변경. 24fps 에서
      분류 결과는 기존과 동일.
  (b) 카메라 timestamp 영역 계약: raw = identity·소속·슬롯, mono =
      위치, fence 시 offset freeze, 양끝 버킷 clamp, session_end = fence.
  (c) 스펙 7장 전력 추정 정오표(추론 비용 과소, 총 전류가 추론 변형에
      둔감). "j 기준값은 실측 cadence 에 종속한다. 캘리브레이션은 운용
      cadence 에서 수행하고, cadence 가 다른 전력 상태는 별도 기준을
      쓴다." "W2 의 Δ = 1/6초는 12·24·30fps 에서 정수 프레임이지만
      15fps 에서는 2.5프레임이다. timestamp 보간은 유지하되 v1 동등성
      검증에서 H15/E15 의 W2 편향을 별도 확인한다."
  (d) 운용 base cadence 를 24fps 에서 12 또는 15fps 로 바꾸는 것은 스펙
      7장의 v5 결정을 실측 근거로 앞당기는 명시적 변경이다. v0 는 30분
      세션 통과 시 채택. v1 은 24fps 수집 데이터를 후보 cadence 로
      다운샘플해 EAR·PERCLOS·감김 episode·W2·jawOpen·eyeLook 의
      동등성을 확인한 뒤 채택한다(프레임 단위 스칼라 기록은 v4 에서
      설계). 1장의 "정상 측정 중 fps 를 깎지 않는다"는 원칙은 운용 base
      cadence 기준으로 유지된다.

## 9. 테스트

선택된 timestamp 목록을 실제로 생성해 검증한다. 테스트는 실제 production
스케줄러·집계기 코드를 호출해야 한다.
(a) 24.000fps → 15Hz 장시간: filled rate ≈ 15Hz, 간격이 41.7 과 83.3
    만 나타남.
(b) 23.98fps → 15Hz 8분, 손실 0: missed = 0.
(c) 24fps → 12Hz: filled ≈ 12Hz.
(d) 캡처 공백 500ms 뒤 catch-up 이 위상을 유지함.
(e) UNKNOWN 을 모사해 두 변환 경로의 offset 이 수 ms 다를 때도 raw
    identity 로 filled 가 어긋나지 않음.
(f) 세션 시작 전 CaptureResult 3개는 expected 에 들어가지 않고, 첫
    ImageAnalysis 에 대응하는 CaptureResult 는 replay 뒤 첫 처리 기회로
    정확히 대응됨.
(g) fence 전에 캡처된 처리 기회 2개가 backpressure 로 분석기에 못 온
    경우: expected +2, missed +2.
(h) fence 뒤에 캡처된 CaptureResult 3개가 CLOSE 전에 도착: expected
    불변, after_fence = 3, 분석기도 같은 3개를 거부.
(i) UNKNOWN 에서 fence 직전 프레임이 old offset 으로 captureMonoNs >
    fenceMono 가 된 뒤 Face 가 fence 이후 완료: rawTs < fenceRaw 이면
    requested/received/processed/applied 보존, after_fence·missed·
    late_dropped 어디에도 잡히지 않음.
(j) 시작 clamp: 첫 프레임의 CaptureResult 가 old offset 으로
    captureMonoNs < sessionStartMonoNs 여도 첫 버킷에 귀속되고 첫 초의
    보존식(requested = backpressure + received)이 성립. 종료 쪽 대칭
    케이스도 마지막 부분 버킷에서 성립.
(k) 정상 정지에서 expected = filled + missed 가 종료 검사에 연결되고
    capture_results_after_close = 0.
(l) 고정 [24,24] 요청에 실측 cadence 23.2fps → "cadence 불일치" 표시.
    Hvar 에서는 표시하지 않음.
(m) 스케줄러가 선택한 슬롯 하나를 filled 에도 missed 에도 반영하지
    않는 버그를 주입하면 종료 검사에서 expected ≠ filled + missed 로
    실패함.
(n) 갭 임계 공식: 24fps 에서 2프레임 간격(83.3ms)은 갭 초과, 4프레임
    간격(166.7ms ± 0.5ms 지터)은 긴 갭 초과 아님, 5프레임 간격(208.3ms)
    은 긴 갭 초과. 12fps·15fps 에서도 같은 프레임 수 규칙이 성립.
(o) fence 전 CaptureResult 하나를 scheduler CLOSE 뒤에 도착시키면
    capture_results_after_close = 1 이고 stop_integrity_failed 가
    표시된다. session_end.t_mono_ms 는 요약 작성이 700ms 걸려도
    fence 시각이다.

## 10. 검증과 보고

단위 테스트, assembleDebug, 데이터 경계 테스트, CI 통과. PR 본문에
적는다: 슬롯 스케줄러와 timestamp 영역 계약의 구현 위치, 테스트 (a)~(o)
결과, H 변형 선택 로직, 계수 정의·보존식 표(기존 표에 슬롯 계수 3종을
독립 counter 로 추가), 정지 순서 갱신, 스펙·이전 지시문과 어긋난 점.

## README 실측 절차(사람이 수행)

50% 이상 충전하고 thermal NONE 뒤 시작. 접은 상태, 거치·책·자세 고정,
충전기 제거, 세션 사이 충전 금지.
- [12,12] 지원: A(5분) → E(8분) → H12(8분) → E(8분) → A(5분).
- [15,15]만 지원: A(5분) → E15(8분) → H15(8분) → E15(8분) → E(8분) →
  A(5분).
A 는 워밍업 60초 뒤 화면 ON/OFF 60초 교대. 첫 A 의 워밍업에서 정면 →
왼쪽 → 정면 → 오른쪽 → 내려다보기(10초 구간 하나를 채움) → 정면 → 위.
E·E15·H 는 워밍업 60초 뒤 화면 off 7분. 세션마다 요약을 복사한다.

## 정정 이력(요약)

- 원문: H 추가, E 드롭 재분류, 힌지 초기값.
- 정정 1: 기존 frames_* 의미 유지, 슬롯 계수 별도 도입, H 이름을 실측
  cadence 로 구분, Pose 계약 유지, j cadence 종속 메모.
- 정정 2: H12↔E, H15↔E15 짝, E15 추가, Hvar 분리.
- 정정 3: 슬롯 next_due 를 선택 프레임에 재앵커하지 않고 위상 유지
  (재앵커 시 24→15Hz 가 12Hz 로 붕괴), cadence 불일치 표시.
- 구현 조건: 프레임 identity 를 raw timestamp 로.
- 구현 조건 정정 1~3: 시작 경계 raw, 종료 경계는 마지막 수락 프레임이
  아니라 raw fence(마지막 수락 프레임 상한은 정지 직전 손실을 숨김),
  offset freeze, 카메라 이벤트 소속 판정 raw 통일, 종료 clamp,
  시작 전 CaptureResult replay.
- 구현 경계조건: 시작 clamp 대칭 추가, clamp 와 late_dropped 구분.
- 최종 정정 1: missed 를 독립 terminal counter 로(expected − filled
  파생은 보존식을 항진식으로 만듦), 갭 임계를 공식으로 통일, Hvar 를
  전 프레임 목록에 포함하고 합격·cadence 판정에서 제외.
- 최종 정정 2: long gap 4.5배(정수배 경계 지터), after_close 를 무결성
  실패로 격상, session_end = fence 명시, Hvar 임계 n/a.
