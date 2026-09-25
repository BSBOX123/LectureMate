# **SPEC.md - 과목 단위 RAG 학습 도우미 (LectureMate AI)**

# **1. Project Overview & Scope**

* **한 줄 정의:** 과목 폴더에 강의 자료(PDF)와 수업 녹음을 모아 두면, 공부하다 질문할 때 그 과목의 자료와 교수님 말씀을 함께 찾아 답한다.
* **Active Scope:** Fullstack (Spring Boot 메인 백엔드 + FastAPI AI 추론 워커 + Next.js 웹 프론트엔드 + PostgreSQL/pgvector 단일 DB)
* **Tech Stack:**
  * **Main Backend:** Java 21, Spring Boot 4.1.x, Spring Data JPA, Spring Security, JWT, Spring WebSocket, Spring RestClient
  * **AI Engine:** Python 3.11+, FastAPI, PyMuPDF (fitz), mlx-whisper / Faster-Whisper, BAAI/bge-m3, Claude Code CLI(headless) 또는 Ollama
  * **Database:** PostgreSQL 16 + pgvector 확장 (관계형 데이터와 벡터 데이터 단일 통합 관리)
  * **Frontend:** Next.js 16 (App Router), TypeScript, Tailwind CSS, PDF.js, Web Audio API

## **핵심 개념**

**과목(course)** 하나가 **자료(material, PDF N개)** 와 **녹음(recording, N개)** 을 담는 폴더다. 질의응답은 과목 단위로 이뤄지고, 그 안의 모든 자료와 모든 녹음이 한 번의 질문으로 함께 검색된다. 녹음은 특정 PDF 에 묶이지 않는다.

```
courses (과목)
  ├─ course_materials  (PDF N개)   ─ material_pages     (페이지 텍스트 + 임베딩)
  ├─ course_recordings (녹음 N개)  ─ recording_segments (전사 + 임베딩)
  └─ 채팅 1개 ─ 과목 전체를 검색 (WHERE course_id = X)
```

## **Architecture Overview**

* **Client:** Next.js 기반 웹 앱으로 과목 목록, 과목 안의 자료·녹음 관리, PDF 뷰어, 음성 녹음, 과목 단위 RAG 질의응답 UI를 제공한다.
* **Spring Boot 메인 백엔드:** 회원 인증, 과목/자료/녹음 CRUD, 파일 업로드·스토리지 관리, 녹음 WebSocket 세션 제어, FastAPI 내부 통신 오케스트레이션 및 SSE 스트리밍 중계를 담당한다.
* **FastAPI AI 워커:** PyMuPDF 로 PDF 페이지별 텍스트·Bounding Box 추출, Whisper 정밀 전사, bge-m3 임베딩, pgvector 하이브리드 RAG 검색 및 LLM 토큰 스트리밍 생성을 전담한다.
* **비동기 작업:** 녹음 전사는 오래 걸리므로 FastAPI 가 끝난 뒤 Spring Boot 의 Webhook 을 호출해 상태를 동기화한다.

## **범위에서 뺀 것 (추후 발전 과제)**

아래는 한 번 구현했다가 **의도적으로 제거**한 기능이다. 메인 가치는 RAG 학습 도우미이고, 아래는 부차적인데 비용이 컸다.

| 기능 | 뺀 이유 |
|---|---|
| 슬라이드-발화 단조 정렬 (Monotonic DP) | 녹음이 특정 PDF 에 묶이지 않게 되어 "이 발화가 몇 번 슬라이드냐"의 기준이 없어졌다 |
| 자동 필기 생성 (슬라이드별 LLM 요약·시험 힌트) | 128쪽 자료면 LLM 호출도 128번. 시간과 사용량 부담이 크다 |
| PDF 위 하이라이트 오버레이, 슬라이드 타임라인 | 자동 필기에 딸린 화면이다 |
| 실시간 자막 (녹음 중 Whisper) | 실측 60초 음성에 40초 소요 → 녹음 내내 CPU 점유(발열). 한국어 품질도 쓸 수 없는 수준이었다 |

단어별 bbox(`material_pages.layout_data`)는 계속 저장한다. 파싱할 때 거의 비용 없이 얻어지고, 자동 필기를 되살릴 때 전체 재파싱을 피할 수 있다.

# **2. API & Data Contracts**

## **2.1 Spring Boot <-> Client API**

### 과목

1. **과목 만들기**
   * `POST /api/v1/courses`
   * Request: `{ "title": "데이터베이스" }` (1~255자)
   * Response (201 Created): `{ "courseId": 17, "title": "데이터베이스" }`
2. **과목 목록 / 조회**
   * `GET /api/v1/courses` → `[{ "courseId": 17, "title": "데이터베이스" }, ...]` (본인 과목만, 최신순)
   * `GET /api/v1/courses/{courseId}` → 위와 동일한 형식. 본인 과목이 아니면 404
   * `PATCH /api/v1/courses/{courseId}` → 이름 변경. Request/Response 는 §2.1-1 과 동일
3. **과목 삭제**
   * `DELETE /api/v1/courses/{courseId}` → 204 No Content
   * 과목과 안의 자료·녹음, 하위 데이터(페이지/전사)를 지우고 저장된 PDF·WAV 파일도 지운다

### 자료 (PDF)

4. **자료 업로드**
   * `POST /api/v1/courses/{courseId}/materials`
   * Request: Multipart (`title`: String, `file`: MultipartFile) — PDF 만, 최대 50MB
   * Response (201 Created):
     ```json
     { "materialId": 19, "title": "2장 관계형 모델", "status": "PROCESSING",
       "pdfUrl": "/files/pdf/19.pdf", "totalPages": null }
     ```
   * 파싱이 끝나면 `status=READY`, `totalPages` 가 채워진다
5. **자료 목록 / 삭제**
   * `GET /api/v1/courses/{courseId}/materials` → 위 형식의 배열 (올린 순서)
   * `DELETE /api/v1/courses/{courseId}/materials/{materialId}` → 204 No Content (PDF 파일까지 삭제)
6. **자료 파싱 재시도**
   * `POST /api/v1/courses/{courseId}/materials/{materialId}/retry` → 202 Accepted, §2.1-4 와 동일한 응답
   * `status=FAILED` 일 때만 허용 (아니면 409)
   * **업로드된 PDF 내려받기:** `GET /files/pdf/{materialId}.pdf`
     * Request Header: `Authorization: Bearer {accessToken}`. 본인 자료가 아니면 404
     * Response: `application/pdf`

### 녹음

7. **녹음 만들기**
   * `POST /api/v1/courses/{courseId}/recordings`
   * Request: `{ "title": "10월 2일 수업" }`
   * Response (201 Created):
     ```json
     { "recordingId": 21, "title": "10월 2일 수업", "status": "CREATED",
       "audioUrl": null, "durationMs": null }
     ```
   * 클라이언트는 받은 `recordingId` 로 §2.1-8 WebSocket 을 연다
8. **오디오 스트림 수신 (WebSocket)**
   * `WS /ws/v1/recordings/{recordingId}/audio?token={accessToken}`
   * 인증: 브라우저는 WebSocket 요청에 헤더를 붙일 수 없으므로 Access Token 을 쿼리 파라미터로 전달한다. 토큰이 없거나 소유자가 아니면 1008(Policy Violation)로 종료한다
   * Client -> Spring Boot: Binary Audio Chunks — **16kHz 모노 16bit LE PCM**, 3~5초 단위
   * 수신한 PCM 은 서버가 누적해 연결 종료 시 `{STORAGE_LOCAL_PATH}/audio/{recordingId}.wav` 로 저장하고 `course_recordings.audio_url` 을 기록한다
   * **녹음 중에는 어떤 모델도 돌리지 않는다.** 서버 → 클라이언트 메시지는 없다
   * 연결이 끝나면 WAV 를 만든 **직후 전사를 자동으로 시작**한다 (사용자가 버튼을 누르지 않는다)
9. **녹음 목록 / 삭제**
   * `GET /api/v1/courses/{courseId}/recordings` → §2.1-7 형식의 배열 (최신순)
   * `DELETE /api/v1/courses/{courseId}/recordings/{recordingId}` → 204 No Content (WAV 파일까지 삭제)
10. **전사 재시도**
    * `POST /api/v1/courses/{courseId}/recordings/{recordingId}/retry` → 202 Accepted, §2.1-7 과 동일한 응답
    * `status` 가 `FAILED` 또는 `UPLOADED` 일 때만 허용 (아니면 409)

### 질의응답

11. **과목 단위 RAG Q&A (SSE 스트리밍)**
    * `POST /api/v1/courses/{courseId}/chat`
    * Request:
      ```jsonc
      { "question": "그거 시험에 나와?",
        // 후속 질문의 맥락. 서버는 대화를 저장하지 않고 클라이언트가 최근 몇 마디를 매번 보낸다.
        // role 은 "user" | "assistant", 최대 20마디. 서버는 뒤에서 4마디만 쓴다.
        "history": [ { "role": "user", "text": "외래 키가 뭐야?" },
                     { "role": "assistant", "text": "다른 릴레이션의 기본 키를 참조하는 속성이다." } ] }
      ```
      `question` 1~2000자, `history` 는 생략 가능
    * Response: `text/event-stream`. 이벤트 순서는 citations → token(여러 번) → done 이며, FastAPI(§2.2-3)가 보내는 형식을 Spring Boot 가 그대로 중계한다
      * `event: citations` / `data: { "citations": [ ... ] }` — 답변 생성 전에 먼저 보내 근거 뱃지를 즉시 표시할 수 있게 한다. 근거 한 건은 출처에 따라 모양이 다르다:
        ```json
        { "source": "MATERIAL", "snippet": "...", "materialId": 19,
          "materialTitle": "2장 SQL", "pageNumber": 14 }
        { "source": "RECORDING", "snippet": "...", "recordingId": 21,
          "recordingTitle": "10월 2일 수업", "startTimeMs": 1043880 }
        ```
        (쓰이지 않는 필드는 `null`. 자료 근거는 뱃지를 누르면 그 자료의 해당 쪽으로 이동한다)
      * `event: token` / `data: { "text": "외래" }` — 생성되는 토큰 조각
      * `event: done` / `data: { "finishReason": "stop" | "error" }`

### 인증

12. **회원가입**
    * `POST /api/v1/auth/signup`
    * Request: `{ "email": "student@example.com", "password": "...", "name": "김학생" }`
    * 제약: email 형식/중복 불가, password 8자 이상, name 1~100자
    * Response (201 Created): `{ "userId": 1, "email": "student@example.com", "name": "김학생" }`
    * 오류: 409 Conflict (이메일 중복), 400 Bad Request (형식 오류)
13. **로그인**
    * `POST /api/v1/auth/login`
    * Request: `{ "email": "student@example.com", "password": "..." }`
    * Response (200 OK): `{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 1800 }`
    * Refresh Token은 응답 본문이 아니라 httpOnly 쿠키(`refreshToken`, Path=/api/v1/auth, SameSite=Lax, 운영 환경 Secure)로 내려간다
    * 오류: 401 Unauthorized (이메일 또는 비밀번호 불일치)
14. **Access Token 재발급**
    * `POST /api/v1/auth/refresh`
    * Request: 본문 없음. `refreshToken` 쿠키 사용
    * Response (200 OK): 로그인과 동일 형식. Refresh Token은 회전(rotation)되어 새 쿠키로 교체된다
    * 오류: 401 Unauthorized (쿠키 없음/만료/이미 폐기됨)
15. **로그아웃**
    * `POST /api/v1/auth/logout` → 204 No Content. Refresh Token을 폐기하고 쿠키를 만료시킨다
16. **내 정보 조회**
    * `GET /api/v1/users/me`
    * Request Header: `Authorization: Bearer {accessToken}`
    * Response (200 OK): `{ "userId": 1, "email": "student@example.com", "name": "김학생" }`

### 상태 전이

* **자료 (`course_materials.status`)**
  * `PROCESSING` (업로드 완료, FastAPI 파싱 요청) → `READY` (페이지·임베딩 적재 완료, 검색 가능)
  * 실패 시 `FAILED` → §2.1-6 으로 재시도
* **녹음 (`course_recordings.status`)**
  * `CREATED` (행 생성) → `RECORDING` (WebSocket 연결) → `UPLOADED` (WAV 저장 완료)
    → `ANALYZING` (전사 중) → `READY` (§2.2-4 Webhook 수신, 검색 가능)
  * 실패 시 `FAILED` → §2.1-10 으로 재시도

### 인증 규칙

* `/api/v1/auth/**`를 제외한 모든 `/api/v1/**`와 `/ws/v1/**`는 Access Token이 필요하다 (`Authorization: Bearer {accessToken}`)
* Access Token은 HS256 서명 JWT, 유효기간 30분, `sub`에 userId. Refresh Token은 14일
* 사용자 소유 리소스는 토큰의 userId와 `courses.user_id`가 일치해야 접근 가능 (불일치 시 404 Not Found). 자료·녹음은 속한 과목을 거쳐 소유자를 확인한다

## **2.2 Spring Boot <-> FastAPI Internal API**

1. **PDF 페이지 텍스트 및 BBox 추출 / 임베딩**
   * `POST /ai/v1/materials/parse`
   * Request: `{ "material_id": 19, "course_id": 17, "pdf_path": "/storage/pdf/19.pdf" }`
   * Response: `{ "total_pages": 128, "status": "COMPLETED" }`
   * 검색이 과목 단위이므로 페이지 행에도 `course_id` 를 함께 저장한다. 같은 자료를 다시 파싱하면 기존 페이지 행을 교체한다
2. **녹음 정밀 전사**
   * `POST /ai/v1/recordings/{recording_id}/transcribe`
   * Request: `{ "course_id": 17, "audio_path": "/storage/audio/21.wav" }`
   * Response (202 Accepted): `{ "task_id": "transcribe_21_20260925", "status": "QUEUED" }`
   * 전사 → 임베딩 → `recording_segments` 적재 → §2.2-4 Webhook 통보
3. **과목 단위 RAG 검색 및 응답 생성**
   * `POST /ai/v1/rag/query`
   * Request: `{ "course_id": 17, "question": "...", "top_k": 5, "history": [ ... ] }` (`top_k` 생략 시 5, `history` 는 §2.1-11 과 같은 형식)
   * Response: `text/event-stream`. §2.1-11 과 동일한 citations / token / done 이벤트
   * 검색은 pgvector 코사인 거리로 `material_pages` 와 `recording_segments` 를 **과목 전체 범위에서** 각각 조회해 합친다 (하이브리드 컨텍스트). 출처 이름을 붙이기 위해 `course_materials` / `course_recordings` 와 조인한다
   * **후속 질문 처리:** "그거 시험에 나와?" 는 그 자체로 검색어가 되지 못한다. `history` 가 있으면 **직전 사용자 질문을 앞에 붙여** 임베딩한다. LLM 으로 질문을 재작성하면 더 정확하겠지만 호출이 한 번 늘어 답변이 느려지므로, 빠른 응답을 우선해 이 방식을 쓴다
4. **전사 완료 통보 Webhook (FastAPI -> Spring Boot)**
   * `POST /internal/v1/recordings/{recording_id}/transcription-complete`
   * Request:
     ```json
     { "recordingId": 21, "status": "READY", "segmentCount": 436, "durationMs": 2188460 }
     ```
   * `status` 가 `READY` 면 녹음을 검색 가능 상태로, 아니면 `FAILED` 로 바꾼다. `durationMs` 는 마지막 세그먼트의 끝 시각이다

* **내부 API 인증:** FastAPI -> Spring Boot Webhook(`/internal/v1/**`)은 공유 시크릿 헤더 `X-Internal-Secret`으로 인증한다. 값은 양쪽 환경 변수(`INTERNAL_API_SECRET`)로 주입하며 불일치 시 401을 반환한다. Spring Boot -> FastAPI 호출은 내부 네트워크 신뢰를 전제로 한다.

# **3. Data Model & DB Schema (PostgreSQL 16 + pgvector)**

* **스키마 관리:** 아래 스키마는 Flyway 마이그레이션(`server-core/src/main/resources/db/migration/`)이 소유한다. 변경 시 기존 파일을 고치지 말고 새 버전 파일(`V{n}__설명.sql`)을 추가하며, Spring Boot 기동 시 자동 적용된다. Hibernate 는 `ddl-auto: validate` 로 검증만 한다.
* **이력:** `V1`~`V2` 는 강의(lecture) 중심 초기 스키마, `V3` 이 과목 중심으로 전환하며 기존 데이터를 이관했고, `V4` 는 갈라진 과목을 합친 1회성 정리다. 옛 테이블(`lectures`, `lecture_slides`, `lecture_transcripts`, `slide_annotations`)은 아직 지우지 않고 남겨 두었다.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- 0. 회원 정보 (Spring Security + JWT 인증 주체)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,  -- BCrypt 해시
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 0-1. Refresh Token (로그아웃/회전 시 폐기하기 위해 서버에 보관)
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,  -- 원문 대신 SHA-256 해시 저장
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);

-- 1. 과목 (자료와 녹음을 담는 폴더, 질의응답의 단위)
CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_courses_user ON courses(user_id);

-- 2. 과목에 속한 PDF 자료 (여러 개)
CREATE TABLE course_materials (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    pdf_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PROCESSING',  -- PROCESSING, READY, FAILED
    total_pages INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_materials_course ON course_materials(course_id);

-- 3. 자료의 페이지별 텍스트 + 단어 단위 BBox + 임베딩
--    course_id 는 과목 단위 검색을 위한 비정규화 컬럼이다
CREATE TABLE material_pages (
    id BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL REFERENCES course_materials(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    page_number INT NOT NULL,
    page_text TEXT NOT NULL,
    layout_data JSONB NOT NULL,  -- [{"word": "Dijkstra", "bbox": [100.2, 150.4, 180.0, 168.2]}, ...]
    embedding vector(1024),      -- BAAI/bge-m3 임베딩 차원
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_material_pages_material ON material_pages(material_id, page_number);
CREATE INDEX idx_material_pages_course ON material_pages(course_id);
CREATE INDEX idx_material_pages_vector ON material_pages USING hnsw (embedding vector_cosine_ops);

-- 4. 과목에 속한 녹음 (여러 개, 특정 자료에 묶이지 않는다)
CREATE TABLE course_recordings (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    audio_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'RECORDING',
    -- CREATED, RECORDING, UPLOADED, ANALYZING, READY, FAILED
    duration_ms INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_recordings_course ON course_recordings(course_id);

-- 5. 녹음 전사 세그먼트 + 임베딩
CREATE TABLE recording_segments (
    id BIGSERIAL PRIMARY KEY,
    recording_id BIGINT NOT NULL REFERENCES course_recordings(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    start_time_ms INT NOT NULL,
    end_time_ms INT NOT NULL,
    speaker_text TEXT NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
CREATE INDEX idx_segments_recording ON recording_segments(recording_id, start_time_ms);
CREATE INDEX idx_segments_course ON recording_segments(course_id);
CREATE INDEX idx_segments_vector ON recording_segments USING hnsw (embedding vector_cosine_ops);
```

# **4. Module & Component Breakdown**

## **4.1 Spring Boot Module (`server-core`)**

* **`com.lecturemate.api.controller`**
  * `CourseController.java`: 과목 생성·목록·조회·이름 변경·삭제
  * `MaterialController.java`: PDF 자료 업로드·목록·삭제·파싱 재시도
  * `RecordingController.java`: 녹음 생성·목록·삭제·전사 재시도
  * `ChatController.java`: 과목 단위 RAG 질문 전달 및 SSE 스트리밍 응답 중계
  * `FileController.java`: PDF·WAV 내려받기 (소유자 확인)
  * `InternalWebhookController.java`: FastAPI 전사 완료 알림 수신 Webhook
  * `AuthController.java`, `UserController.java`: 인증 및 내 정보
* **`com.lecturemate.websocket`**
  * `AudioStreamWebSocketHandler.java`: 브라우저 마이크 오디오 청크 수집. 종료 시 WAV 생성 후 전사 자동 시작
* **`com.lecturemate.client`**
  * `FastApiClient.java`: Spring RestClient 기반 FastAPI 통신 규격
* **`com.lecturemate.domain.entity`**
  * `User.java`, `RefreshToken.java`, `Course.java`, `CourseMaterial.java`, `CourseRecording.java`, `MaterialStatus.java`, `RecordingStatus.java`
  * `material_pages` / `recording_segments` 는 FastAPI 가 소유하므로 ORM 에 매핑하지 않는다
* **`com.lecturemate.repository`**
  * `UserRepository.java`, `RefreshTokenRepository.java`, `CourseRepository.java`, `CourseMaterialRepository.java`, `CourseRecordingRepository.java`
* **`com.lecturemate.service`**
  * `CourseService.java`: 과목 CRUD 및 **소유자 확인 단일 지점**(`requireOwned`). 자료·녹음 서비스는 반드시 이를 거친다
  * `MaterialService.java`, `RecordingService.java`, `StorageService.java`
  * `AiTaskTrigger.java`: 커밋 이후 FastAPI 에 파싱·전사를 요청한다 (`@TransactionalEventListener`)

## **4.2 FastAPI AI Module (`ai-engine`)**

* **`routers/`**
  * `materials.py`: PDF 파싱 및 BBox 추출 라우터
  * `recordings.py`: 녹음 정밀 전사 라우터
  * `rag.py`: 과목 단위 복합 벡터 검색 및 LLM 질의응답 라우터
* **`services/`**
  * `pdf_parser.py`: PyMuPDF(fitz)로 페이지별 텍스트 및 Bounding Box(`rect`) 추출
  * `stt_service.py`: Whisper 백엔드 관리 (`STT_BACKEND=mlx|faster-whisper`). 반복 루프를 막기 위해 5분 단위로 나눠 처리한다
  * `embedding_service.py`: BAAI/bge-m3 임베딩 (1024차원, 정규화)
  * `rag_service.py`: pgvector 하이브리드 쿼리(자료 페이지 + 녹음 발화) 구성 및 LLM 스트리밍 답변 생성. 자료 내용과 교수님 발화를 구분해 답하도록 프롬프트를 구성한다
  * `llm_client.py`: LLM 호출 추상화 (`LLM_PROVIDER=claude-code|ollama`)
  * `spring_webhook.py`: 전사 완료 통보
* **`core/`**
  * `config.py`: 환경 변수, DB 커넥션 풀, 모델 가중치 경로 관리
  * `database.py`: SQLAlchemy 비동기 세션 엔진
  * `models.py`: ORM 매핑

## **4.3 Web Frontend Module (`web-client`)**

* `app/courses/page.tsx`: 과목 목록 및 과목 만들기
* `app/courses/[id]/page.tsx`: 과목 학습 화면 (왼쪽 자료·녹음 목록, 가운데 PDF, 오른쪽 질의응답)
* **`components/`**
  * `MaterialList.tsx`: 자료 목록·업로드·삭제·재시도
  * `RecordingList.tsx`: 녹음 목록·상태 표시·삭제·재시도
  * `PdfViewer.tsx`: PDF.js 기반 슬라이드 렌더러
  * `AudioRecorder.tsx`: Web Audio API 기반 녹음 컨트롤러 (녹음 생성 후 WebSocket 연결)
  * `CourseChatPanel.tsx`: SSE 기반 RAG 어시스턴트 대화창. 자료 근거 뱃지를 누르면 그 자료의 해당 쪽으로 이동한다. 최근 4마디를 `history` 로 보내 후속 질문의 맥락을 유지한다
  * `AuthProvider.tsx`: 세션 복구 및 로그인 상태 공유

# **5. Environment & Commands**

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
  * `STT_BACKEND=mlx` (Apple GPU. Linux/CI 는 `faster-whisper`)
  * `WHISPER_MODEL_NAME=large-v3`
  * `EMBEDDING_MODEL_NAME=BAAI/bge-m3`
  * `LLM_PROVIDER=claude-code` (대체: `ollama`)
  * `CLAUDE_CODE_MODEL=sonnet` (실측상 opus 보다 약 2배 빠르고 답변이 간결하다)
  * `LLM_BACKEND_URL=http://localhost:11434/v1`, `LLM_MODEL_NAME=qwen2.5:14b-instruct` (`LLM_PROVIDER=ollama` 일 때)
* **Frontend (`.env.local`)**
  * `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`

## **5.2 Local Development Commands**

1. **Docker Compose (DB)**
   * `docker-compose up -d postgres`
2. **Spring Boot**
   * `./gradlew bootRun`
3. **FastAPI**
   * `source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload`
4. **Next.js Frontend**
   * `pnpm install && pnpm dev`
5. **한 번에 전부 (권장)**
   * `scripts/lecturemate.sh start` / `stop` — Desktop 의 `LectureMate 시작.app` 과 같다
   * DB 접속: `scripts/db.sh` (대화형) 또는 `scripts/db.sh "select ..."`
