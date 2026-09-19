# Step 1: 인프라 및 DB 초기화

- 기간: 2026-09-18 ~ 2026-09-20 (DB 실제 검증은 2026-09-20)
- 커밋: `91b445c` (Step 1), `8b09919` (CLAUDE.md → AGENTS.md 분리, 사용자 변경)

## 구현한 것

| 파일 | 내용 |
|---|---|
| `docker-compose.yml` | PostgreSQL 16 + pgvector, Ollama 컨테이너 |
| `db/init.sql` | SPEC §3 스키마 + `users` 테이블 |
| `SPEC.md` | §3에 `users` 테이블, §4.1에 `User.java`, `UserRepository.java` 추가 |
| `AGENTS.md` (구 `CLAUDE.md`) | 모듈 디렉토리와 기술 스택을 SPEC에 맞춤 |

## 코드 설명

### docker-compose.yml

| 서비스 | 이미지 | 포트 | 볼륨 | 비고 |
|---|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | 5432 | `postgres-data` | `POSTGRES_USER/PASSWORD=postgres`, `POSTGRES_DB=lecturemate`, `pg_isready` healthcheck |
| `ollama` | `ollama/ollama:latest` | 11434 | `ollama-data` | 모델 다운로드는 자동으로 하지 않음 |

- 계정과 DB 이름은 SPEC §5.1의 FastAPI `DATABASE_URL`(`postgres:postgres@localhost:5432/lecturemate`)에서 가져왔습니다.
- `db/init.sql`을 `/docker-entrypoint-initdb.d/01-init.sql`로 마운트합니다. Postgres 이미지는 **데이터 볼륨이 비어 있을 때 한 번만** 이 스크립트를 실행합니다.

### db/init.sql

SPEC §3의 SQL을 그대로 옮기고 `users` 테이블만 추가했습니다.

```
users (1) ──< lectures (1) ──< lecture_slides (1) ──< slide_annotations
                         ├──< lecture_transcripts
                         └──< slide_annotations (lecture_id로도 직접 참조)
```

| 테이블 | 역할 | 벡터 |
|---|---|---|
| `users` | 회원 (email UNIQUE, BCrypt `password_hash`) | - |
| `lectures` | 강의 메타데이터, 처리 상태 (`INITIALIZED` → `PROCESSING` → `RECORDING` → `ANALYZING` → `READY` / `FAILED`) | - |
| `lecture_slides` | 페이지별 텍스트, 단어 BBox(`layout_data` JSONB) | `embedding vector(1024)` + HNSW |
| `lecture_transcripts` | 전사 세그먼트, 매핑된 슬라이드 번호 | `embedding vector(1024)` + HNSW |
| `slide_annotations` | 슬라이드별 요약, 시험 힌트, 하이라이트 BBox | - |

- 모든 FK는 `ON DELETE CASCADE`입니다. 사용자나 강의를 지우면 하위 데이터도 DB에서 함께 삭제됩니다.
- 1024차원은 BAAI/bge-m3 임베딩 차원입니다. HNSW 인덱스는 코사인 거리(`vector_cosine_ops`)를 씁니다.

## 이렇게 한 이유

- **`users` 테이블 추가:** SPEC에는 `lectures.user_id BIGINT NOT NULL`만 있고 참조할 테이블이 없었습니다. 기술 스택에는 Spring Security + JWT가 있어서 인증 주체가 필요합니다. 컬럼은 이메일/비밀번호 로그인에 필요한 최소한만 넣었습니다(role, 소셜 로그인 없음). 사용자 승인을 받았습니다.
- **SPEC.md도 같이 수정:** SPEC을 가장 우선하는 문서로 쓰기로 했으므로 DB와 SPEC이 어긋나지 않게 맞췄습니다.
- **스키마 파일 위치 `db/`:** SPEC에 위치가 없어서 정했습니다. 나중에 마이그레이션 파일이 늘어나도 한곳에 모을 수 있습니다.
- **스펙에 없는 트리거와 제약조건은 추가하지 않음:** 예를 들어 `updated_at` 자동 갱신 트리거는 넣지 않았습니다. `updated_at`은 Spring Boot의 `@UpdateTimestamp`가 갱신합니다(Step 3).
- **Ollama 컨테이너 유지:** SPEC §5.2 명령(`docker-compose up -d postgres ollama`)과 맞추기 위해서입니다. 실제 LLM 추론은 EC2에서 합니다(Step 2 참고).

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| 컨테이너를 띄워 볼 수 없었음 | Docker Desktop 데몬이 꺼져 있었음 | `docker compose config`로 문법만 먼저 확인. 이후 사용자가 **OrbStack**으로 바꾼 뒤(2026-09-20) 실제로 기동해 검증 |
| 디렉토리 이름 불일치 | 기존 CLAUDE.md는 `backend/frontend/ai-service`, Java 17, npm 기준이었음 | SPEC 기준(`server-core/web-client/ai-engine`, Java 21, pnpm)으로 수정. 이후 사용자가 `AGENTS.md`로 분리 |
| Mac 컨테이너에서 Ollama가 느림 | macOS 컨테이너는 Apple GPU(Metal)를 쓸 수 없어서 CPU로만 추론 | LLM은 EC2 GPU에서 운영하기로 결정 |

### OrbStack 사용 메모

- OrbStack을 설치하면 `/usr/local/bin/docker`와 `docker-compose`가 OrbStack 바이너리로 연결되고 docker context가 `orbstack`으로 바뀝니다. **별도 설정 없이 기존 명령을 그대로 쓰면 됩니다.**
- OrbStack에서도 컨테이너 안에서 Mac GPU는 쓸 수 없습니다.

## 검증 결과 (2026-09-20, OrbStack)

- `docker compose up -d postgres`: healthy
- 컨테이너 로그: `01-init.sql` 실행 중 오류 없음
- 테이블 5개(`users`, `lectures`, `lecture_slides`, `lecture_transcripts`, `slide_annotations`)와 인덱스 11개 생성
- pgvector 확장 버전 0.8.6

## 사용법

```bash
docker compose up -d postgres ollama     # 기동
docker compose down                      # 중지 (데이터 유지)
docker compose down -v                   # 중지 + 볼륨 삭제 → 다음 기동 때 init.sql 재실행
docker exec -it lecturemate-postgres psql -U postgres -d lecturemate
```
