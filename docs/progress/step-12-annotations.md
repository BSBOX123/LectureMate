# Step 12: 자동 필기 생성 (LLM) + 주석 조회 API

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §4.2 `annotation_service`, §2.1-4 주석 조회 API, 화면 오버레이 연결

## 전체 흐름

```
배치 분석(전사 → 정렬) 이후
  └ 슬라이드별로 매칭된 발화를 모아 LLM 호출
      └ JSON 응답: {professor_summary, exam_hints, highlight_words}
          └ highlight_words 를 PDF layout_data 의 BBox 와 매칭
              └ slide_annotations 적재
브라우저: GET /api/v1/lectures/{id}/pages/{n}/annotations
  └ AnnotationOverlay 가 PDF 위에 하이라이트 + 요약 카드 렌더링
```

## 구현한 것

### FastAPI (`ai-engine`)
| 파일 | 내용 |
|---|---|
| `services/annotation_service.py` | 프롬프트, LLM 호출(OpenAI 호환), JSON 파싱, 강조 단어 ↔ BBox 매칭, 신뢰도 계산 |
| `routers/audio.py` | 배치 분석 마지막 단계로 `_generate_annotations()` 추가 |
| `core/models.py` | `slide_annotations` ORM 매핑 |
| `core/config.py` | `llm_timeout_seconds`(기본 180초) |

- **프롬프트**: 시스템 메시지로 JSON 형식을 고정하고, "강조 단어는 슬라이드에 실제로 있는 단어여야 한다"고 못 박습니다. `response_format={"type":"json_object"}`도 함께 씁니다.
- **파싱**: 모델이 ```json 코드 블록으로 감싸는 경우가 많아 그것까지 처리합니다.
- **신뢰도**: 발화량과 "요청한 강조 단어 중 실제로 슬라이드에서 찾은 비율"로 계산합니다(0.4~1.0).
- **실패 격리**: 한 슬라이드에서 LLM이 실패해도 나머지 슬라이드는 계속 만들고, 분석 자체는 READY로 끝납니다.

### Spring Boot (`server-core`)
- `GET /api/v1/lectures/{id}/pages/{n}/annotations` (SPEC §2.1-4). 소유자가 아니거나 필기가 없으면 404
- `SlideAnnotationRepository`, `PageAnnotationResponse`

### web-client
- 현재 슬라이드가 바뀔 때마다 주석을 조회하고, `AnnotationOverlay`가 PDF 위에 하이라이트와 요약 카드(요약·시험 힌트·신뢰도)를 그립니다

## 강조 단어를 좌표에 붙이는 방법

PDF는 단어 단위로 쪼개져 있어서 "최단 경로" 같은 구절은 여러 항목에 걸칩니다. 처음에는 **매칭되는 상자를 전부 합쳤는데, 화면에서 거대한 노란 블록이 나왔습니다.** 같은 단어가 제목과 본문에 모두 있으면 두 줄을 아우르는 상자가 만들어졌기 때문입니다.

고친 규칙:
1. 구절의 첫 토큰과 일치하는 단어를 찾고, **같은 줄(y 좌표 차이 2pt 이내)에 연속으로 놓인 단어들만** 이어 붙입니다.
2. 첫 번째로 찾은 구간만 씁니다(같은 단어가 여러 번 나와도 하나만).
3. 조사가 붙어도 매칭합니다("가중치" ↔ "가중치가").
4. 이미 칠한 영역과 절반 넘게 겹치는 하이라이트는 버립니다(LLM이 "최단 경로"와 "경로"를 함께 주는 경우).

## 이렇게 한 이유

- **LLM 호출을 슬라이드 단위로:** 강의 전체를 한 번에 넣으면 컨텍스트가 길고 슬라이드별 귀속이 흐려집니다. 슬라이드마다 그 슬라이드에 매칭된 발화만 넣으면 프롬프트가 짧고 결과도 명확합니다.
- **OpenAI 호환 SDK 사용:** Ollama와 vLLM 모두 같은 API를 제공하므로 `LLM_BACKEND_URL`만 바꾸면 로컬/EC2를 오갈 수 있습니다.
- **강조 단어를 슬라이드 텍스트로 제한:** LLM이 지어낸 단어는 PDF에 없어 좌표를 붙일 수 없습니다. 프롬프트로 제약하고, 못 찾으면 그냥 버리며, 그 비율을 신뢰도에 반영합니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| **Docker 안 Ollama가 0.3 tok/s** (7B 180초 타임아웃, 3B도 동일) | macOS의 Docker VM은 GPU를 못 쓰고 CPU 추론도 극단적으로 느림. 아키텍처(arm64)나 모델 크기 문제가 아니었음 — 3B 단독으로도 같은 속도 | 사용자 결정으로 **Mac 네이티브 Ollama(brew)** 사용. 같은 7B 모델이 **9.1 tok/s**로 약 30배 빨라짐. `docker-compose.yml`에 macOS 주의사항을 주석으로 남김 |
| 하이라이트가 페이지를 덮는 거대한 블록으로 표시 | 매칭되는 단어 상자를 전부 합쳐 제목 줄과 본문 줄을 아우름 | 같은 줄 연속 구간만 합치고, 첫 번째 구간만 사용, 겹치는 하이라이트 제거 |
| 검증용 PDF에서 한글이 화면에 안 보임 | PyMuPDF `fontname='korea'`는 폰트를 내장하지 않아 PDF.js가 렌더링하지 못함 (텍스트 추출은 정상) | 검증용 PDF를 AppleGothic.ttf를 내장해 생성. 실제 강의 PDF는 보통 폰트가 내장되어 있어 문제없음 |
| 업로드 후 READY 대기가 20초 타임아웃 | 임베딩을 켜면서 첫 PDF 파싱 때 bge-m3 모델 로딩 시간이 추가됨 | 검증 스크립트의 대기 시간을 늘림. 실제 사용에서는 화면이 상태를 폴링하므로 문제없음 |

## 검증 결과

**자동 테스트 (총 67개 통과)**
- FastAPI 35개: 기존 21개 + 주석 서비스 단위 10개 + 배치 통합 2개 + 실패 격리 1개 + 기존 수정
  - JSON 파싱(평문/코드 블록/없음), 단어·구절 좌표 매칭, **줄을 넘어 합치지 않음**, 중복 제거, 첫 번째 위치만 사용, 색상 순서, 신뢰도 계산
  - 통합: 슬라이드별로 해당 발화만 전달되는지, `slide_annotations` 적재, LLM 실패 시에도 전사·정렬 결과 보존
- Spring Boot 27개: 기존 24개 + 주석 조회 3개(소유자 조회, 필기 없는 페이지 404, 타인 404·비로그인 401)
- web-client 5개, lint·타입 검사·빌드 통과

**실제 LLM(qwen2.5:7b-instruct, 네이티브 Ollama)로 전 구간 검증**

| 항목 | 결과 |
|---|---|
| 슬라이드 1장당 생성 시간 | 약 18초 |
| 1쪽 요약 | "오늘은 다익스트라 최단 경로 알고리즘을 공부합니다." (하이라이트 4개, 신뢰도 0.74) |
| 2쪽 요약 | "음수 가중치가 있으면 벨만 포드 알고리즘을 사용해야 합니다." (하이라이트 5개, 신뢰도 1.0) |
| 화면 | 슬라이드 단어 위에 하이라이트가 정확히 정렬되고, 우측 상단에 요약 카드 표시 |
| 별도 단위 확인 | 시험 힌트도 정상 추출됨("중간고사에 자주 나옵니다" → "벨만 포드 알고리즘") |

검증에 사용한 계정, 강의, 파일은 모두 삭제했습니다.

## 남은 일

- **RAG 질의응답(§2.1-5, §2.2-3)**: 마지막 핵심 기능. 슬라이드·전사 임베딩은 이미 준비되어 있음
- `SlideTimeline`에 발화 분량과 시험 힌트 유무를 채울 API가 아직 없음
- 시험 힌트가 없는 슬라이드가 많음 — 프롬프트 조정 여지 있음
- 슬라이드가 45장이면 LLM 호출도 45번(로컬 7B 기준 약 13분). EC2 GPU에서는 훨씬 빠르지만, 병렬 호출이나 진행률 표시가 필요할 수 있음

## 사용법

```bash
# Mac 로컬: 네이티브 Ollama 사용 (Docker 컨테이너는 너무 느림)
brew install ollama && brew services start ollama
ollama pull qwen2.5:7b-instruct

cd ai-engine
LLM_MODEL_NAME=qwen2.5:7b-instruct WHISPER_MODEL_NAME=base \
  source .venv/bin/activate && uvicorn main:app --port 8000
```
