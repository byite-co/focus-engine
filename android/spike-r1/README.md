# android/spike-r1

화면을 끄고 잠근 상태에서 camera 타입 foreground service 가 전면 카메라 프레임을
끊김 없이 받는지(R1), 24fps 고정이 되는지(R2), 프레임 timestamp 를 elapsedRealtime
으로 환산할 수 있는지(R3)를 실기기 로그로 확인하는 스파이크. 버리는 코드다.
판정 로직·MediaPipe·Flutter 는 없다. 지시문은 `directives/A-spike-r1.md`,
정본은 `docs/focus/v0-plan-and-gt.md` 3장과 `docs/focus/spec-v0.2.0.md` 9장.

픽셀은 어디에도 저장하지 않는다. 분석기는 프레임마다 timestamp 두 개만 읽고, 1초에
한 번 Y plane 을 16x12 격자로 찍어 평균 휘도 하나만 남긴 뒤 바로 닫는다. INTERNET
권한은 없다.

## 구성

| 경로 | 내용 |
|---|---|
| `app/` | Android 앱. `MainActivity`(권한·시작·정지·요약), `CaptureService`(LifecycleService, CameraX ImageAnalysis 1개), `SessionRecorder`(초당 CSV·timebase·이벤트·요약) |
| `spikecore/` | 순수 JVM 모듈. `FrameStats`(갭 통계), `TimebaseEstimator`(offset·drift), `LumaGrid`(격자 휘도). Android 의존 없음, kotlin.test 단위 테스트 |
| `gradle/libs.versions.toml` | AGP, Kotlin, CameraX, AndroidX 버전 고정 |

minSdk 29, compileSdk 37.2, targetSdk 37. 패키지명 `kr.co.byite.focus.spike`(임시).

## 설치

1. GitHub Actions 의 `spike-r1` 워크플로에서 최신 성공 실행을 열고 `spike-r1-debug-apk`
   아티팩트를 받는다. `android/spike-r1/**` 가 바뀔 때마다 돌고, 디버그 APK 와 단위
   테스트 리포트를 올린다.
2. 압축을 풀고 설치한다.

   ```sh
   adb install -r app-debug.apk
   ```

3. 앱을 열고 `권한 요청` 으로 CAMERA 와 알림 권한을 허용한다.

로컬 빌드는 JDK 17 이상과 Android SDK(platforms;android-37.2, build-tools;37.0.0)가
있으면 된다.

```sh
cd android/spike-r1
./gradlew :spikecore:test :app:assembleDebug
```

## 실측 절차 (사람이 수행, 각 30분)

기기는 Galaxy S26 을 포함해 2종 이상. 폰은 거치대에 세워 전면 카메라가 사람 쪽을 보게
둔다. 세션마다 아래 순서로 한다.

1. 앱을 연다. 서비스는 Activity 가 보이는 상태에서만 시작할 수 있다.
2. `시작` 을 누른다. 상태 칸에 `카메라 바인딩됨 1280x720 fps [24,24]` 와 초당 갱신되는
   프레임 수가 보이면 정상이다. 알림 영역에도 진행 상태가 30초마다 갱신된다.
3. 시나리오대로 30분 둔다.
4. 화면을 켜고 앱으로 돌아와 `정지` 를 누른다. 요약이 화면에 뜬다. `요약 복사` 로
   클립보드에 복사해 기록에 붙인다.

| # | 시나리오 | 방법 |
|---|---|---|
| 1 | 화면 켠 채 | 시작 뒤 화면을 켜 둔다. 화면 자동 꺼짐 시간을 30분 이상으로 두거나 가끔 터치한다 |
| 2 | 화면 끄고 잠금 | 시작 뒤 전원 버튼으로 화면을 끄고 잠근 채 둔다 |
| 3 | 2 + 절전 설정 변경 | 2 를 아래 설정 조합별로 반복한다. 조합마다 별도 세션으로 돌리고 요약과 함께 설정값을 적는다 |
| 4 | 2 + 충전 중 | 충전기를 꽂은 채 2 를 반복한다 |

시나리오 3 에서 바꿔 볼 설정 (제조사마다 이름이 다르다):

- 배터리 최적화 예외: `배터리 최적화 설정 열기` 버튼으로 시스템 목록을 열어 이 앱을
  "최적화 안 함" 으로 바꾼다. 헤더의 `battery_optimization_ignored` 와 화면 상단에
  현재 값이 보인다.
- Samsung: 설정 > 배터리 > 백그라운드 사용 제한 > 잠자기 앱 / 절대 잠자지 않는 앱,
  "사용하지 않는 앱을 잠자기 상태로 전환" 끄기. 절전 모드 on/off.
- 기타 제조사: 앱 자동 실행, 백그라운드 활동 제한, 절전 모드.

세션이 끝나면 아래를 함께 기록한다: 기기 모델, OS 버전(헤더에 있다), 시나리오 번호,
바꾼 설정, 요약 전문.

## 합격 기준

세션(30분)마다:

- 80ms 초과 갭 < 1%
- 1초 넘는 갭 0
- 누락된 초 0 (프레임이 하나도 없는 1초 버킷이 없음)
- 서비스 생존 (30분 뒤 `정지` 를 눌렀을 때 앱이 살아 있고 요약이 나온다)

요약 마지막 줄에 앞의 세 항목을 자동으로 판정해 적는다. 서비스 생존은 사람이 확인한다.
앱을 다시 열었을 때 "이전 세션이 정상 정지되지 않았다" 가 보이면 서비스가 죽은 것이다.
그때는 `frames.csv` 의 마지막 행 시각이 종료 시각의 근사값이다 (flush 가 30초 단위라
최대 30초가 잘릴 수 있다).

R2 는 요약의 `fps 요청 / 결과` 와 `frame_duration`, `평균 fps` 로 본다. 요청 [24,24] 에
결과 [24,24], frame_duration 41.7ms, 평균 fps 23.9~24.0 이면 고정된 것이다. 지원 fps
범위 전체는 헤더의 `fps_available` 에 있다.

R3 는 헤더의 `timestamp_source` 와 요약의 `offset`·`drift`, `timebase.csv` 로 본다.
`REALTIME` 이면 프레임 ts 가 이미 elapsedRealtime 기준이고 offset 은 콜백 지연의
최솟값(수십 ms)이어야 한다. `UNKNOWN` 이면 offset 이 임의의 상수이고, drift 가 30분
동안 작게 유지되면 세션 시작 때 한 번 추정한 offset 으로 환산할 수 있다는 뜻이다.

## 로그 위치와 가져오기

앱 전용 외부 저장소 아래 세션별 디렉터리에 남는다. 화면의 `CSV 파일` 칸에 경로가 있다.

```
/sdcard/Android/data/kr.co.byite.focus.spike/files/spike-r1/<yyyyMMdd_HHmmss>/
  frames.csv     초당 1행
  timebase.csv   offset·drift (처음 100프레임, 이후 1분마다 100프레임)
  events.log     카메라 상태, 첫 프레임, fps 범위 변화, 1초 초과 갭, 행 누락
  summary.txt    정지 시 요약 (화면과 같은 내용)
```

```sh
adb pull /sdcard/Android/data/kr.co.byite.focus.spike/files/spike-r1/ ./spike-r1-logs/
```

로그는 `gt/sessions/` 에 두고 git 에는 올리지 않는다.

## 파일 형식

`frames.csv` 는 `#` 로 시작하는 헤더 줄 뒤에 열 이름 행, 그 뒤 초당 1행이다.
pandas 라면 `read_csv(path, comment='#')`.

헤더: `session_id, device_model, os_version, app_version, camera_id, resolution,
fps_selected, fps_available, timestamp_source, battery_optimization_ignored,
stabilization, start_t_mono_ms, start_t_utc_ms, start_local`.

| 열 | 뜻 |
|---|---|
| `t_mono_ms` | 행을 만든 시각, `SystemClock.elapsedRealtime()` |
| `t_utc_ms` | 같은 시각의 `System.currentTimeMillis()` |
| `frames` | 이 구간에 분석기가 받은 프레임 수 |
| `max_gap_ms` | 이 구간에서 닫힌 프레임 간 capture timestamp 갭의 최댓값. 이전 구간 마지막 프레임과의 갭도 포함 |
| `gaps_over_80ms` | 이 구간에서 80ms 를 넘긴 갭 수 |
| `cb_latency_ms_mean` | (콜백 `elapsedRealtimeNanos` − 프레임 ts) 평균. timestamp source 가 REALTIME 이면 콜백 지연이다 |
| `y_mean` | 1Hz 로 찍은 Y plane 16x12 격자 평균 휘도 (0–255). 없으면 빈칸 |
| `thermal_status` | `PowerManager.currentThermalStatus` (0 NONE … 6 SHUTDOWN) |
| `battery_pct` | 배터리 % |
| `battery_current_ua` | `BATTERY_PROPERTY_CURRENT_NOW` 원값. 부호와 단위(µA/mA)가 제조사마다 다르다 |
| `is_interactive` | 화면 on 이면 1 |
| `is_device_idle` | Doze 면 1 |

`timebase.csv`: `t_mono_ms, offset_ns, drift_ns, frames, complete`. 첫 행이 초기 offset
(drift 0), 이후 1분마다 한 행. `complete=0` 은 정지 때 100프레임을 못 채우고 닫은 부분
표본이다.

요약의 `capture result 스트림` 줄은 Camera2 `CaptureResult` 콜백에서 따로 센 값이다.
분석기가 `STRATEGY_KEEP_ONLY_LATEST` 로 버린 프레임과 무관하게 HAL 이 낸 프레임을
세므로, 분석기 통계에는 갭이 있는데 이 줄에는 없다면 카메라가 아니라 앱 쪽 지연이다.

## 알려진 한계

- 화면이 꺼진 뒤 이 앱을 다시 열려면 잠금을 풀어야 한다. 정지 전까지 세션은 계속된다.
- 요약의 `서비스 생존` 은 자동 판정하지 않는다.
- 프로세스가 강제 종료되면 마지막 flush(최대 30초) 이후의 행과 요약 파일이 남지 않는다.
  그 경우 앱을 다시 열면 비정상 종료 안내가 뜬다.
- 24fps 도 30fps 도 지원 목록에 없으면 fps 범위를 지정하지 않고 HAL 기본값으로 돈다.
  헤더 `fps_selected` 에 `unset` 으로 남는다.
