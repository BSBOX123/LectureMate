# Step 2: FastAPI `ai-engine` 스캐폴딩

- 기간: 2026-09-20
- 커밋: `16bef97`

## 구현한 것

```
ai-engine/
├── main.py              # FastAPI 앱. 라우터를 /ai/v1 prefix로 등록, 종료 시 DB 엔진 정리
├── requirements.txt     # 설치 확인 후 버전 고정
├── .env.example         # SPEC §5.1 환경 변수 + EC2 SSH 터널 사용법
├── .gitignore           # .venv, .env, __pycache__ 제외
├── core/
│   ├── config.py        # pydantic-settings 기반 설정
│   └── database.py      # SQLAlchemy 비동기 엔진, 세션, Base, get_session
├── routers/             # pdf.py, audio.py, rag.py: APIRouter 선언만
└── services/            # pdf_parser, stt_service, alignment_service,
                         # annotation_service, rag_service: 빈 파일
```

`SPEC.md` §2.2에 5번 항목 **실시간 오디오 청크 STT 프리뷰 (`WS /ai/v1/lectures/{lecture_id}/audio-stream`)**도 추가했습니다.

## 코드 설명

### core/config.py

- `Settings(BaseSettings)`가 `.env`나 OS 환경 변수를 읽습니다. 기본값은 SPEC §5.1 로컬 개발 값입니다.
- `get_settings()`는 `lru_cache`로 한 번만 만들고, 모듈 전역 `settings`로 노출합니다.

| 필드 (환경 변수) | 기본값 | 출처 |
|---|---|---|
| `DATABASE_URL` | `postgresql+asyncpg://postgres:postgres@localhost:5432/lecturemate` | SPEC |
| `SPRING_BOOT_WEBHOOK_URL` | `http://localhost:8080/internal/v1` | SPEC |
| `WHISPER_MODEL_NAME` | `large-v3` | SPEC |
| `EMBEDDING_MODEL_NAME` | `BAAI/bge-m3` | SPEC |
| `LLM_BACKEND_URL` | `http://localhost:11434/v1` | SPEC |
| `LLM_MODEL_NAME` | `qwen2.5:14b-instruct` | SPEC |
| `DB_POOL_SIZE` / `DB_MAX_OVERFLOW` / `DB_ECHO` | 10 / 20 / false | 추가 (SPEC의 "DB 커넥션 풀 관리") |
| `MODEL_CACHE_DIR` | 없음 (HuggingFace 기본 캐시) | 추가 (SPEC의 "모델 가중치 경로 관리") |

### core/database.py

- `create_async_engine`: asyncpg 드라이버를 쓰고, `pool_pre_ping=True`로 끊긴 커넥션을 자동으로 걸러냅니다.
- `AsyncSessionLocal`: `expire_on_commit=False`입니다. 비동기 환경에서 커밋 후 속성에 접근할 때 lazy load 오류가 나지 않게 하려는 설정입니다.
- `Base(DeclarativeBase)`: ORM 모델용 베이스입니다. **스키마는 `db/init.sql`이 관리하므로 `create_all`은 쓰지 않습니다.**
- `get_session()`: FastAPI `Depends`용 제너레이터입니다. 예외가 나면 롤백합니다.

### main.py

- `lifespan`: 앱이 종료될 때 `engine.dispose()`로 커넥션 풀을 정리합니다.
- `pdf`, `audio`, `rag` 라우터를 `/ai/v1` prefix로 등록합니다. SPEC §2.2의 경로가 모두 `/ai/v1/...` 형태이기 때문입니다.

### requirements.txt (Python 3.13에서 설치 검증)

| 분류 | 패키지 | 용도 |
|---|---|---|
| Web | fastapi 0.141.1, uvicorn[standard] 0.53.0, pydantic 2.13.5, pydantic-settings 2.15.0 | API 서버, 설정 |
| DB | sqlalchemy[asyncio] 2.0.54, asyncpg 0.31.0, pgvector 0.5.0 | 비동기 ORM, pgvector 타입 |
| PDF | pymupdf 1.28.2 | 텍스트, BBox 추출 |
| STT | faster-whisper 1.2.1 | Whisper large-v3 / base |
| Embedding | torch 2.14.0, sentence-transformers 6.1.0 | bge-m3 |
| LLM / HTTP | openai 3.16.2, httpx 0.28.1 | OpenAI 호환 LLM 클라이언트, Webhook 호출 |
| Test | pytest 9.1.1, pytest-asyncio 1.4.0 | 테스트 |

vLLM은 넣지 않았습니다. vLLM은 EC2에서 별도 서버로 실행하고, `ai-engine`은 HTTP로만 호출하기 때문입니다.

## 다른 모듈과의 관계

```
Spring Boot ──HTTP──▶ FastAPI  POST /ai/v1/pdf/parse, /lectures/{id}/analyze-batch, /rag/query (SSE)
Spring Boot ◀──WS──▶ FastAPI  /ai/v1/lectures/{id}/audio-stream (오디오 청크 → TRANSCRIPT_PREVIEW)
FastAPI ──HTTP──▶ Spring Boot POST /internal/v1/lectures/{id}/analysis-complete (Webhook)
FastAPI ──SQL──▶ PostgreSQL   lecture_slides / lecture_transcripts / slide_annotations 적재 및 벡터 검색
FastAPI ──HTTP──▶ LLM (EC2 Ollama/vLLM, OpenAI 호환 /v1)
```

- **테이블 소유권:** `lecture_slides`, `lecture_transcripts`, `slide_annotations`는 FastAPI가 씁니다. Spring Boot는 이 테이블을 읽기만 합니다(Step 3 참고).

## 이렇게 한 이유

- **bge-m3를 `sentence-transformers`로 사용 (dense 임베딩만):** DB 컬럼이 `vector(1024)` 하나뿐이어서입니다. SPEC의 "하이브리드 검색"은 슬라이드 컨텍스트와 음성 전사 컨텍스트를 합치는 것이라 sparse 임베딩은 필요 없다고 해석했습니다. sparse 임베딩이 필요해지면 FlagEmbedding을 쓰고 컬럼을 추가해야 합니다.
- **LLM 클라이언트는 `openai` SDK:** Ollama와 vLLM이 둘 다 OpenAI 호환 `/v1` API를 제공합니다. 그래서 `LLM_BACKEND_URL`만 바꾸면 로컬 Ollama, EC2 Ollama, EC2 vLLM 사이를 코드 변경 없이 오갈 수 있습니다.
- **라우터에 `APIRouter` 한 줄만 둔 이유:** 파일을 완전히 비우면 `main.py`에서 import가 실패합니다. SPEC에 없는 엔드포인트(예: `/health`)는 AGENTS.md 규칙에 따라 추가하지 않았습니다.
- **LLM 운영 방식 (사용자 결정):**
  - 개발 중에는 `ai-engine`, DB, Spring Boot를 로컬에서 돌리고 LLM만 EC2 Ollama를 SSH 터널로 연결합니다. Whisper는 작은 모델로 테스트합니다.
  - 통합 테스트와 시연 때는 GPU EC2 한 대(A10G/L4 24GB)에 `ai-engine`, Whisper, bge-m3, LLM을 모두 올립니다.
  - 메모리 추정: Qwen2.5-14B 4bit 약 9~10GB, Whisper large-v3 int8 약 3GB, bge-m3 약 2GB, 여기에 KV 캐시를 더해도 24GB에 들어갑니다.
  - Ollama에는 인증이 없으므로 11434 포트를 인터넷에 열지 않습니다. SSH 터널, 보안 그룹 IP 제한, Tailscale 중 하나로 접근합니다.
- **실시간 STT는 WebSocket (사용자 결정, B안):** 녹음 세션마다 Spring Boot와 FastAPI 사이에 연결을 하나 유지합니다. 3~5초마다 오는 청크를 HTTP 요청으로 보내는 방식보다 지연과 오버헤드가 적습니다. FastAPI가 클라이언트용 `TRANSCRIPT_PREVIEW` 형식 그대로 응답하므로 Spring Boot는 받은 것을 그대로 전달만 하면 됩니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| SSH 터널 포트 충돌 | 로컬 `ollama` 컨테이너가 11434를 이미 사용 | 터널은 **11435**로 엽니다: `ssh -N -L 11435:localhost:11434 <user>@<ec2>`. `.env`에 `LLM_BACKEND_URL=http://localhost:11435/v1` |
| `import fitz` deprecated 경고 | PyMuPDF 최신 버전의 API 변경 | 구현할 때 `import pymupdf` 사용 |
| Python 버전 선택 | 시스템 기본은 3.14. torch/CTranslate2 휠이 3.14를 지원하는지 확인되지 않음 | 3.13으로 가상환경을 만들어 전체 설치 성공. EC2 배포도 3.13 권장 |
| 실시간 STT 내부 API 부재 | SPEC §2.2에 Spring Boot → FastAPI 청크 전달 경로가 없었음 | 사용자 결정(B안)으로 §2.2-5 추가 |

## 검증 결과

- `pip install -r requirements.txt`: 성공 (Python 3.13, macOS arm64)
- `main.app` import와 `TestClient` 기동, `/openapi.json` 200 응답 (등록된 경로는 아직 없음)
- `AsyncSessionLocal`로 로컬 postgres에 `select version()` 성공 (PostgreSQL 16.15)

## 사용법

```bash
cd ai-engine
python3.13 -m venv .venv && .venv/bin/pip install -r requirements.txt   # 최초 1회
cp .env.example .env
source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```
