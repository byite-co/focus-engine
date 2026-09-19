# focus-engine

스마트폰 전면 카메라 하나로 학습자의 비집중·부재 상태를 측정하는 엔진이다.
Flutter 앱에 붙는 네이티브 측정 엔진(Android 우선)과 순수 로직 코어로 구성된다.
설계 기준선과 검증 규칙은 `docs/focus/`에 있고, 알고리즘 세부는 이 문서에 쓰지 않는다.

## 디렉터리

| 경로 | 용도 |
|---|---|
| `CLAUDE.md` | 세션 공통 규칙. 정본, 수정 범위, 데이터 경계, 시간, 커밋 |
| `CHANGELOG.md` | v0.2.x 설계 변경 기록. 설계 변경은 여기에만 쌓는다 |
| `docs/focus/` | 정본. `spec-v0.2.0.md`(설계 기준선, 동결)와 `v0-plan-and-gt.md`(v0 작업 분해·GT 검증 규칙). 읽기 전용 |
| `docs/research-notes/` | 연구노트 `RN-###-제목.md`. 템플릿은 `RN-000-template.md` |
| `docs/assets/` | 문서용 이미지·영상. 저장소에서 미디어 파일이 허용되는 유일한 위치 |
| `gt/scenarios/` | GT 시나리오 T1~T11 대본 |
| `gt/sessions/` | 실측 세션 로그. git에 올리지 않는다 (`.gitkeep`만 추적) |
| `core/focus-core/` | 순수 로직 코어. Kotlin JVM, Android·java.* 의존 금지 |
| `android/spike-r1/` | 리스크 스파이크 R1. 버리는 코드 |
| `directives/` | CC 세션 지시문 보관 |
| `tools/hooks/` | git 훅. `pre-commit`이 미디어 파일 규칙을 검사한다 |
| `.github/workflows/guard-media.yml` | push와 PR에서 같은 미디어 규칙을 검사한다 |
| `.claude/settings.json` | Claude Code 세션 시작 시 `core.hooksPath`를 `tools/hooks`로 설정한다 |

## 세션별 수정 범위

- 세션마다 지시문에 적힌 디렉터리만 수정한다. 다른 경로는 읽기 전용이다.
- `docs/focus/`는 동결 상태다. 설계 변경은 `CHANGELOG.md`의 v0.2.x 항목으로만 쌓는다.
- 순수 로직은 `core/focus-core/`에만 둔다.
- 스펙과 지시문·코드가 충돌하면 구현 전에 보고한다.

## 미디어 파일 규칙

`mp4, mov, avi, mkv, jpg, jpeg, png, heic, webp, bmp` 파일은 `docs/assets/` 아래에만 둔다.
다른 경로에 있으면 로컬 pre-commit 훅과 CI(guard-media)가 실패한다.
측정 세션의 녹화물이나 캡처는 저장소에 넣지 않는다.

## hooksPath 설정

Claude Code 클라우드 세션에서는 `.claude/settings.json`의 SessionStart 훅이 `core.hooksPath`를 자동으로 잡는다.
로컬 클론에서는 한 번 실행한다.

```sh
git config core.hooksPath tools/hooks
```
