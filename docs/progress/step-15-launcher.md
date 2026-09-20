# Step 15: 데스크톱 실행 앱 (서비스 한 번에 시작/중지)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 목적: 터미널을 열지 않고 **Desktop 아이콘 하나로** 서비스 5개를 띄우고 브라우저까지 연다

## 만든 것

| 파일 | 내용 |
|---|---|
| `scripts/lecturemate.sh` | `start` / `stop` / `status`. 서비스 5개 관리 |
| `scripts/app/start.applescript`, `stop.applescript` | macOS 앱 소스 |
| `scripts/build-apps.sh` | 앱 빌드 + Desktop 별칭 생성 |
| Desktop `LectureMate 시작.app`, `LectureMate 중지.app` | 실제 아이콘 (별칭) |

## 동작

**시작 아이콘을 누르면:**
1. 알림 표시 → 2. Ollama(없으면 기동) → 3. **OrbStack(Docker) 기동 및 대기** → 4. PostgreSQL 컨테이너 기동 후 healthy 대기
→ 5. AI 엔진(:8000) → 6. 백엔드(:8080) → 7. 프론트엔드(:3000)
8. 포트가 열릴 때까지 기다린 뒤 **브라우저에서 http://localhost:3000 열기**

이미 떠 있는 서비스는 건너뛰므로 여러 번 눌러도 안전합니다.

**중지 아이콘을 누르면:** 프론트·백엔드·AI 엔진을 종료하고 PostgreSQL 컨테이너를 멈춥니다. Ollama는 다른 작업에도 쓸 수 있어 그대로 둡니다.

로그는 `~/Library/Logs/LectureMate/` 에 서비스별로 쌓입니다.

```bash
# 터미널에서 쓰고 싶을 때
scripts/lecturemate.sh start | stop | status
```

## 이렇게 한 이유

- **AppleScript 앱(.app)으로 만든 이유:** `.command` 파일은 터미널 창이 뜹니다. 앱은 창 없이 실행되고 알림과 오류 대화상자를 쓸 수 있습니다.
- **앱은 저장소 안에 두고 Desktop에는 별칭만:** 앱이 **자기 위치를 기준으로** 프로젝트 경로를 찾기 때문에, 복사본을 Desktop에 두면 경로를 찾지 못합니다. 별칭은 원본 위치를 가리키므로 정상 동작합니다. 저장소를 옮겨도 다시 빌드하면 됩니다.
- **PATH를 스크립트에서 직접 설정:** GUI로 실행하면 셸 설정(`.zshrc`)을 읽지 않아 `brew`, `docker`, `pnpm` 을 찾지 못합니다. Homebrew·OrbStack·`~/.local/bin` 경로를 스크립트 앞부분에서 추가합니다.
- **타임아웃 900초:** AppleScript `do shell script` 의 기본 제한은 120초인데, Spring Boot 첫 빌드는 그보다 오래 걸립니다.
- **`.app` 결과물은 git에서 제외:** 컴파일 산출물이라 소스(`.applescript`)와 빌드 스크립트만 커밋합니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| 앱을 눌러도 서비스가 뜨지 않고 로그도 없음 | `path to me`가 `.app` 디렉토리 자체를 가리켜 상위 경로 계산이 한 단계 부족했음 (`scripts/scripts/...`를 찾고 있었음) | `../../../` 로 수정 |
| Desktop 심볼릭 링크로 앱 실행 실패 (`error -10810`) | macOS 는 심볼릭 링크를 통한 앱 실행을 허용하지 않음 | Finder **별칭**(alias)으로 생성 |
| **OrbStack 이 꺼져 있으면 백엔드 기동 실패** (2026-09-21) | PostgreSQL 컨테이너가 뜨지 못해 Spring Boot 가 DB 연결 실패로 종료. 스크립트는 Docker 가 켜져 있다고 가정했음 | `ensure_docker()` 추가: OrbStack 을 켜고 `docker ps` 가 응답할 때까지 최대 60초 대기. 이어서 `wait_for_postgres()` 로 healthy 확인 후 백엔드를 띄운다 |

## 검증 결과

- 앱 실행 → 5개 서비스 전부 기동 → 브라우저 자동 열림 확인
- 이미 실행 중인 상태에서 다시 눌러도 중복 실행 없이 10초 만에 완료
- 중지 앱 → 프론트·백엔드·AI·DB 정지, Ollama 유지 확인
- Desktop 별칭으로도 동일하게 동작
- 엔드포인트: 프론트 200, 백엔드 401(인증 동작), AI 엔진 200

## 추가 (2026-09-21): OrbStack 자동 기동

`ensure_docker()` 와 `wait_for_postgres()` 를 추가했습니다. OrbStack 을 종료한 상태에서 실행해 확인한 결과:

```
00:30:40 LectureMate 시작
00:30:40 OrbStack 시작...
00:30:42 ✓ Docker 준비됨
00:30:48 ✓ PostgreSQL 준비됨 (:5432)
```

DB 가 연결을 받기 전에 백엔드가 뜨면 기동에 실패하므로, postgres healthy 를 기다린 뒤 백엔드를 시작합니다.

## 남은 일

- 앱 아이콘이 기본 스크립트 아이콘입니다. 필요하면 `.icns` 를 넣어 바꿀 수 있습니다
- 로그 회전 정책이 없습니다 (계속 쌓임)
- 서비스가 뜨지 않으면 알림만 뜨고 원인은 로그를 봐야 합니다
