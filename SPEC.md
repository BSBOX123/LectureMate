# **SPEC.md \- 강의 맞춤형 RAG 및 자동 필기 어시스턴트 (LectureMate AI)**

# **1\. Project Overview & Scope**

* **Active Scope:** Fullstack (Spring Boot 메인 백엔드 \+ FastAPI AI 추론 워커 \+ Next.js 웹 프론트엔드 \+ PostgreSQL/pgvector 단일 DB)  
* **Tech Stack:**  
  * **Main Backend:** Java 21, Spring Boot 4.1.x, Spring Data JPA, Spring Security, JWT, Spring WebSocket, Spring RestClient  
  * **AI Engine:** Python 3.11+, FastAPI, PyMuPDF (fitz), Faster-Whisper, PyTorch, vLLM / Ollama (Qwen2.5-14B-Instruct / Llama-3.1-8B), BAAI/bge-m3  
  * **Database:** PostgreSQL 16 \+ pgvector 확장 (관계형 데이터와 벡터 데이터 단일 통합 관리)  
  * **Frontend:** Next.js 16 (App Router), TypeScript, Tailwind CSS, PDF.js, Canvas Overlay (Fabric.js), Web Audio API / MediaRecorder

## **Architecture Overview**

* **Client:** Next.js 기반 웹 앱으로 강의자료(PDF) 뷰어, 실시간 음성 녹음 스트리밍, PDF 위에 교수님 발화 요약 및 하이라이트를 표시하는 인터랙티브 필기 오버레이, RAG 질의응답 챗봇 UI를 제공합니다.  
* **Spring Boot 메인 백엔드:** 회원/강의 메타데이터 CRUD, 파일 업로드/스토리지 관리, 클라이언트 웹소켓 세션 제어, FastAPI와의 내부 통신 오케스트레이션 및 SSE 스트리밍 중계를 담당합니다.  
* **FastAPI AI 워커:** PyMuPDF를 통한 PDF 단어별 Bounding Box 추출, Faster-Whisper 기반 하이브리드 STT, 슬라이드-음성 시간 단조 정렬(DP Alignment), LLM 자동 필기 요약 생성, pgvector 하이브리드 RAG 검색 및 토큰 스트리밍 생성을 전담합니다.  
* **비동기 작업:** 강의 종료 후 정밀 분석은 FastAPI 완료 후 Spring Boot의 Webhook을 호출하여 상태를 동기화합니다.

# **2\. API & Data Contracts**

## **2.1 Spring Boot \<-\> Client API**

1. **강의 생성 및 PDF 업로드**  
   * `POST /api/v1/lectures`  
   * Request: Multipart (title: String, file: MultipartFile)  
   * Response (201 Created):{  
     &nbsp;  
     &nbsp;&nbsp;"lectureId": 101,  
     &nbsp;  
     &nbsp;&nbsp;"title": "컴퓨터 알고리즘 5강",  
     &nbsp;  
     &nbsp;&nbsp;"status": "PROCESSING",  
     &nbsp;  
     &nbsp;&nbsp;"pdfUrl": "/files/pdf/101.pdf"  
     &nbsp;  
     }

   &nbsp;  
2. **실시간 오디오 스트림 수신 (WebSocket)**  
   * `WS /ws/v1/lectures/{lectureId}/audio`  
   * Client \-\> Spring Boot: Binary Audio Chunks (PCM/Opus, 3\~5초 단위)  
   * Spring Boot \-\> Client: JSON 프리뷰 자막 이벤트{  
     &nbsp;  
     &nbsp;&nbsp;"type": "TRANSCRIPT\_PREVIEW",  
     &nbsp;  
     &nbsp;&nbsp;"startTimeMs": 15000,  
     &nbsp;  
     &nbsp;&nbsp;"endTimeMs": 18000,  
     &nbsp;  
     &nbsp;&nbsp;"text": "오늘 다룰 내용은 다익스트라 최단 경로 알고리즘입니다."  
     &nbsp;  
     }

   &nbsp;  
3. **강의 녹음 종료 및 분석 트리거**  
   * `POST /api/v1/lectures/{lectureId}/recording/finish`  
   * Response (202 Accepted):{  
     &nbsp;  
     &nbsp;&nbsp;"lectureId": 101,  
     &nbsp;  
     &nbsp;&nbsp;"status": "ANALYZING",  
     &nbsp;  
     &nbsp;&nbsp;"message": "강의 종료 후 정밀 전사 및 슬라이드 필기 매칭 분석이 시작되었습니다."  
     &nbsp;  
     }

   &nbsp;  
4. **슬라이드 및 자동 필기 데이터 조회**  
   * `GET /api/v1/lectures/{lectureId}/pages/{pageNumber}/annotations`  
   * Response (200 OK):{  
     &nbsp;  
     &nbsp;&nbsp;"pageNumber": 5,  
     &nbsp;  
     &nbsp;&nbsp;"professorSummary": "다익스트라 알고리즘에서 음수 가중치가 존재할 경우 무한 루프 위험성을 강조함 (중간고사 단골 출제)",  
     &nbsp;  
     &nbsp;&nbsp;"examHints": "음수 가중치 그래프는 벨만-포드 알고리즘을 사용해야 함을 명시",  
     &nbsp;  
     &nbsp;&nbsp;"confidenceScore": 0.92,  
     &nbsp;  
     &nbsp;&nbsp;"highlights": \[  
     &nbsp;  
     &nbsp;&nbsp;&nbsp;&nbsp;{ "word": "음수 가중치", "bbox": \[145.2, 310.5, 230.1, 328.0\], "color": "\#FFEB3B" },  
     &nbsp;  
     &nbsp;&nbsp;&nbsp;&nbsp;{ "word": "벨만-포드", "bbox": \[240.0, 310.5, 315.8, 328.0\], "color": "\#FFC107" }  
     &nbsp;  
     &nbsp;&nbsp;\]  
     &nbsp;  
     }

   &nbsp;  
5. **강의 맞춤형 RAG Q\&A (SSE 스트리밍)**  
   * `POST /api/v1/lectures/{lectureId}/chat`  
   * Request: `{ "question": "다익스트라 알고리즘에서 왜 음수 가중치를 쓸 수 없다고 하셨나요?" }`  
   * Response: Server-Sent Events (data: chunk tokens \+ citations)

6. **회원가입**  
   * `POST /api/v1/auth/signup`  
   * Request: `{ "email": "student@example.com", "password": "...", "name": "김학생" }`  
   * 제약: email 형식/중복 불가, password 8자 이상, name 1\~100자  
   * Response (201 Created): `{ "userId": 1, "email": "student@example.com", "name": "김학생" }`  
   * 오류: 409 Conflict (이메일 중복), 400 Bad Request (형식 오류)  
7. **로그인**  
   * `POST /api/v1/auth/login`  
   * Request: `{ "email": "student@example.com", "password": "..." }`  
   * Response (200 OK): `{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 1800 }`  
   * Refresh Token은 응답 본문이 아니라 httpOnly 쿠키(`refreshToken`, Path=/api/v1/auth, SameSite=Lax, 운영 환경 Secure)로 내려간다  
   * 오류: 401 Unauthorized (이메일 또는 비밀번호 불일치)  
8. **Access Token 재발급**  
   * `POST /api/v1/auth/refresh`  
   * Request: 본문 없음. `refreshToken` 쿠키 사용  
   * Response (200 OK): 로그인과 동일 형식. Refresh Token은 회전(rotation)되어 새 쿠키로 교체된다  
   * 오류: 401 Unauthorized (쿠키 없음/만료/이미 폐기됨)  
9. **로그아웃**  
   * `POST /api/v1/auth/logout`  
   * Refresh Token을 폐기하고 쿠키를 만료시킨다  
   * Response (204 No Content)  
10. **내 정보 조회**  
    * `GET /api/v1/users/me`  
    * Request Header: `Authorization: Bearer {accessToken}`  
    * Response (200 OK): `{ "userId": 1, "email": "student@example.com", "name": "김학생" }`

11. **강의 목록 / 메타데이터 조회**  
    * `GET /api/v1/lectures` → `[{ "lectureId": 101, "title": "...", "status": "READY", "pdfUrl": "/files/pdf/101.pdf" }, ...]` (본인 강의만, 최신순)  
    * `GET /api/v1/lectures/{lectureId}` → 위 항목과 동일한 형식. 본인 강의가 아니면 404  
12. **업로드된 PDF 내려받기**  
    * `GET /files/pdf/{lectureId}.pdf`  
    * Request Header: `Authorization: Bearer {accessToken}`. 본인 강의가 아니면 404  
    * Response: `application/pdf`

* **강의 status 전이**  
  * `INITIALIZED` (행 생성) → `PROCESSING` (PDF 저장 완료, FastAPI 파싱 요청) → `READY` (파싱 완료, 녹음/학습 가능)  
  * `READY` → `RECORDING` (녹음 중) → `ANALYZING` (배치 분석 중) → `READY` (§2.2-4 Webhook 수신)  
  * 각 단계 실패 시 `FAILED`

* **인증 규칙**  
  * `/api/v1/auth/**`를 제외한 모든 `/api/v1/**`와 `/ws/v1/**`는 Access Token이 필요하다 (`Authorization: Bearer {accessToken}`)  
  * Access Token은 HS256 서명 JWT, 유효기간 30분, `sub`에 userId. Refresh Token은 14일  
  * 강의 등 사용자 소유 리소스는 토큰의 userId와 `lectures.user_id`가 일치해야 접근 가능 (불일치 시 404 Not Found)

## **2.2 Spring Boot \<-\> FastAPI Internal API**

1. **PDF 슬라이드 텍스트 및 BBox 추출 / 임베딩**  
   * `POST /ai/v1/pdf/parse`  
   * Request: `{ "lecture_id": 101, "pdf_path": "/storage/pdf/101.pdf" }`  
   * Response: `{ "total_pages": 45, "status": "COMPLETED" }`  
2. **전체 강의 오디오 정밀 배치 분석 요청**  
   * `POST /ai/v1/lectures/{lecture_id}/analyze-batch`  
   * Request: `{ "audio_path": "/storage/audio/101.wav" }`  
   * Response (202 Accepted): `{ "task_id": "batch_101_20260918", "status": "QUEUED" }`  
3. **RAG 복합 검색 및 응답 생성**  
   * `POST /ai/v1/rag/query`  
   * Request: `{ "lecture_id": 101, "question": "음수 가중치 관련 교수님 설명", "top_k": 5 }`  
   * Response: Streaming Response (Server-Sent Events)  
4. **배치 완료 통보 Webhook (FastAPI \-\> Spring Boot)**  
   * `POST /internal/v1/lectures/{lecture_id}/analysis-complete`  
   * Request:{  
     &nbsp;  
     &nbsp;&nbsp;"lectureId": 101,  
     &nbsp;  
     &nbsp;&nbsp;"status": "READY",  
     &nbsp;  
     &nbsp;&nbsp;"totalPagesAnalyzed": 45,  
     &nbsp;  
     &nbsp;&nbsp;"matchedTranscriptSegments": 128  
     &nbsp;  
     }

5. **실시간 오디오 청크 STT 프리뷰 (WebSocket, Spring Boot \-\> FastAPI)**  
   * `WS /ai/v1/lectures/{lecture_id}/audio-stream`  
   * 강의 녹음 세션당 1개 연결을 유지 (Spring Boot가 클라이언트 WS 세션 시작 시 연결, 녹음 종료 시 해제)  
   * Spring Boot \-\> FastAPI: 클라이언트에서 받은 Binary Audio Chunks를 그대로 전달 (§2.1-2와 동일 포맷)  
   * FastAPI \-\> Spring Boot: §2.1-2의 `TRANSCRIPT_PREVIEW` JSON 이벤트와 동일한 포맷 (Spring Boot는 클라이언트로 그대로 중계)

* **내부 API 인증:** FastAPI \-\> Spring Boot Webhook(`/internal/v1/**`)은 공유 시크릿 헤더 `X-Internal-Secret`으로 인증한다. 값은 양쪽 환경 변수(`INTERNAL_API_SECRET`)로 주입하며 불일치 시 401을 반환한다. Spring Boot \-\> FastAPI 호출은 내부 네트워크 신뢰를 전제로 한다.

# **3\. Data Model & DB Schema (PostgreSQL 16 \+ pgvector)**

* **스키마 관리:** 아래 스키마는 Flyway 마이그레이션(`server-core/src/main/resources/db/migration/`)이 소유한다. 변경 시 기존 파일을 고치지 말고 새 버전 파일(`V{n}__설명.sql`)을 추가하며, Spring Boot 기동 시 자동 적용된다. Hibernate 는 `ddl-auto: validate` 로 검증만 한다.

CREATE EXTENSION IF NOT EXISTS vector;

&nbsp;

\-- 0\. 회원 정보 (Spring Security \+ JWT 인증 주체)

&nbsp;

CREATE TABLE users (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;email VARCHAR(255) NOT NULL UNIQUE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;password\_hash VARCHAR(255) NOT NULL, \-- BCrypt 해시

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;name VARCHAR(100) NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;updated\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

\-- 0\-1\. Refresh Token (로그아웃/회전 시 폐기하기 위해 서버에 보관)

&nbsp;

CREATE TABLE refresh\_tokens (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;user\_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;token\_hash VARCHAR(255) NOT NULL UNIQUE, \-- 원문 대신 SHA-256 해시 저장

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;expires\_at TIMESTAMP WITH TIME ZONE NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;revoked\_at TIMESTAMP WITH TIME ZONE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

CREATE INDEX idx\_refresh\_tokens\_user ON refresh\_tokens(user\_id);

&nbsp;

\-- 1\. 강의 정보 메타데이터

&nbsp;

CREATE TABLE lectures (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;user\_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;title VARCHAR(255) NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;pdf\_url VARCHAR(500),

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;audio\_url VARCHAR(500),

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;status VARCHAR(50) DEFAULT 'INITIALIZED', \-- INITIALIZED, PROCESSING, RECORDING, ANALYZING, READY, FAILED

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;updated\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

\-- 2\. PDF 슬라이드 페이지 데이터 (텍스트 \+ 단어 단위 Bounding Box 좌표 \+ 임베딩)

&nbsp;

CREATE TABLE lecture\_slides (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;lecture\_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;page\_number INT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;slide\_text TEXT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;layout\_data JSONB NOT NULL, \-- \[{"word": "Dijkstra", "bbox": \[100.2, 150.4, 180.0, 168.2\]}, ...\]

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;embedding vector(1024),     \-- BAAI/bge-m3 임베딩 차원

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

CREATE INDEX idx\_slides\_lecture\_page ON lecture\_slides(lecture\_id, page\_number);

&nbsp;

CREATE INDEX idx\_slides\_vector ON lecture\_slides USING hnsw (embedding vector\_cosine\_ops);

&nbsp;

\-- 3\. 오디오 전사 녹취록 (타임스탬프 \+ 매핑된 슬라이드 번호 \+ 임베딩)

&nbsp;

CREATE TABLE lecture\_transcripts (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;lecture\_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;start\_time\_ms INT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;end\_time\_ms INT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;speaker\_text TEXT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;matched\_slide\_page INT,     \-- 단조 정렬 알고리즘으로 매핑된 슬라이드 번호

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;embedding vector(1024),

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

CREATE INDEX idx\_transcripts\_lecture\_slide ON lecture\_transcripts(lecture\_id, matched\_slide\_page);

&nbsp;

CREATE INDEX idx\_transcripts\_vector ON lecture\_transcripts USING hnsw (embedding vector\_cosine\_ops);

&nbsp;

\-- 4\. 슬라이드별 최종 자동 필기 및 교수님 요약 레이어 (화면 렌더링용)

&nbsp;

CREATE TABLE slide\_annotations (

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;id BIGSERIAL PRIMARY KEY,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;slide\_id BIGINT REFERENCES lecture\_slides(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;lecture\_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;page\_number INT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;professor\_summary TEXT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;exam\_hints TEXT,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;highlight\_bboxes JSONB NOT NULL, \-- \[{"word": "...", "bbox": \[x1, y1, x2, y2\], "color": "\#FFEB3B"}\]

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;confidence\_score FLOAT NOT NULL,

&nbsp;

&nbsp;&nbsp;&nbsp;&nbsp;created\_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()

&nbsp;

);

&nbsp;

CREATE INDEX idx\_annotations\_lecture\_page ON slide\_annotations(lecture\_id, page\_number);

# **4\. Module & Component Breakdown**

## **4.1 Spring Boot Module (`server-core`)**

* **`com.lecturemate.api.controller`**  
  * `LectureController.java`: 강의 생성, PDF 업로드, 메타데이터 조회  
  * `AudioRecordingController.java`: 녹음 종료 및 분석 요청 트리거  
  * `ChatController.java`: RAG 질문 전달 및 SSE 스트리밍 응답 중계  
  * `InternalWebhookController.java`: FastAPI 완료 알림 수신 Webhook  
* **`com.lecturemate.websocket`**  
  * `AudioStreamWebSocketHandler.java`: 브라우저 마이크 오디오 청크 수집 및 실시간 STT 프리뷰 세션 관리  
* **`com.lecturemate.client`**  
  * `FastApiClient.java`: Spring RestClient/WebClient 기반 FastAPI 통신 규격  
* **`com.lecturemate.domain.entity`**  
  * `User.java`, `Lecture.java`, `LectureSlide.java`, `LectureTranscript.java`, `SlideAnnotation.java`  
* **`com.lecturemate.repository`**  
  * `UserRepository.java`, `LectureRepository.java`, `LectureSlideRepository.java`, `SlideAnnotationRepository.java`

## **4.2 FastAPI AI Module (`ai-engine`)**

* **`routers/`**  
  * `pdf.py`: PDF 파싱 및 BBox 추출 라우터  
  * `audio.py`: 실시간 스트림 청크 STT 및 배치 정밀 분석 라우터  
  * `rag.py`: 복합 벡터 검색 및 LLM 질의응답 라우터  
* **`services/`**  
  * `pdf_parser.py`: PyMuPDF(fitz)로 페이지별 텍스트 및 Bounding Box(`rect`) 추출  
  * `stt_service.py`: Faster-Whisper(Large-v3 및 Base 모델) 관리  
  * `alignment_service.py`: 슬라이드 텍스트 임베딩과 타임스탬프 음성 세그먼트 간 시간 단조성 제약(Monotonic Dynamic Programming) 정렬  
  * `annotation_service.py`: 매핑된 발화 기반 LLM 프롬프트 엔지니어링 (핵심 요약, 시험 힌트, 강조 단어 BBox 매칭)  
  * `rag_service.py`: PostgreSQL pgvector 하이브리드 쿼리 (Slide Context \+ Audio Transcript Context) 구성 및 LLM 스트리밍 답변 생성  
* **`core/`**  
  * `config.py`: 환경 변수, DB 커넥션 풀, 모델 가중치 경로 관리  
  * `database.py`: SQLAlchemy 비동기 세션 엔진

## **4.3 Web Frontend Module (`web-client`)**

* `app/lectures/[id]/page.tsx`: 메인 강의 학습 대시보드  
* **`components/`**  
  * `PdfViewer.tsx`: PDF.js 기반 슬라이드 렌더러  
  * `AnnotationOverlay.tsx`: Fabric.js 또는 HTML5 Canvas 기반 하이라이트 및 주석 오버레이 레이어  
  * `AudioRecorder.tsx`: Web Audio API 기반 강의 녹음 컨트롤러 및 실시간 자막 프리뷰  
  * `LectureChatPanel.tsx`: SSE 기반 RAG 어시스턴트 대화창 (답변 출처 슬라이드 번호 뱃지 클릭 시 해당 슬라이드로 이동)  
  * `SlideTimeline.tsx`: 슬라이드별 교수님 발화 분량 및 시험 힌트 유무 인디케이터

# **5\. Environment & Commands**

## **5.1 Environment Variables**

* **Spring Boot (`application.yml`)**  
  * `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/lecturemate`  
  * `FASTAPI_ENGINE_URL=http://localhost:8000`  
  * `STORAGE_LOCAL_PATH=/data/lecturemate/storage` (서버 배포 기준. 로컬 개발 기본값은 `~/lecturemate/storage`)  
  * `JWT_SECRET=...` (HS256 서명 키, 최소 32바이트)  
  * `INTERNAL_API_SECRET=...` (FastAPI Webhook 공유 시크릿)  
  * `WEB_CLIENT_ORIGIN=http://localhost:3000` (CORS 허용 Origin)
* **FastAPI (`.env`)**  
  * `DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/lecturemate`  
  * `SPRING_BOOT_WEBHOOK_URL=http://localhost:8080/internal/v1`  
  * `INTERNAL_API_SECRET=...` (Spring Boot와 동일한 값)  
  * `WHISPER_MODEL_NAME=large-v3`  
  * `EMBEDDING_MODEL_NAME=BAAI/bge-m3`  
  * `LLM_BACKEND_URL=http://localhost:11434/v1` (Ollama/vLLM)  
  * `LLM_MODEL_NAME=qwen2.5:14b-instruct`

## **5.2 Local Development Commands**

1. **Docker Compose (DB 및 Ollama)**  
   * `docker-compose up -d postgres ollama`  
2. **Spring Boot**  
   * `./gradlew bootRun`  
3. **FastAPI**  
   * `source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload`  
4. **Next.js Frontend**  
   * `pnpm install && pnpm dev`

&nbsp;