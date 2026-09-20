# LectureMate AI 진행 기록

`SPEC.md`를 기준으로 단계별 구현 과정을 기록합니다. 각 Step 문서에는 구현한 기능, 코드 설명, 모듈/엔티티 간 관계, 결정 이유, 트러블슈팅이 들어 있습니다.

## 진행 현황

| Step | 내용 | 상태 | 커밋 | 문서 |
|---|---|---|---|---|
| 1 | 인프라(Docker Compose) 및 DB 스키마 | 완료 | `91b445c` | [step-1-infra-db.md](step-1-infra-db.md) |
| 2 | FastAPI `ai-engine` 스캐폴딩 | 완료 | `16bef97` | [step-2-ai-engine.md](step-2-ai-engine.md) |
| 3 | Spring Boot `server-core` 스캐폴딩 | 완료 | `fbb3153` | [step-3-server-core.md](step-3-server-core.md) |
| 4 | Next.js `web-client` 스캐폴딩 | 완료 | `8e486ed` | [step-4-web-client.md](step-4-web-client.md) |
| 5 | 인증 (Spring Security + JWT, 로그인/회원가입 화면) | 완료 | `9f277da` | [step-5-auth.md](step-5-auth.md) |
| 6 | 강의 생성 / PDF 업로드 / 파싱 | 완료 | `a1bee50` | [step-6-lecture-upload.md](step-6-lecture-upload.md) |
| 7 | PDF.js 슬라이드 렌더링, 테스트 DB 분리 | 완료 (검토 대기) | - | [step-7-pdf-viewer.md](step-7-pdf-viewer.md) |

## 주요 결정 기록

| 날짜 | 결정 | 근거 | 관련 |
|---|---|---|---|
| 2026-09-18 | 모듈 디렉토리는 `server-core` / `ai-engine` / `web-client` | SPEC 우선. 기존 CLAUDE.md(현 AGENTS.md)를 SPEC에 맞게 수정 | Step 1 |
| 2026-09-18 | `users` 테이블, `User` 엔티티 추가 | `lectures.user_id`가 참조할 대상 필요, Spring Security + JWT 인증 주체 | Step 1, 3 |
| 2026-09-20 | 로컬 `ollama` 컨테이너 유지 | SPEC §5.2 명령과 일치 | Step 1 |
| 2026-09-20 | 개발 중 LLM은 EC2 Ollama를 SSH 터널로 연결, 통합/시연은 GPU EC2 한 대에 `ai-engine` 전체 | Mac 컨테이너는 GPU 사용 불가, Faster-Whisper는 Metal 미지원 | Step 2 |
| 2026-09-20 | 실시간 STT는 Spring Boot와 FastAPI 사이 WebSocket (SPEC §2.2-5 추가) | 청크마다 HTTP 요청하는 방식보다 지연과 오버헤드가 적음 | Step 2 |
| 2026-09-20 | Docker 런타임을 OrbStack으로 변경 | 사용자 환경 변경. 명령어는 그대로 사용 | Step 1 |
| 2026-09-20 | ~~Spring Boot 3.3.13 + Gradle 8.14.3~~ → **Spring Boot 4.1.1 + Gradle 9.7.1** | 3.3.x와 3.5.x 모두 OSS 지원 종료. 4.1.x는 2027-07-31까지 지원. SPEC도 4.1.x로 수정 | Step 3 |
| 2026-09-20 | PDF 업로드 최대 50MB (요청 전체 55MB) | 사용자 요청. Spring 기본 1MB로는 실제 강의 PDF 업로드 불가 | Step 3 |
| 2026-09-20 | 로컬 파일 저장 경로 `~/lecturemate/storage`, 서버는 `STORAGE_LOCAL_PATH` 환경 변수로 SPEC 값 사용 | macOS에서는 `/data` 생성 불가. Spring Boot와 FastAPI가 같은 절대경로를 봐야 함 | Step 3 |
| 2026-09-20 | Spring Security + JWT는 Step 4 이후 별도 인증 단계에서 | SPEC에 로그인 API가 없고, 의존성만 넣으면 전체 API가 401 | Step 3 |
| 2026-09-20 | 원본 파일 저장은 **현행 유지** (Spring Boot 로컬 디스크, `STORAGE_LOCAL_PATH`). 데스크톱 앱으로 확장할 때 D안(로컬 동기화 폴더) 재검토 | 사용자 결정. 비용 분석 결과 PDF와 음성은 S3 기준 저렴하고, 문제는 영상. 대안(A: S3 + 영상 로컬, B: 로컬 우선, C: Google Drive, D: 데스크톱 앱)은 필요할 때 다시 검토 | 전체 |
| 2026-09-20 | Next.js 14 → **16.3.5** | 14는 2025-10-26, 15는 2026-10-21 지원 종료. SPEC과 AGENTS도 16으로 수정 | Step 4 |
| 2026-09-20 | pnpm은 corepack으로 설치 (`~/.local/bin`) | 사용자 선택. `packageManager` 필드로 버전 고정 | Step 4 |
| 2026-09-20 | JWT는 Access(30분) + Refresh(14일, httpOnly 쿠키, DB 저장 및 회전) | 사용자 선택. Access 탈취 피해 최소화, 로그아웃/폐기 가능 | Step 5 |
| 2026-09-20 | 내부 Webhook은 `X-Internal-Secret` 공유 시크릿 헤더 | 사용자 선택. 구현이 단순하고 EC2 분리 배포에도 안전 | Step 5 |
| 2026-09-20 | JWT는 Spring Security 내장(Nimbus) 사용, 외부 라이브러리 미사용 | 의존성 및 버전 관리 단순화 | Step 5 |
| 2026-09-20 | Access Token은 프론트 메모리에만 보관, 새로고침 시 refresh로 복구 | localStorage는 XSS 취약 | Step 5 |
| 2026-09-20 | PDF 파싱은 트랜잭션 커밋 후 비동기 호출, 완료 시 status=READY | SPEC §2.1-1 응답이 PROCESSING, FastAPI가 FK 때문에 커밋된 lectures 행 필요 | Step 6 |
| 2026-09-20 | 슬라이드 임베딩은 `EMBEDDING_ENABLED`(기본 false)로 분리 | bge-m3 약 2GB 다운로드와 CPU 추론 비용. RAG 단계에서 활성화 | Step 6 |
| 2026-09-20 | 강의 목록 API/화면(§2.1-11)과 PDF 내려받기(§2.1-12) 추가 | 업로드한 강의로 이동할 경로가 필요 | Step 6 |
| 2026-09-20 | 프론트엔드 테스트 도구로 Vitest 도입 (`pnpm test`) | 좌표 변환 등 순수 함수 검증 필요. Next.js 표준 선택 | Step 7 |
| 2026-09-20 | 통합 테스트는 전용 DB `lecturemate_test` 사용 | 테스트의 deleteAll 이 개발용 데이터를 삭제하는 문제 발견 | Step 7 |
| 2026-09-20 | 브라우저 실검증은 헤드리스 Chrome(puppeteer-core) 스크립트로 수행 | Claude 브라우저 확장 미연결. CORS 등 curl 로 안 잡히는 문제를 잡음 | Step 7 |

## 미결 질문

- [x] ~~Spring Boot 버전~~ → 4.1.1로 업그레이드
- [x] ~~Spring Security / JWT 시점~~ → Step 4 이후 별도 인증 단계
- [x] ~~PDF 업로드 최대 크기~~ → 50MB
- [x] ~~로컬 파일 저장 경로~~ → `~/lecturemate/storage`
- [ ] SPEC §4.1에 `LectureTranscriptRepository`가 없음. 필요 여부 (기능 구현 단계에서 결정)
- [x] ~~인증 API SPEC 정의~~ → §2.1-6 ~ §2.1-10 추가 완료
- [x] ~~원본 파일 저장 전략~~ → 현행 유지, 앱 확장 시 D안 재검토
- [ ] `SlideTimeline`의 데이터(슬라이드별 발화 분량, 시험 힌트 유무)를 가져올 API가 SPEC §2.1에 없음 (Step 4)
- [ ] §2.1-5 채팅 SSE의 이벤트 형식(토큰 청크, citations 구조)이 정의되지 않음 (Step 4)
- [x] ~~프론트엔드 API 주소 환경 변수와 CORS~~ → `NEXT_PUBLIC_API_BASE_URL`, `WEB_CLIENT_ORIGIN` (Step 5)
- [x] ~~강의 목록과 PDF 업로드 화면~~ → `/lectures`, `/lectures/new` 추가, SPEC §2.1-11·12 반영 (Step 6)
- [ ] `/ws/v1/**` WebSocket 인증 방식(브라우저는 헤더를 못 붙임 → 쿼리 파라미터 토큰 등): WS 핸들러 구현 시 결정 (Step 5)
- [ ] 로그인 상태가 아닐 때 `/lectures/[id]` 접근 차단(라우트 가드) 미구현 (Step 5)
- [ ] `ai-engine`에 `INTERNAL_API_SECRET` 반영 필요 (Webhook 호출 코드 작성 시) (Step 5)
- [ ] DB 스키마 변경 방식: 현재는 `init.sql` + 볼륨 재생성. 데이터가 쌓이기 전에 Flyway 도입 검토 (Step 5)
- [x] ~~`PdfViewer` PDF 렌더링~~ → PDF.js 적용 완료 (Step 7)
- [ ] 강의 삭제 API와 파일 정리 정책이 없음 (Step 6)
- [ ] 업로드 실패(FAILED) 시 재시도 방법이 없음 (Step 6)
- [ ] 브라우저 e2e 스크립트를 저장소에 포함할지 (현재는 스크래치패드에만 있음) (Step 7)
- [ ] 첫 화면에서 `/auth/refresh` 401 콘솔 오류가 보임 (세션 없을 때 정상 동작이지만 노이즈) (Step 7)
