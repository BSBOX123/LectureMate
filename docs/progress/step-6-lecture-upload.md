# Step 6: 강의 생성 및 PDF 업로드 + 파싱

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §2.1-1 업로드 → 파일 저장 → FastAPI §2.2-1 파싱 → `lecture_slides` 적재 → 화면(업로드/목록/대시보드)

## 전체 흐름

```
브라우저 ──multipart(title, file)──▶ POST /api/v1/lectures
                                     ① lectures 행 생성 (INITIALIZED)
                                     ② {storage}/pdf/{id}.pdf 저장
                                     ③ status=PROCESSING, pdfUrl 기록
                                     ④ 201 즉시 응답
                                     ⑤ 커밋 후 비동기 ──▶ POST /ai/v1/pdf/parse (FastAPI)
                                                          PyMuPDF 파싱 → lecture_slides 적재
                                     ⑥ 성공 READY / 실패 FAILED
브라우저 ──폴링──▶ GET /api/v1/lectures/{id}  (PROCESSING 동안 2초 간격)
브라우저 ──Bearer──▶ GET /files/pdf/{id}.pdf  (소유자만)
```

## 구현한 것

### FastAPI (`ai-engine`)
| 파일 | 내용 |
|---|---|
| `routers/pdf.py` | `POST /ai/v1/pdf/parse` (SPEC §2.2-1). 파일 없으면 404, `lecture_id<=0`이면 422 |
| `services/pdf_parser.py` | `parse_pdf()`(PyMuPDF 텍스트·BBox 추출), `store_pages()`(적재, 재파싱 시 기존 행 교체) |
| `services/embedding_service.py` | bge-m3 임베딩. `EMBEDDING_ENABLED=false`(기본)면 모델을 받지 않고 `None` 반환 |
| `core/models.py` | `lecture_slides` ORM 매핑 (`pgvector.sqlalchemy.Vector(1024)`) |
| `pytest.ini` | `asyncio_mode = auto`, 세션 단위 이벤트 루프 |

- `parse_pdf()`는 `page.get_text("words")`로 단어별 `[x1, y1, x2, y2]`(PDF 포인트)를 뽑아 SPEC §3의 `layout_data` 형태로 만듭니다. 공백 단어는 버립니다.
- `store_pages()`는 같은 `lecture_id`의 기존 행을 지우고 다시 넣습니다. 재업로드나 재시도 시 중복이 쌓이지 않습니다.

### Spring Boot (`server-core`)
| 파일 | 내용 |
|---|---|
| `client/FastApiClient.java` | RestClient 기반 FastAPI 호출. snake_case 계약(`lecture_id`, `pdf_path`) |
| `service/StorageService.java` | `{storage}/pdf/{id}.pdf` 저장, PDF 매직넘버 검사, 경로 조회 |
| `service/LectureService.java` | 강의 생성/조회/목록/상태 변경, 커밋 후 이벤트 발행 |
| `service/PdfParseTrigger.java` | `@TransactionalEventListener` + `@Async`로 파싱 호출 후 상태 갱신 |
| `config/AsyncConfig.java` | `@EnableAsync` |
| `api/controller/LectureController.java` | `POST /api/v1/lectures`, `GET /api/v1/lectures`, `GET /api/v1/lectures/{id}` |
| `api/controller/FileController.java` | `GET /files/pdf/{id}.pdf` (소유자 검사) |
| `repository/LectureRepository.java` | `findByIdAndUserId`, `findAllByUserIdOrderByCreatedAtDesc` |
| `domain/entity/Lecture.java` | `attachPdf(pdfUrl)`, `changeStatus(status)` 추가 |

### web-client
| 파일 | 내용 |
|---|---|
| `app/lectures/new/page.tsx` | 제목 + PDF 업로드 폼, 완료 후 대시보드로 이동 |
| `app/lectures/page.tsx` | 내 강의 목록 (제목, status) |
| `app/lectures/[id]/page.tsx` | 강의 메타 조회, PROCESSING 동안 2초 폴링, PDF blob URL 전달 |
| `lib/api.ts` | `lectureApi.create/get/list/pdfObjectUrl` 추가 |
| `types/api.ts` | `LectureCreatedResponse` → `LectureResponse`(조회 응답과 동일 형식) |

### SPEC 추가
- §2.1-11 강의 목록/메타데이터 조회, §2.1-12 PDF 내려받기
- **강의 status 전이** 규칙: `INITIALIZED → PROCESSING → READY`, 녹음 시 `RECORDING`, 분석 시 `ANALYZING`, 실패 시 `FAILED`

## 이렇게 한 이유

- **파싱은 커밋 이후 비동기 (사용자 결정):** SPEC §2.1-1 응답이 `PROCESSING`이므로 업로드 응답을 기다리게 하지 않습니다. 또 FastAPI가 `lecture_slides`를 넣으려면 `lectures` 행이 **이미 커밋되어 있어야** 합니다(FK). 그래서 `@TransactionalEventListener`(기본 AFTER_COMMIT) + `@Async` 조합을 씁니다. 단순 `@Async` 호출이었다면 커밋 전에 FastAPI가 먼저 도달해 FK 오류가 날 수 있습니다.
- **파싱 완료 후 status는 READY (사용자 결정):** READY = "사용 가능". 분석 완료 Webhook(§2.2-4)도 READY를 쓰므로 일관됩니다.
- **임베딩은 기본 꺼짐 (사용자 결정):** bge-m3는 약 2GB를 내려받고 Mac CPU에서는 느립니다. `EMBEDDING_ENABLED=true`로 켜면 같은 코드 경로에서 벡터까지 저장합니다. RAG 구현 단계에서 켤 예정입니다.
- **확장자 대신 매직넘버(%PDF) 검사:** `Content-Type`과 파일명은 클라이언트가 마음대로 보낼 수 있습니다.
- **PDF를 blob URL로 받는 이유:** `/files/pdf/{id}.pdf`가 Bearer 토큰을 요구하는데 `<iframe src>`나 `<object>`는 헤더를 붙일 수 없습니다. 그래서 fetch로 받아 `URL.createObjectURL`로 넘깁니다.
- **강의 목록 화면 추가:** SPEC §4.3에는 대시보드만 있지만, 업로드한 강의로 이동할 방법이 필요했습니다. API(§2.1-11)와 함께 SPEC에 반영했습니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| **업로드 후 status=FAILED, 슬라이드 0건** (실제 서버 실행 시) | Java의 `JdkClientHttpRequestFactory`가 기본적으로 HTTP/2 업그레이드(h2c)를 시도하는데, uvicorn(h11)은 이를 지원하지 않아 `400 Invalid HTTP request` 반환 | `HttpClient.newBuilder().version(HTTP_1_1)`로 고정. **목(mock)을 쓴 테스트에서는 드러나지 않는 문제라, 실제 두 서버를 띄워 확인해야 했습니다** |
| Boot 3 → 4에서 `org.springframework.boot.http.client.ClientHttpRequestFactorySettings` 없음 | Boot 4에서 패키지/모듈 구성이 바뀜 | 프레임워크 기본 API(`JdkClientHttpRequestFactory` + `HttpClient`)로 타임아웃 설정 |
| pytest에서 `Event loop is closed`, `attached to a different loop` | 테스트마다 `asyncio.run()`을 호출해 이벤트 루프가 달라지고, asyncpg 커넥션이 다른 루프에 묶임 | `pytest.ini`에 `asyncio_mode=auto` + 세션 스코프 루프, 테스트를 async 로 작성하고 `httpx.ASGITransport` 사용 |
| `NoReferencedTableError: ... could not find table 'lectures'` | ORM 모델에 `ForeignKey("lectures.id")`를 선언했지만 `lectures`는 매핑하지 않음 | FK/CASCADE는 DB(`init.sql`)가 관리하므로 모델에서 FK 선언 제거 |
| ESLint `react-hooks/set-state-in-effect` 오류 | Next 16의 React Compiler 규칙이 effect 안 setState 호출을 막음 | 데이터 로딩은 `then` 콜백에서 상태를 갱신하고, 폴링은 `reloadKey` 카운터를 올려 재조회하도록 구조 변경 |

## 검증 결과

**Spring Boot 15개 테스트 통과** (`./gradlew test`)
- `LectureUploadTest` 5개 (신규): 업로드 201 + 파일 저장 + 비동기 파싱 호출 + READY 전환, 파싱 실패 시 FAILED, PDF 아닌 파일 400 / 비로그인 401, PDF 내려받기는 소유자만(타인 404), 목록은 본인 것만
- 기존 `AuthFlowTest` 7개, `EntityMappingTest` 2개, `LectureMateApplicationTests` 1개 통과

**FastAPI 5개 테스트 통과** (`.venv/bin/python -m pytest`)
- `test_pdf_parser.py` 2개: 텍스트·단어 BBox 추출, 빈 페이지 처리
- `test_pdf_router.py` 3개 (실제 DB 사용): 적재 및 재파싱 멱등성, 없는 파일 404, 잘못된 `lecture_id` 422

**실제 3개 서버 연동 확인** (postgres + FastAPI + Spring Boot, 3페이지 PDF)

| 단계 | 결과 |
|---|---|
| 업로드 | 201 `{"lectureId":18,"status":"PROCESSING","pdfUrl":"/files/pdf/18.pdf"}` |
| 상태 폴링 | 약 2초 후 `READY` |
| 파일 저장 | `~/lecturemate/storage/pdf/18.pdf` |
| DB 적재 | 3행, 페이지별 단어 6~7개, 첫 단어 `Slide`, bbox `[72.0, 98.5, 116.46, 125.98]` |
| PDF 내려받기 | 200 `application/pdf`, 원본과 바이트 동일 |

검증에 사용한 계정, 강의, 저장 파일은 모두 삭제했습니다.

**확인하지 못한 것:** 브라우저 확장이 연결되어 있지 않아 화면에서 직접 업로드해 보지는 못했습니다. 화면은 lint, 타입 검사, 빌드까지만 확인했습니다.

## 사용법

```bash
docker compose up -d postgres
cd ai-engine && source .venv/bin/activate && uvicorn main:app --port 8000 --reload
cd server-core && ./gradlew bootRun
cd web-client && pnpm dev
# http://localhost:3000/signup → 로그인 → /lectures/new 에서 PDF 업로드
```
