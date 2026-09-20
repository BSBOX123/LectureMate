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
| 7 | PDF.js 슬라이드 렌더링, 테스트 DB 분리 | 완료 | `43d5947` | [step-7-pdf-viewer.md](step-7-pdf-viewer.md) |
| 8 | Flyway 도입 (DB 마이그레이션) | 완료 | `85bb4ec` | [step-8-flyway.md](step-8-flyway.md) |
| 9 | 녹음 + 실시간 자막 (WebSocket + Whisper) | 완료 | `ba67ee8` | [step-9-recording.md](step-9-recording.md) |
| 10 | 배치 정밀 전사 (녹음 종료 → 분석 → 완료 Webhook) | 완료 | `5db8473` | [step-10-batch-transcription.md](step-10-batch-transcription.md) |
| 11 | 슬라이드-음성 정렬 (Monotonic DP), 임베딩 활성화 | 완료 | `c3dc8c8` | [step-11-alignment.md](step-11-alignment.md) |
| 12 | 자동 필기 생성 (LLM) + 주석 조회 API | 완료 | `308eb45` | [step-12-annotations.md](step-12-annotations.md) |
| 13 | RAG 질의응답 (하이브리드 검색 + SSE) | 완료 | `d6a6ff6` | [step-13-rag-chat.md](step-13-rag-chat.md) |
| 14 | 배포 준비 (Docker 이미지, Nginx, CI) | 완료 | `f793455` | [step-14-deployment.md](step-14-deployment.md) |
| 15 | 데스크톱 실행 앱 (서비스 일괄 시작/중지) | 완료 (검토 대기) | - | [step-15-launcher.md](step-15-launcher.md) |

> GitHub: [BSBOX123/LectureMate](https://github.com/BSBOX123/LectureMate) — main 푸시 완료, CI 3개 잡 통과

**SPEC에 정의된 핵심 기능(§2.1-1 ~ §2.1-5)이 모두 구현되었습니다.** 배포 절차는 [deploy.md](../deploy.md) 참고.

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
| 2026-09-20 | 스키마 관리를 Flyway 로 이관 (`V1__init_schema.sql` 부터) | 볼륨 삭제 없이 스키마 변경. 운영 데이터가 쌓이기 전에 도입 | Step 8 |
| 2026-09-20 | WebSocket 인증은 쿼리 파라미터 토큰 | 사용자 선택. 브라우저가 WS 에 헤더를 못 붙임. 검증은 핸들러 한 곳에 집중 | Step 9 |
| 2026-09-20 | 오디오는 16kHz 모노 16bit PCM 전송 | 사용자 선택. 청크 독립 디코딩 가능, .wav 저장과 직결. 시간당 약 115MB | Step 9 |
| 2026-09-20 | 실시간 프리뷰는 Whisper base, 배치는 large-v3 | base 가 아니면 CPU 에서 3초 청크를 따라가지 못함 (SPEC §4.2와 일치) | Step 9 |
| 2026-09-20 | 정밀 분석은 사용자가 버튼으로 시작 (자동 호출 아님) | 녹음 종료 직후 자동 호출 시 WAV 생성 전 도착해 409 발생 가능 | Step 10 |
| 2026-09-20 | 완료 통보는 Webhook, 값은 임시 의미 (`totalPagesAnalyzed=0`) | 정렬/필기 미구현 구간. SPEC §2.2 에 주석 명시 | Step 10 |
| 2026-09-20 | `EMBEDDING_ENABLED` 기본값 false → **true** | 정렬과 RAG 에 임베딩이 필수. 테스트에서는 conftest 로 비활성 유지 | Step 11 |
| 2026-09-20 | 정렬은 단조성 제약 DP + 전환 패널티 0.05 | 강의는 슬라이드를 되돌아가지 않음. 유사도만 쓰면 문장마다 페이지가 튐 | Step 11 |
| 2026-09-20 | **Mac 로컬 LLM 은 네이티브 Ollama**(brew), Docker Ollama 미사용 | 컨테이너 0.3 tok/s vs 네이티브 9.1 tok/s (약 30배). compose 서비스는 Linux/EC2 용으로 유지 | Step 12 |
| 2026-09-20 | 하이라이트는 같은 줄 연속 단어만 병합 + 중복 제거 | 전부 병합하면 제목·본문을 아우르는 거대한 상자가 생김 (화면 검증에서 발견) | Step 12 |
| 2026-09-20 | 채팅 SSE 형식 정의: citations → token → done (SPEC §2.1-5) | 출처를 먼저 보내면 답변 생성 중에도 뱃지를 표시할 수 있음 | Step 13 |
| 2026-09-20 | Spring Boot 는 SSE 를 해석하지 않고 바이트 그대로 중계 | 형식이 바뀌어도 중계 코드 수정 불필요 | Step 13 |
| 2026-09-20 | 배포는 **GPU EC2 1대에 전부** (postgres/ollama/ai-engine/server-core/web-client/nginx) | 사용자 선택. 같은 볼륨을 공유해 파일 경로 전달 방식 유지 가능 → S3 불필요 | Step 14 |
| 2026-09-20 | 프론트도 같은 서버 + Nginx | 도메인이 같아 CORS·Refresh 쿠키 설정이 단순 | Step 14 |
| 2026-09-20 | GitHub Actions CI (모듈 3개 병렬 테스트) | 모델 다운로드 없이 80개 테스트를 돌릴 수 있는 구조 | Step 14 |
| 2026-09-20 | **A안 확정: 전부 로컬 실행. EC2 배포하지 않음** | Mac 네이티브 Ollama 9.1 tok/s 로 실용적. GPU EC2 는 시간당 1.2~1.5달러. 만든 인스턴스는 t3.micro(RAM 1GB, GPU 없음)라 사용 불가 | Step 14 |
| 2026-09-20 | EC2 보안 그룹 SSH 22번: 0.0.0.0/0 → 내 IP(/32) | 전 세계 개방 상태였음. 자동화된 공격 시도 차단 | Step 14 |
| 2026-09-20 | **EC2 인스턴스(t3.micro) 종료(삭제)** | A안 확정으로 당분간 불필요. 필요하면 다시 만들면 됨 | Step 14 |
| 2026-09-20 | 실행은 Desktop 아이콘(AppleScript 앱)으로 | 터미널 4개를 여는 대신 아이콘 하나로 전체 기동 + 브라우저 열기 | Step 15 |

## 미결 질문

- [x] ~~Spring Boot 버전~~ → 4.1.1로 업그레이드
- [x] ~~Spring Security / JWT 시점~~ → Step 4 이후 별도 인증 단계
- [x] ~~PDF 업로드 최대 크기~~ → 50MB
- [x] ~~로컬 파일 저장 경로~~ → `~/lecturemate/storage`
- [ ] SPEC §4.1에 `LectureTranscriptRepository`가 없음. 필요 여부 (기능 구현 단계에서 결정)
- [x] ~~인증 API SPEC 정의~~ → §2.1-6 ~ §2.1-10 추가 완료
- [x] ~~원본 파일 저장 전략~~ → 현행 유지, 앱 확장 시 D안 재검토
- [ ] `SlideTimeline`의 데이터(슬라이드별 발화 분량, 시험 힌트 유무)를 가져올 API가 SPEC §2.1에 없음 (Step 4, 12)
- [x] ~~§2.1-5 채팅 SSE 이벤트 형식~~ → citations/token/done 정의 및 구현 (Step 13)
- [x] ~~프론트엔드 API 주소 환경 변수와 CORS~~ → `NEXT_PUBLIC_API_BASE_URL`, `WEB_CLIENT_ORIGIN` (Step 5)
- [x] ~~강의 목록과 PDF 업로드 화면~~ → `/lectures`, `/lectures/new` 추가, SPEC §2.1-11·12 반영 (Step 6)
- [x] ~~`/ws/v1/**` WebSocket 인증 방식~~ → 쿼리 파라미터 토큰 + 핸들러 검증 (Step 9)
- [ ] 로그인 상태가 아닐 때 `/lectures/[id]` 접근 차단(라우트 가드) 미구현 (Step 5)
- [x] ~~`ai-engine`에 `INTERNAL_API_SECRET` 반영~~ → Webhook 호출에 적용 (Step 10)
- [x] ~~DB 스키마 변경 방식~~ → Flyway 도입 완료 (Step 8)
- [x] ~~`PdfViewer` PDF 렌더링~~ → PDF.js 적용 완료 (Step 7)
- [ ] 강의 삭제 API와 파일 정리 정책이 없음 (Step 6)
- [ ] 업로드 실패(FAILED) 시 재시도 방법이 없음 (Step 6)
- [ ] 브라우저 e2e 스크립트를 저장소에 포함할지 (스크래치패드가 정리되어 현재는 없음) (Step 7)
- [x] ~~배포 설계~~ → GPU EC2 1대 구성, Dockerfile/compose/Nginx/CI 준비 완료 (Step 14)
- [ ] EC2 에서 `ai-engine` CUDA 이미지 첫 빌드·GPU 인식 검증 (GPU 인스턴스 확보 후) (Step 14)
- [ ] (보류) EC2 는 삭제됨. 외부 공개가 필요해지면 다시 생성:: EC2 t3.small 이상 + 탄력적 IP + 80/443 개방 + Mac 역터널, 또는 GPU 인스턴스 (Step 14)
- [ ] Whisper 를 Metal 지원 백엔드(whisper.cpp / mlx-whisper)로 교체할지 — 배치 전사 속도 문제 (Step 14)
- [ ] CD(자동 배포), 모니터링/로그 수집, 서비스 헬스체크 (Step 14)
- [ ] 첫 화면에서 `/auth/refresh` 401 콘솔 오류가 보임 (세션 없을 때 정상 동작이지만 노이즈) (Step 7)
- [ ] 녹음 중 네트워크 끊김 시 재연결 로직 없음 (Step 9)
- [ ] 장시간 녹음 시 PCM 용량(시간당 약 115MB) 관리 정책 필요 (Step 9)
- [ ] 운영 전 WebSocket 토큰을 단기 티켓 방식으로 전환할지 (접근 로그 노출) (Step 9)
- [ ] Webhook 실패 시 강의가 ANALYZING 에 멈춤 — 재시도/타임아웃 필요 (Step 10)
- [ ] 배치 작업 진행 상태 조회 수단 없음 (task_id 추적 안 함) (Step 10)
- [ ] 정렬 품질 평가 수단 없음. 전환 패널티(0.05)는 실제 강의 데이터로 조정 필요 (Step 11)
- [ ] 슬라이드 45장이면 LLM 호출도 45번(로컬 7B 약 13분). 병렬화/진행률 표시 검토 (Step 12)
- [ ] 시험 힌트가 잘 안 잡힘 — 프롬프트 조정 여지 (Step 12)
- [ ] 채팅이 이전 질문 맥락을 이어받지 않음 (매 질문 독립) (Step 13)
- [ ] 같은 쪽 발화가 여러 건이면 출처 뱃지가 중복 표시됨 (Step 13)
- [ ] RAG 검색 품질 평가 수단 없음 (top_k=5 고정) (Step 13)
- [ ] Docker Ollama 볼륨(모델 6.6GB) 정리 여부 (Step 12)
