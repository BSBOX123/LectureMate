# Step 13: RAG 질의응답 (하이브리드 검색 + SSE 스트리밍)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §2.1-5(채팅 SSE), §2.2-3(RAG 검색/생성), §4.3 `LectureChatPanel`. **SPEC의 마지막 핵심 기능**

## 전체 흐름

```
질문 입력
  └ POST /api/v1/lectures/{id}/chat   (Spring Boot: 소유자 확인 후 SSE 중계)
      └ POST /ai/v1/rag/query          (FastAPI)
          ├ 질문 임베딩(bge-m3)
          ├ pgvector 코사인 검색: lecture_slides top_k + lecture_transcripts top_k
          ├ event: citations  ← 출처를 먼저 보냄 (뱃지 즉시 표시)
          ├ event: token ...  ← LLM 토큰 스트리밍
          └ event: done
브라우저: 답변을 실시간으로 그리고, 출처 뱃지를 누르면 해당 슬라이드로 이동
```

## SSE 이벤트 형식 (SPEC §2.1-5에 새로 정의)

```
event: citations
data: {"citations":[{"source":"SLIDE","pageNumber":2,"snippet":"...","startTimeMs":null}]}

event: token
data: {"text":"벨만 "}

event: done
data: {"finishReason":"stop"}
```

- `source`는 `SLIDE`(교재 내용)와 `TRANSCRIPT`(교수님 발화) 두 가지입니다. `startTimeMs`는 발화에만 있습니다.
- **citations를 답변보다 먼저 보냅니다.** 검색은 즉시 끝나지만 LLM 생성은 수 초가 걸리므로, 그 사이에 출처를 먼저 보여 줄 수 있습니다.

## 구현한 것

### FastAPI (`ai-engine`)
| 파일 | 내용 |
|---|---|
| `services/rag_service.py` | 질문 임베딩, pgvector 하이브리드 검색, 프롬프트 구성, LLM 스트리밍, SSE 직렬화 |
| `routers/rag.py` | `POST /ai/v1/rag/query` → `StreamingResponse` |

- 검색은 슬라이드와 전사를 **각각** top_k개 가져옵니다. "교재에 뭐라고 쓰여 있는지"와 "교수님이 뭐라고 했는지"가 둘 다 필요하기 때문입니다.
- 프롬프트에 근거만 쓰도록 제약하고, 슬라이드는 쪽수와 함께, 발화는 쪽수와 시각과 함께 넣습니다.
- LLM 호출은 동기 이터레이터라 `iterate_in_threadpool`로 감싸 이벤트 루프를 막지 않습니다.
- 생성 중 실패하면 `done { finishReason: "error" }`로 끝냅니다.

### Spring Boot (`server-core`)
- `ChatController`: 소유자를 먼저 확인한 뒤 `StreamingResponseBody`로 FastAPI SSE를 **바이트 그대로** 중계합니다. 이벤트를 해석하지 않으므로 형식이 바뀌어도 중계 코드는 영향을 받지 않습니다.
- `FastApiClient.streamRagQuery()`: `RestClient.exchange()`로 응답 스트림을 읽어 즉시 flush합니다.

### web-client
| 파일 | 내용 |
|---|---|
| `lib/sse.ts` | SSE 파서 (fetch 스트림은 `EventSource`를 못 쓴다) |
| `lib/api.ts` | `lectureApi.chat()` — 스트림을 읽어 citations/token/done 콜백 호출 |
| `components/LectureChatPanel.tsx` | 대화 목록, 토큰 누적 렌더링, 출처 뱃지(클릭 시 슬라이드 이동) |

## 이렇게 한 이유

- **Spring Boot가 SSE를 해석하지 않고 중계만:** SPEC상 Spring Boot의 역할은 "SSE 스트리밍 중계"입니다. 파싱하면 형식 변경 때마다 양쪽을 고쳐야 합니다.
- **응답에 `charset=UTF-8` 명시:** SSE 기본 인코딩은 UTF-8이지만, 명시하지 않으면 클라이언트나 프록시가 Latin-1로 해석해 한국어가 깨질 수 있습니다(테스트에서 실제로 겪었습니다).
- **프론트에서 fetch로 직접 파싱:** `EventSource`는 POST를 지원하지 않고 헤더도 못 붙입니다. 질문 본문과 Bearer 토큰이 필요하므로 fetch 스트림을 직접 읽습니다.
- **출처 뱃지에 스니펫을 title로:** 마우스를 올리면 근거 문장을 볼 수 있습니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| MockMvc에서 한국어가 깨져 본문 비교 실패 | 응답에 charset이 없으면 MockMvc가 Latin-1로 디코딩 | 컨트롤러에 `charset=UTF-8` 명시(실제 개선), 테스트는 바이트를 UTF-8로 직접 디코딩 |
| 스트리밍 응답 본문이 비어 있음 | `StreamingResponseBody`는 비동기 처리라 첫 결과가 아니라 **asyncDispatch 결과**에 본문이 담김 | `mockMvc.perform(asyncDispatch(result))`의 반환값에서 본문을 읽음 |
| MockMvc가 Content-Type을 기록하지 않음 | 스트리밍 응답에서는 헤더가 채워지지 않음 | 테스트는 본문만 검증하고, 인코딩은 컨트롤러 선언으로 보장 |

## 검증 결과

**자동 테스트 (총 80개 통과)**
- FastAPI 40개: 기존 35개 + RAG 5개
  - citations → token → done 순서와 형식, 질문과 가장 가까운 슬라이드/발화가 선택되는지, 검색 결과가 LLM에 전달되는지
  - LLM 실패 시 `done { error }`, 임베딩 비활성 시 빈 citations, 잘못된 요청 422
- Spring Boot 31개: 기존 27개 + 채팅 중계 4개
  - SSE 원문 그대로 중계, citations가 token보다 먼저, 비동기 처리, 타인 강의 404·비로그인 401·빈 질문 400
- web-client 9개: 기존 5개 + SSE 파서 4개(부분 수신 버퍼 처리 포함)

**실제 LLM(qwen2.5:7b-instruct)로 전 구간 검증** — 녹음부터 질의응답까지 한 번에

| 단계 | 결과 |
|---|---|
| 녹음 → 실시간 자막 | "오늘은 다익스트라 최단 경로 알고리즘을 공부하..." |
| 정밀 분석 | 전사 → 정렬 → 자동 필기 생성 완료 |
| 질문 | "음수 가중치가 있으면 어떻게 해야 하나요?" |
| 출처 뱃지 (답변 전 먼저 도착) | 슬라이드 p.2, 슬라이드 p.1, 발화 p.2, 발화 p.1, 발화 p.2 |
| **답변** | **"음수 가중치가 있으면 벨만 포드 알고리즘을 사용해야 합니다."** |
| 뱃지 클릭 | 2쪽으로 이동 확인 (SPEC §4.3) |

질문과 가장 가까운 2쪽이 첫 출처로 선택됐고, 답변도 2쪽 내용과 교수님 발화를 근거로 나왔습니다.

검증에 사용한 계정, 강의, 파일은 모두 삭제했습니다.

## 남은 일

- 같은 쪽의 발화가 여러 건이면 뱃지가 중복돼 보입니다(예: 발화 p.2가 두 번). 묶어서 보여 줄지 검토
- 대화 맥락(이전 질문)을 이어받지 않습니다. 매 질문이 독립입니다
- 검색 품질 평가 수단이 없습니다. top_k=5 고정
- `SlideTimeline`에 발화 분량과 시험 힌트를 채울 API가 여전히 없습니다

## 사용법

```bash
brew services start ollama        # 네이티브 Ollama
docker-compose up -d postgres
cd ai-engine && LLM_MODEL_NAME=qwen2.5:7b-instruct uvicorn main:app --port 8000
cd server-core && ./gradlew bootRun
cd web-client && pnpm dev
# /lectures/{id} 우측 패널에서 질문 입력
```
