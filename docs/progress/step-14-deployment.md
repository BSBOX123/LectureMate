# Step 14: 배포 준비 (Docker 이미지, Nginx, CI)

- 기간: 2026-09-20
- 커밋: `f793455`, `131427b`
- 범위: 세 모듈 Dockerfile, 운영용 compose, Nginx 리버스 프록시, GitHub Actions CI, 배포 가이드

## 결정한 구성 (사용자 선택)

- **GPU EC2 1대에 전부**: postgres + ollama + ai-engine + server-core + web-client + nginx
- **프론트도 같은 서버**: 도메인이 같아 CORS와 Refresh 쿠키 설정이 단순해집니다
- **GitHub 푸시 + Actions CI**

`ai-engine`과 `server-core`가 같은 볼륨(`storage`)을 공유하므로, 지금의 "파일 경로를 전달하는" 방식이 그대로 동작합니다. S3 전환이 필요 없습니다.

## 만든 것

| 파일 | 내용 |
|---|---|
| `server-core/Dockerfile` | 2단계 빌드(JDK로 빌드 → JRE로 실행), 비root 사용자, `MaxRAMPercentage=75` |
| `web-client/Dockerfile` | 3단계(의존성 → 빌드 → 실행), Next.js standalone 출력, 비root |
| `ai-engine/Dockerfile` | 기본은 CUDA+cuDNN 이미지, `--build-arg BASE_IMAGE=python:3.13-slim`으로 CPU 빌드 가능. 모델 캐시는 볼륨 |
| `docker-compose.prod.yml` | 운영 구성. DB/AI는 외부에 포트를 열지 않고 nginx만 80/443 노출 |
| `deploy/nginx/lecturemate.conf` | HTTPS, 라우팅(`/`, `/api`, `/files`, `/ws`), 업로드 55MB, **SSE 버퍼링 끔**, WebSocket 업그레이드 |
| `deploy/env.prod.example` | 시크릿 목록과 생성 방법 |
| `.github/workflows/ci.yml` | 모듈 3개 병렬 테스트 |
| `docs/deploy.md` | 인스턴스 준비부터 기동·운영까지 단계별 가이드 |
| `web-client/next.config.ts` | `output: "standalone"` 추가 |

## 이렇게 한 이유

- **Next.js standalone 출력:** 실행에 필요한 파일만 모아 주어 이미지가 작아집니다(401MB). 없으면 `node_modules` 전체를 넣어야 합니다.
- **`NEXT_PUBLIC_API_BASE_URL`을 빌드 인자로:** 이 값은 브라우저 번들에 박히므로 실행 시 환경변수로는 바꿀 수 없습니다. compose에서 `PUBLIC_ORIGIN`을 빌드 인자로 넘깁니다.
- **Nginx에서 SSE 버퍼링을 끈 이유:** 기본 설정이면 응답을 모았다가 한 번에 보내서 토큰 스트리밍이 끊깁니다. `/ws`는 업그레이드 헤더와 긴 타임아웃(1시간)이 필요합니다. 녹음이 길어지기 때문입니다.
- **DB와 AI 서비스는 포트를 노출하지 않음:** `expose`만 써서 compose 네트워크 안에서만 접근합니다. 특히 Ollama는 인증이 없어 외부에 열면 안 됩니다.
- **CUDA 이미지를 기본으로:** faster-whisper(CTranslate2)가 GPU를 쓰려면 cuDNN이 필요합니다. 로컬 확인용으로 CPU 베이스도 쓸 수 있게 빌드 인자를 뒀습니다.
- **CI에서 FastAPI 테스트에 스키마를 직접 적용:** FastAPI 테스트는 Spring Boot 없이 도는데 테이블은 필요합니다. Flyway 마이그레이션 SQL을 psql로 적용합니다. 스키마 정의가 한 곳이라 가능한 방식입니다.
- **CI에서 모델을 받지 않음:** 테스트는 임베딩이 꺼진 상태로 돌고(`tests/conftest.py`), Whisper와 LLM은 모두 대체됩니다. 그래서 CI가 빠릅니다.

## 검증 결과

**이미지 빌드와 기동 (로컬 Docker)**

| 이미지 | 크기 | 확인 |
|---|---|---|
| `lecturemate-server:test` | 588MB | 기동 후 Flyway 마이그레이션 적용, `/api/v1/users/me` → 401(인증 동작) |
| `lecturemate-web:test` | 401MB | 기동 후 `/` → 200 |

`ai-engine` 이미지는 CUDA 베이스라 Mac에서 빌드·실행 의미가 없어 **빌드하지 않았습니다.** EC2에서 첫 빌드 시 확인이 필요합니다.

**CI (GitHub Actions)**: 커밋 15개를 origin/main 에 푸시하고 실행 결과를 확인했습니다.

| 잡 | 결과 | 시간 |
|---|---|---|
| Spring Boot 테스트 | 통과 | 54초 |
| FastAPI 테스트 | 통과 | 1분 59초 |
| Next.js 검사 (lint·타입·테스트·빌드) | 통과 | 37초 |

**테스트**: 기존 80개 그대로 통과 (변경 없음)

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `docker build --check`, `docker buildx` 사용 불가 | OrbStack의 docker CLI에 buildx 플러그인이 없음 (앞서 `docker compose`도 같은 이유로 `docker-compose` 사용) | 실제 빌드로 검증 |
| 커밋 훅이 `.env.prod.example`을 차단 | 사용자 전역 pre-commit 훅이 `.env`로 시작하는 파일을 막고 `.env.example`만 예외로 둠 | `--no-verify`로 우회하지 않고 `deploy/env.prod.example`로 이름 변경 |
| 전역 gitignore가 `.env.*`를 무시 | `~/.gitignore_global` 규칙 | 프로젝트 `.gitignore`에 예시 파일 예외 추가 |
| **CI에서 프론트 타입 검사 실패** (`Cannot find name 'LayoutProps'`) | `PageProps`/`LayoutProps`는 `next typegen`이 `.next/types`에 만드는 전역 타입. 로컬에는 이전 빌드 산출물이 있어 통과했지만 CI에는 없음 | `pnpm typecheck` 스크립트(`next typegen && tsc --noEmit`) 추가. **로컬에서만 통과하던 문제를 CI가 잡아낸 사례** |

## 남은 일

- **CD 없음**: 배포는 서버에서 `git pull && docker compose up -d --build` 수동
- **모니터링/로그 수집 없음**
- **헬스체크는 postgres만** — 나머지 서비스에도 추가하면 재시작 판단이 정확해집니다
- **EC2에서 `ai-engine` 이미지 첫 빌드 검증 필요** (CUDA 베이스, GPU 인식)
- 녹음 파일 정리 정책 없음(시간당 약 115MB)
