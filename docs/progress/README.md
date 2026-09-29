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
| 15 | 데스크톱 실행 앱 (서비스 일괄 시작/중지) | 완료 | `4f7f901` | [step-15-launcher.md](step-15-launcher.md) |
| 16 | 화면 다듬기 (가드·타임라인·삭제·재시도) | 완료 | `695cf2b` | [step-16-ui-polish.md](step-16-ui-polish.md) |
| 17 | LLM 백엔드를 Claude Code 로 교체 | 완료 | `871ad43` | [step-17-claude-code-llm.md](step-17-claude-code-llm.md) |
| 18 | 실제 강의 사용 후 개선 (자막 토글·전사 속도·분석 안내) | 완료 | `5c17930` | [step-18-real-lecture-fixes.md](step-18-real-lecture-fixes.md) |
| 19 | **과목 중심 전환 및 기능 축소** (자동 필기·정렬·실시간 자막 제거) | 완료 | `3b956ff` | [step-19-course-centric.md](step-19-course-centric.md) |
| 20 | 채팅 맥락 이어받기 (후속 질문) | 완료 | `352bc44` | [step-20-chat-context.md](step-20-chat-context.md) |
| 21 | 전사 용어 사전 검토(미도입) + 전사 구간 축소 + 옛 테이블 정리 + 실사용 버그 수정 | 완료 | `99648b4` | [step-21-glossary-and-cleanup.md](step-21-glossary-and-cleanup.md) |
| 22 | 녹음 요약 (채팅으로 답할 수 없는 집계 질문) | 완료 | `cc61b2a` | [step-22-recording-summary.md](step-22-recording-summary.md) |
| 23 | 전사 청킹 (검색 단위 재설계) | 완료 | `42655b4` | [step-23-chunking.md](step-23-chunking.md) |
| 24 | 마이크 입력 레벨 표시와 무음 경고 | 완료 (검토 대기) | - | [step-24-mic-level.md](step-24-mic-level.md) |

> GitHub: [BSBOX123/LectureMate](https://github.com/BSBOX123/LectureMate) — main 푸시 완료, CI 3개 잡 통과

**Step 19 에서 구조를 과목 중심으로 바꾸고 범위를 줄였습니다.** 과목 폴더 하나에 PDF 여러 개와 녹음 여러 개를 모아 두고, 질문하면 그 과목 전체를 검색해 자료 내용과 교수님 말씀을 구분해 답합니다. 자동 필기·슬라이드 정렬·실시간 자막은 추후 발전 과제로 남겼습니다. 배포 절차는 [deploy.md](../deploy.md) 참고.

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
| 2026-09-20 | 로그인 가드는 `/lectures` 레이아웃 한 곳에 | 페이지마다 검사하면 새 화면에서 빠뜨리기 쉬움 | Step 16 |
| 2026-09-20 | SPEC §2.1-12/13/14 신설 (타임라인·삭제·재시도) | 화면에 필요한데 명세에 없던 API | Step 16 |
| 2026-09-20 | 삭제 시 하위 데이터는 DB CASCADE 에 맡김 | 스키마에 이미 있는 제약. 애플리케이션 중복 삭제 불필요 | Step 16 |
| 2026-09-20 | **LLM 을 Claude Code(headless)로 교체**, `LLM_PROVIDER` 로 Ollama 전환 가능 | 7B 품질 한계. 로컬 전용이라 구독 사용 가능. RAG·벡터 DB·citations 는 그대로 유지 | Step 17 |
| 2026-09-21 | 실시간 자막 **기본 꺼짐** (`preview` 파라미터) | 실측 60초 음성에 40초 소요 → 녹음 내내 CPU 점유(발열). 자막 품질도 낮고, 꺼도 정밀 분석 품질은 동일 | Step 18 |
| 2026-09-21 | STT 백엔드 **mlx-whisper(Apple GPU)** 추가, 기본값 | 1분 음성: CPU 208초 → GPU 16초 (약 13배). 36분 강의 127분 → 15분 | Step 18 |
| 2026-09-21 | 배치 전사를 5분 단위로 분할 + `condition_on_previous_text=False` | 통째로 넘기면 Whisper 반복 루프로 4.5시간 후에도 미완료 | Step 18 |
| 2026-09-23 | **구조를 강의 중심 → 과목 중심으로 전환** | 메인 가치는 RAG 학습 도우미. 과목 폴더에 PDF N개·녹음 N개를 모아 한 채팅으로 전체 검색 | Step 19 |
| 2026-09-23 | 하위 테이블에 `course_id` 비정규화 | 검색이 항상 과목 단위. `WHERE course_id = X` 한 줄로 모든 자료·녹음을 훑는다 | Step 19 |
| 2026-09-23 | **자동 필기·슬라이드 정렬·실시간 자막 제거** (발전 과제) | 사용자 결정. 하드웨어 부담이 크고 부차적. 128쪽이면 LLM 호출 128번 | Step 19 |
| 2026-09-23 | `layout_data`(단어 bbox) 컬럼은 유지 | 파싱 시 거의 공짜. 자동 필기 복원 시 169쪽 재파싱을 피한다 | Step 19 |
| 2026-09-23 | 새 `id` 를 기존 `lectures.id` 와 동일하게 이관 | 파일명이 id 기반이라 파일을 한 개도 옮기지 않아도 된다 | Step 19 |
| 2026-09-23 | 옛 테이블(`lectures` 등) 삭제 보류 | 새 구조가 실사용에서 검증된 뒤 정리한다 | Step 19 |
| 2026-09-25 | **녹음 종료 시 전사 자동 시작** | "분석하는 방법을 모르겠다"는 Step 18 문제. WAV 생성 후 요청하므로 순서 보장 | Step 19 |
| 2026-09-25 | **LLM 모델 `opus` → `sonnet`** | 실측 3회: opus 7.5~10.0초, haiku 6.6~7.0초, sonnet 4.8~5.1초. 가장 낮은 haiku 가 가장 빠르지 않다 | Step 19 |
| 2026-09-25 | 출처에 자료·녹음 이름 포함 (`MATERIAL`/`RECORDING`) | 자료가 여러 개라 "5쪽"만으로는 어느 자료인지 알 수 없다 | Step 19 |
| 2026-09-25 | 대화 맥락은 **서버에 저장하지 않고 클라이언트가 매번 전송** | 세션 테이블은 저장·만료·정리가 따라온다. 필요한 건 직전 몇 마디뿐이고 화면이 이미 들고 있다 | Step 20 |
| 2026-09-25 | 후속 질문은 **직전 질문을 검색어에 붙여** 임베딩 (LLM 재작성 미도입) | 재작성은 호출이 늘어 느려진다. 실측상 11쪽(정답 자료)이 맥락 있을 때만 검색됐다 | Step 20 |
| 2026-09-25 | **전사 용어 사전 미도입** (`STT_GLOSSARY_ENABLED=false`, 코드는 유지) | 7회 실측: 사전 없으면 "외래키·주키" 정답, 사전 4종 전부 "외의 key·주 key" 오류. 영어 제거 가설도 반증됨 | Step 21 |
| 2026-09-25 | **`BATCH_WINDOW_SECONDS` 300 → 120** | 같은 측정에서 165초 → 112초, 쓰레기 구간 최장 118자 → 60자. 구간을 짧게 끊으면 피해가 그 구간에서 멈춘다 | Step 21 |
| 2026-09-25 | **옛 테이블 4개 삭제 (V5)** | 임베딩 값까지 동일(코사인 거리 1e-9 이내) 확인 후 삭제. `~/lecturemate/backup/` 에 2.7MB 백업 | Step 21 |
| 2026-09-28 | **답변을 3~5문장으로 제한** | 지연의 96%가 LLM 생성. 722자 → 333자로 9.4초 → 6.0초. 읽기도 더 낫다 | Step 21 |
| 2026-09-28 | **임베딩 모델 기동 시 워밍업** | 첫 질문만 20.7초였다(bge-m3 로딩 13~16초). 백그라운드 로딩으로 7.1초 | Step 21 |
| 2026-09-28 | **401 이면 토큰 재발급 후 재시도** | Access Token 30분 < 수업 72분. 종료 직후 모든 요청이 실패했다 | Step 21 |
| 2026-09-28 | **전사 자동 시작 철회 → "전사 시작" 버튼** (Step 19 결정 되돌림) | 실측 음성 1분당 34초(75분 수업 = 42분). 수업 직후 뚜껑 닫고 이동하면 끝까지 못 돈다. "방법을 모르겠다"는 문제는 자동화가 아니라 보이는 버튼으로 풀 일이었다 | Step 21 |
| 2026-09-28 | **녹음 요약을 별도 기능으로** (채팅 RAG 와 분리) | "강조한 부분" 은 주제가 아니라 임베딩이 매칭할 대상이 없고 top_k=5 로는 71분을 요약할 수 없다. 검색 질문이 아니라 집계 질문 | Step 22 |
| 2026-09-28 | 요약은 전사 전체를 **LLM 1회** 호출 | 71분이 24,924자(약 8천 토큰)로 한 번에 들어간다. 슬라이드마다 호출해 128번이던 자동 필기와 달리 1회 | Step 22 |
| 2026-09-29 | **녹음 중 입력 레벨 표시 + 무음 10초 경고** | 61분 수업이 통째로 무음으로 녹음됐다(아이폰 연속성 마이크가 끊김). 파일은 정상이라 아무도 몰랐다. 진폭 계산은 모델이 필요 없어 발열과 무관하다 | Step 24 |
| 2026-09-29 | **검색 단위를 Whisper 세그먼트 → 약 300자 덩어리로** | 세그먼트 평균이 21자(10자 미만이 36.5%)라 임베딩할 의미가 없었다. "박준오" 같은 출석 호명이 각각 한 행 | Step 23 |
| 2026-09-29 | 원본 세그먼트는 남기고 덩어리를 **별도 테이블**로 | 전사에 42분이 든다. 파라미터를 바꿔 다시 묶으려면 원본이 필요하고, 요약은 정밀한 타임스탬프를 쓴다 | Step 23 |
| 2026-09-28 | 녹음 19 제목 `데이터베이스 3장` → `9월 21일 수업` | 자료 제목과 같아 모델이 어느 쪽 출처인지 판단하지 못했다 (V3 마이그레이션 흔적) | Step 22 |
| 2026-09-28 | **필기 OCR 도입하지 않음.** 필기는 사용자가 직접 보며 채팅한다 | 사용자 결정. iPad 가 심어 준 텍스트 층은 이미 읽히지만 품질이 낮아("model"→"mohel") 검색에 유의미한 기여를 못 한다. 쪽 이미지 OCR 은 파싱을 느리게 하고 한국어 손글씨 품질도 불확실하다 | Step 21 |

## 미결 질문

- [x] ~~Spring Boot 버전~~ → 4.1.1로 업그레이드
- [x] ~~Spring Security / JWT 시점~~ → Step 4 이후 별도 인증 단계
- [x] ~~PDF 업로드 최대 크기~~ → 50MB
- [x] ~~로컬 파일 저장 경로~~ → `~/lecturemate/storage`
- [ ] SPEC §4.1에 `LectureTranscriptRepository`가 없음. 필요 여부 (기능 구현 단계에서 결정)
- [x] ~~인증 API SPEC 정의~~ → §2.1-6 ~ §2.1-10 추가 완료
- [x] ~~원본 파일 저장 전략~~ → 현행 유지, 앱 확장 시 D안 재검토
- [x] ~~`SlideTimeline` 데이터 API~~ → SPEC §2.1-12 신설 및 구현 (Step 16)
- [x] ~~§2.1-5 채팅 SSE 이벤트 형식~~ → citations/token/done 정의 및 구현 (Step 13)
- [x] ~~프론트엔드 API 주소 환경 변수와 CORS~~ → `NEXT_PUBLIC_API_BASE_URL`, `WEB_CLIENT_ORIGIN` (Step 5)
- [x] ~~강의 목록과 PDF 업로드 화면~~ → `/lectures`, `/lectures/new` 추가, SPEC §2.1-11·12 반영 (Step 6)
- [x] ~~`/ws/v1/**` WebSocket 인증 방식~~ → 쿼리 파라미터 토큰 + 핸들러 검증 (Step 9)
- [x] ~~로그인 라우트 가드~~ → `/lectures` 레이아웃에서 처리 (Step 16)
- [x] ~~`ai-engine`에 `INTERNAL_API_SECRET` 반영~~ → Webhook 호출에 적용 (Step 10)
- [x] ~~DB 스키마 변경 방식~~ → Flyway 도입 완료 (Step 8)
- [x] ~~`PdfViewer` PDF 렌더링~~ → PDF.js 적용 완료 (Step 7)
- [x] ~~강의 삭제 API~~ → SPEC §2.1-13 신설, 파일까지 삭제 (Step 16)
- [x] ~~실패 시 재시도~~ → SPEC §2.1-14 신설, 실패 지점에 따라 자동 분기 (Step 16)
- [ ] 브라우저 e2e 스크립트를 저장소에 포함할지 (스크래치패드가 정리되어 현재는 없음) (Step 7)
- [x] ~~배포 설계~~ → GPU EC2 1대 구성, Dockerfile/compose/Nginx/CI 준비 완료 (Step 14)
- [ ] EC2 에서 `ai-engine` CUDA 이미지 첫 빌드·GPU 인식 검증 (GPU 인스턴스 확보 후) (Step 14)
- [ ] (보류) EC2 는 삭제됨. 외부 공개가 필요해지면 다시 생성:: EC2 t3.small 이상 + 탄력적 IP + 80/443 개방 + Mac 역터널, 또는 GPU 인스턴스 (Step 14)
- [x] ~~Whisper Metal 백엔드~~ → mlx-whisper 도입, 13배 개선 (Step 18)
- [ ] CD(자동 배포), 모니터링/로그 수집, 서비스 헬스체크 (Step 14)
- [ ] 첫 화면에서 `/auth/refresh` 401 콘솔 오류가 보임 (세션 없을 때 정상 동작이지만 노이즈) (Step 7)
- [ ] 녹음 중 네트워크 끊김 시 재연결 로직 없음 (Step 9)
- [ ] 장시간 녹음 시 PCM 용량(시간당 약 115MB) 관리 정책 필요 (Step 9)
- [ ] 운영 전 WebSocket 토큰을 단기 티켓 방식으로 전환할지 (접근 로그 노출) (Step 9)
- [ ] Webhook 실패 시 강의가 ANALYZING 에 멈춤 — 재시도/타임아웃 필요 (Step 10)
- [ ] 배치 작업 진행 상태 조회 수단 없음 (task_id 추적 안 함). 진행률은 서버 로그에만 남는다 (Step 10)
- [ ] 전사 중 발열이 크다 (42분간 GPU 점유). 더 작은 Whisper 모델(medium) 비교 미측정 (Step 21)
- [ ] 채팅이 질문마다 `claude` CLI 를 새로 띄운다. 상주 세션으로 바꾸면 6~9초 → 3.7초, 캐시 생성 토큰 22,266 → 2,031 (측정 완료, 미적용) (Step 21)
- [x] ~~정렬 품질 평가 수단~~ → 정렬 기능 자체를 제거 (Step 19)
- [x] ~~슬라이드 45장이면 LLM 호출도 45번~~ → 자동 필기 제거로 해소 (Step 19)
- [x] ~~시험 힌트 확인~~ → 자동 필기 제거. 교수님 강조는 RAG 답변으로 확인한다 (Step 19)
- [ ] Claude Code 프로세스 시작 2~3초 — 세션 재사용(`--resume`)으로 단축 검토 (Step 17)
- [x] ~~슬라이드 45장 = LLM 45회 호출~~ → 자동 필기 제거로 해소 (Step 19)
- [x] ~~전사 전문 용어 오인식. initial_prompt 로 용어 사전 제공 검토~~ → 만들어서 7회 측정했으나 오히려 악화. 미도입 (Step 21)
- [x] ~~채팅이 이전 질문 맥락을 이어받지 않음~~ → `history` 전달 + 검색어에 직전 질문 결합 (Step 20)
- [ ] 같은 쪽 발화가 여러 건이면 출처 뱃지가 중복 표시됨 (Step 13)
- [ ] RAG 검색 품질 평가 수단 없음 (top_k=5 고정) (Step 13)
- [ ] Docker Ollama 볼륨(모델 6.6GB) 정리 여부 (Step 12)
- [x] ~~녹음 19번 전사 미실행~~ → 실행 완료. 438세그먼트, 36분, 상태 READY (Step 21)
- [x] ~~옛 테이블 4개 정리~~ → V5 에서 삭제, 백업 보관 (Step 21)
- [ ] 자료를 다른 과목으로 옮기는 기능 없음 (V4 처럼 SQL 로 해결해야 함) (Step 19)
- [ ] 자동 필기·슬라이드 정렬 복원 (발전 과제). `layout_data` 는 남겨 뒀다 (Step 19)
- [ ] 대화가 길어지면 4마디 밖의 맥락은 사라진다. 새로 고치면 대화가 사라진다 (Step 20)
- [ ] LLM 질문 재작성(query rewriting) 미도입. 검색 품질 평가 수단이 생기면 비교 검토 (Step 20)
- [ ] 요약 생성 중 39초간 화면이 멈춘다. 스트리밍 표시 여지 (Step 22)
- [ ] 여러 녹음을 걸친 요약("지난 3주 정리") 없음 (Step 22)
- [ ] 요약을 검색 대상에 넣지 않았다. 넣으면 채팅이 요약을 근거로 쓸 수 있다 (Step 22)
- [ ] 자료 페이지도 쪽 단위(최대 999자)다. 큰 쪽은 더 쪼갤 여지 (Step 23)
- [ ] 임베딩에 맥락(자료 이름·쪽수)을 붙이지 않는다 (Step 23)
- [ ] 청킹 파라미터(300자/60자)를 검색 품질로 평가할 수단이 없다 (Step 23)
- [ ] 서버 쪽 무음 감지 없음 (브라우저 표시만) (Step 24)
- [ ] 입력 장치를 화면에서 고를 수 없다. Chrome 설정으로 가야 한다 (Step 24)
- [ ] 브라우저에서 실제 녹음으로 레벨 표시를 확인하지 못했다 (헤드리스 Chrome 이 AudioContext 에서 멈춤) (Step 24)
