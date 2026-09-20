# Step 10: 배치 정밀 전사 (녹음 종료 → 분석 → 완료 통보)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §2.1-3(녹음 종료 트리거), §2.2-2(배치 분석), §2.2-4(완료 Webhook). **슬라이드 정렬과 자동 필기는 제외**

## 전체 흐름

```
[정밀 분석 시작] 버튼
  └ POST /api/v1/lectures/{id}/recording/finish   → 202, status=ANALYZING
        └ 커밋 후 비동기 ──▶ POST /ai/v1/lectures/{id}/analyze-batch {audio_path}
                              → 202 {task_id, status: QUEUED}
                              └ 백그라운드: Whisper 전사 → lecture_transcripts 적재
                                   └ POST /internal/v1/lectures/{id}/analysis-complete
                                        (X-Internal-Secret 헤더)
  ◀── Spring Boot 가 status 를 READY(실패 시 FAILED)로 갱신
화면은 ANALYZING 동안 2초마다 상태를 확인한다
```

## 구현한 것

### FastAPI (`ai-engine`)
| 파일 | 내용 |
|---|---|
| `routers/audio.py` | `POST /ai/v1/lectures/{id}/analyze-batch` (202 즉시 응답 + BackgroundTask), `run_batch_analysis()` |
| `services/stt_service.py` | `transcribe_file()` — WAV 전체를 세그먼트 단위로 전사 (기본 large-v3) |
| `services/spring_webhook.py` | 완료 통보 (`X-Internal-Secret` 헤더, 실패해도 예외를 올리지 않음) |
| `core/models.py` | `lecture_transcripts` ORM 매핑 추가 |
| `core/config.py`, `.env.example` | `INTERNAL_API_SECRET` 추가 |

- `task_id`는 SPEC 예시대로 `batch_{lecture_id}_{YYYYMMDD}` 형식입니다.
- 재분석하면 해당 강의의 기존 전사 행을 지우고 다시 넣습니다.
- `matched_slide_page`와 `embedding`은 비워 둡니다. 정렬과 임베딩은 다음 단계 몫입니다.

### Spring Boot (`server-core`)
| 파일 | 내용 |
|---|---|
| `api/controller/LectureController.java` | `POST /{id}/recording/finish` → 202 `{lectureId, status, message}` |
| `service/LectureService.java` | `finishRecording()` — 소유자 확인, WAV 존재 확인, ANALYZING 전환, 이벤트 발행 |
| `service/PdfParseTrigger.java` | `onRecordingFinished()` — 커밋 후 비동기로 FastAPI 호출, 실패 시 FAILED |
| `api/controller/InternalWebhookController.java` | `POST /internal/v1/lectures/{id}/analysis-complete` → 상태 갱신 (204) |
| `client/FastApiClient.java` | `analyzeBatch()` |
| `api/dto/LectureResponse.java` | `audioUrl` 필드 추가 (화면이 녹음 여부를 알아야 함) |

### web-client
- 대시보드에 **정밀 분석 시작** 버튼 (녹음이 있고 상태가 READY일 때만 표시)
- `ANALYZING` 동안에도 상태 폴링
- `lectureApi.finishRecording()`

## 이렇게 한 이유

- **분석 시작을 버튼으로 분리:** 녹음 종료(WebSocket close) 직후 자동 호출하면, 서버가 WAV를 만들기 전에 요청이 도착해 409가 날 수 있습니다. 사용자가 누르는 방식이면 경쟁 상태가 없고 SPEC §2.1-3의 의미와도 맞습니다.
- **FastAPI는 202 후 백그라운드 처리:** 1시간짜리 강의 전사는 수 분이 걸립니다. SPEC §2.2-2 응답이 `QUEUED`인 것도 같은 전제입니다.
- **완료는 Webhook으로 통보 (SPEC §2.2-4):** Spring Boot가 폴링하지 않아도 되고, `ai-engine`을 별도 서버로 분리해도 그대로 동작합니다.
- **Webhook 실패는 삼키고 로그만 남깁니다.** 통보가 실패해도 전사 결과는 이미 DB에 있습니다. 다만 강의 상태가 `ANALYZING`에 멈추는 문제가 남습니다(아래 참고).
- **`totalPagesAnalyzed`는 0:** 아직 슬라이드 분석을 하지 않기 때문입니다. `matchedTranscriptSegments`도 실제로는 "전사 세그먼트 수"입니다. 값의 의미가 오해되지 않도록 SPEC §2.2에 주석을 달아 두었습니다.
- **배치 모델은 `WHISPER_MODEL_NAME`(기본 large-v3):** 실시간 프리뷰(base)와 분리되어 있습니다. 로컬 검증은 시간을 줄이려고 `WHISPER_MODEL_NAME=base`로 돌렸습니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `AuthFlowTest`의 내부 Webhook 테스트 실패 | 그 테스트는 "시크릿이 맞으면 404"를 기대했는데, 이번에 실제 Webhook 컨트롤러가 생겨 404가 아님 | 없는 경로(`/internal/v1/unknown-endpoint`)로 바꿔 필터 통과만 확인 |
| 화면에서 녹음 여부를 알 수 없음 | `LectureResponse`에 `audioUrl`이 없었음 | 응답에 필드 추가 후 SPEC §2.1-11도 갱신 |

## 검증 결과

**자동 테스트 (총 43개 통과)**
- Spring Boot 24개: 기존 19개 + `RecordingFinishTest` 5개
  - 종료 요청 → 202 ANALYZING → FastAPI 호출 인자(강의 ID, WAV 절대경로) 확인
  - 녹음 없이 종료 요청 → 409
  - 타인 토큰 → 404, 비로그인 → 401
  - Webhook READY 수신 → 상태 READY
  - Webhook 시크릿 없음 → 401, FAILED 수신 → 상태 FAILED
- FastAPI 14개: 기존 10개 + `test_analyze_batch.py` 4개
  - 202 응답과 `task_id` 형식, 전사 결과 DB 적재, `matched_slide_page`가 비어 있음
  - 재분석 시 중복 없음, 오디오 파일 없으면 404, 전사 실패 시 Webhook에 FAILED 통보
- web-client 5개, lint·타입 검사·빌드 통과

**브라우저 전 구간 검증** (헤드리스 Chrome + 서버 3개, 배치 모델 base)

| 단계 | 결과 |
|---|---|
| 녹음 → 실시간 자막 | "오늘은 다익스트라 최단 경로 알고리즘을 공부하..." |
| 녹음 종료 | `READY`, WAV 저장 |
| 정밀 분석 시작 | `ANALYZING` |
| 완료 통보 | 약 2초 후 `READY` (서버 로그: `segments=4`) |
| DB 확인 | `lecture_transcripts` 4행, 타임스탬프 0–3600 / 3600–7200 / 7200–10800 / 10800–11600ms, `matched_slide_page`는 전부 비어 있음 |

전사된 문장 예: "오늘은 다익스트라 최단 경로 알고리즘을 공부합니다.", "음소 가중치가 있으면 벨만 포드를 사용해야 합니다." (테스트 음원을 반복 재생해서 같은 문장이 두 번 나옵니다. base 모델이라 "음수"를 "음소"로 잘못 듣습니다.)

검증에 사용한 계정, 강의, 오디오 파일은 모두 삭제했습니다.

## 남은 일

- **슬라이드 정렬(`alignment_service`)**: `matched_slide_page`를 채워야 필기 생성이 가능합니다 — 다음 단계
- **자동 필기 생성(`annotation_service`)**: LLM 필요
- **전사 임베딩**: RAG 검색을 위해 `lecture_transcripts.embedding`도 채워야 합니다
- Webhook 통보가 실패하면 강의가 `ANALYZING`에 멈춥니다. 재시도나 타임아웃 처리가 필요합니다
- 배치 작업 상태를 조회할 방법이 없습니다(`task_id`를 발급만 하고 추적하지 않음)
- large-v3는 약 3GB 다운로드입니다. EC2 배포 시 미리 받아 두는 편이 좋습니다

## 사용법

```bash
docker compose up -d postgres
cd ai-engine && source .venv/bin/activate && uvicorn main:app --port 8000
# 로컬에서 빠르게 확인하려면: WHISPER_MODEL_NAME=base uvicorn main:app --port 8000
cd server-core && ./gradlew bootRun
cd web-client && pnpm dev
# 녹음 종료 후 대시보드의 "정밀 분석 시작" 버튼
```
