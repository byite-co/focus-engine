# focus-engine

스마트폰 전면 카메라 하나로 학습자의 상반신~얼굴을 관찰해 초 단위 상태를 산출하는 집중도 측정 엔진.
v0의 상태 어휘는 PRESENT, AWAY, ABSENT, PRONE, PHONE, INVALID 여섯 개다.

알고리즘 설계 기준선은 Claude Docs의 [스마트폰 카메라 기반 집중도 측정 — 구현 스펙 v0.2](https://claude.ai/artifact/G86bAFvH34Z5GN6V9zxvqV)다.
v0 작업 분해와 ground-truth 검증 규칙은 같은 문서의 두 번째 탭에 있다.

## 스택

| 항목 | 결정 |
|---|---|
| 앱 구조 | Flutter 앱 + 플랫폼별 네이티브 측정 엔진 |
| 1차 플랫폼 | Android. Kotlin + CameraX + MediaPipe Tasks Vision |
| 2차 플랫폼 | iOS. Swift + AVFoundation + MediaPipe Tasks. Android v0 통과 뒤 시작 |
| 경계 | Flutter에는 영상 프레임을 넘기지 않는다. 약 1Hz의 state, coverage, 세션 통계, 캘리브레이션 상태만 전달 |
| 로컬 기록 | Room/SQLite에 초당 레코드와 interval 레코드. 재생 테스트용 JSONL 내보내기 |
| 서버 | 기존 Supabase 유지. 업로드는 세션 종료 후 1회 |

## 저장소 구조

| 경로 | 용도 |
|---|---|
| `docs/assets/` | 문서용 이미지·영상. 저장소에서 미디어 파일이 허용되는 유일한 위치 |
| `tools/hooks/` | git 훅. `pre-commit`이 `docs/assets/` 밖의 미디어 파일 커밋을 막는다 |
| `.github/workflows/guard-media.yml` | push와 PR에서 같은 규칙을 검사한다 |
| `.claude/settings.json` | Claude Code 세션 시작 시 `core.hooksPath`를 `tools/hooks`로 설정한다 |

## 미디어 파일 규칙

`mp4, mov, avi, mkv, jpg, jpeg, png, heic, webp, bmp` 파일은 `docs/assets/` 아래에만 둔다.
다른 경로에 있으면 로컬 pre-commit 훅과 CI(guard-media)가 실패한다.

스펙의 데이터 경계 원칙에 따라 영상, 이미지 프레임, 원시 랜드마크는 기기 밖으로 내보내지 않는다.
측정 세션의 녹화물이나 캡처를 저장소에 넣지 않는다.

## 설정

Claude Code 클라우드 세션에서는 `.claude/settings.json`의 SessionStart 훅이 `core.hooksPath`를 자동으로 잡는다.
로컬 클론에서는 한 번 실행한다.

```sh
git config core.hooksPath tools/hooks
```
