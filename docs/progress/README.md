# LectureMate AI 진행 기록

`SPEC.md`를 기준으로 단계별 구현 과정을 기록합니다. 각 Step 문서에는 구현한 기능, 코드 설명, 모듈/엔티티 간 관계, 결정 이유, 트러블슈팅이 들어 있습니다.

## 진행 현황

| Step | 내용 | 상태 | 커밋 | 문서 |
|---|---|---|---|---|
| 1 | 인프라(Docker Compose) 및 DB 스키마 | 완료 | `91b445c` | [step-1-infra-db.md](step-1-infra-db.md) |
| 2 | FastAPI `ai-engine` 스캐폴딩 | 완료 | `16bef97` | [step-2-ai-engine.md](step-2-ai-engine.md) |
| 3 | Spring Boot `server-core` 스캐폴딩 | 완료 (검토 대기) | - | [step-3-server-core.md](step-3-server-core.md) |
| 4 | Next.js `web-client` 스캐폴딩 | 대기 | - | - |
| 5 (예정) | 인증 (Spring Security + JWT, SPEC 회원가입/로그인 API 정의) | 대기 | - | - |

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

## 미결 질문

- [x] ~~Spring Boot 버전~~ → 4.1.1로 업그레이드
- [x] ~~Spring Security / JWT 시점~~ → Step 4 이후 별도 인증 단계
- [x] ~~PDF 업로드 최대 크기~~ → 50MB
- [x] ~~로컬 파일 저장 경로~~ → `~/lecturemate/storage`
- [ ] SPEC §4.1에 `LectureTranscriptRepository`가 없음. 필요 여부 (기능 구현 단계에서 결정)
- [ ] 인증 단계: SPEC §2.1에 회원가입과 로그인 API 정의 필요
