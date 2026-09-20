# Step 17: LLM 백엔드를 Claude Code 로 교체

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 배경: 로컬 Ollama 7B 의 품질이 낮아, 로컬 전용으로만 쓰는 만큼 Claude Code(구독)를 LLM 으로 사용하기로 결정

## 무엇을 바꿨나 (그리고 무엇을 안 바꿨나)

```
질문 → bge-m3 임베딩 → pgvector 코사인 검색(HNSW)
     → 슬라이드 + 발화 하이브리드 컨텍스트 → citations 생성
     → [여기만 교체] Ollama 7B  →  Claude Code
```

**RAG 파이프라인과 벡터 DB 는 그대로입니다.** 임베딩, pgvector 검색, 하이브리드 컨텍스트, 출처 추적, SSE 계약(citations → token → done), 프론트엔드 모두 손대지 않았습니다. 바뀐 것은 "생성" 단계 한 곳입니다.

## 구현

| 파일 | 내용 |
|---|---|
| `services/llm_client.py` (신규) | `complete()` / `stream()`. `LLM_PROVIDER` 에 따라 Claude Code 또는 OpenAI 호환(Ollama) 백엔드 사용 |
| `services/rag_service.py` | LLM 호출부만 `llm_client.stream()` 으로 교체. 프롬프트에 "마크다운 없이 평문" 지시 추가 |
| `services/annotation_service.py` | OpenAI 클라이언트 제거, `llm_client.complete()` 사용 |
| `core/config.py`, `.env.example` | `LLM_PROVIDER`(기본 `claude-code`), `CLAUDE_CODE_COMMAND`, `CLAUDE_CODE_MODEL` |
| `tests/conftest.py` | 테스트가 실제 LLM 을 호출하지 않도록 실행 불가 명령 지정 |

### Claude Code 호출 방식

```bash
claude -p --allowed-tools "" --system-prompt "<지시>" \
  --output-format stream-json --include-partial-messages --verbose --strict-mcp-config
```

- **`--allowed-tools ""`**: 강의 질의응답에 파일 읽기·실행 도구는 필요 없습니다. 원천 차단합니다.
- **`--strict-mcp-config`** + **빈 임시 디렉토리에서 실행**: 프로젝트의 `CLAUDE.md`, 훅, MCP 설정이 딸려 오지 않게 합니다.
- **`stream-json` + `--include-partial-messages`**: `content_block_delta` 의 `text_delta` 만 뽑아 기존 SSE 토큰 스트리밍에 그대로 연결합니다. `thinking_delta`(사고 과정)는 버립니다.
- 프롬프트는 stdin 으로 넘깁니다. 명령줄 인자로 넘기면 길이 제한과 이스케이프 문제가 생깁니다.

## 품질 비교 (같은 강의, 같은 음성)

**자동 필기 요약**

| | 결과 |
|---|---|
| Ollama 7B | "오늘은 다익스트라 최단 경로 알고리즘을 공부합니다." (전사 문장을 거의 그대로 반복) |
| **Claude Code** | "오늘 강의 주제는 다익스트라 최단 경로 알고리즘으로, 그래프에서 최단 경로를 구하는 방법을 다룬다고 안내하셨다." (슬라이드 내용과 발화를 통합) |

**RAG 답변** (질문: "음수 가중치가 있으면 어떻게 해야 하나요?")

| | 결과 |
|---|---|
| Ollama 7B | "음수 가중치가 있으면 벨만 포드 알고리즘을 사용해야 합니다." |
| **Claude Code** | "음수 가중치가 있으면 벨만 포드 알고리즘을 사용해야 합니다. 2쪽에서 다루는 내용이고, 교수님도 2쪽에서 '...'라고 말씀하셨습니다. 다만 벨만 포드를 왜 써야 하는지에 대한 설명은 주어진 자료에 없어서 확인하지 못했습니다." |

쪽수를 인용하고, **자료에 없는 내용은 없다고 밝히는** 점이 큰 차이입니다.

**바뀌지 않는 것:** 전사 오류("음수" → "음소")는 Whisper 문제라 그대로입니다.

## 이렇게 한 이유

- **검색은 계속 우리가 한다:** Claude Code 가 직접 DB 를 뒤지게 하면 근거를 알 수 없어 출처 뱃지와 "해당 슬라이드로 이동" 기능이 깨집니다. 검색·citations 는 유지하고 생성만 맡깁니다.
- **`LLM_PROVIDER` 로 전환 가능하게:** Max 구독도 5시간 단위 사용량 한도가 있습니다. 한도에 걸리거나 오프라인이면 `LLM_PROVIDER=ollama` 로 되돌릴 수 있습니다.
- **테스트에서는 LLM 을 아예 막음:** 테스트가 실제로 구독 사용량을 쓰고 있었습니다(발견 후 수정). 이제 실행 불가 명령을 지정해 빠르게 실패시키고, 필요한 테스트는 함수를 직접 대체합니다.
- **평문 응답 지시:** Claude 는 기본적으로 마크다운(`**강조**`)을 씁니다. 화면이 평문 렌더링이라 별표가 그대로 보여서 프롬프트에 명시했습니다.

## 장애 시 동작

Claude Code 가 멈추면(로그인 만료, 오프라인, 사용량 한도) **AI 생성 기능만 멈춥니다.**

| 기능 | 영향 |
|---|---|
| 로그인, 업로드, PDF 보기, 녹음, 실시간 자막, 전사, 정렬, 벡터 검색 | 정상 |
| 자동 필기 | 해당 슬라이드만 건너뛰고 분석은 READY 로 완료 |
| 채팅 | 출처는 표시되고 `done { finishReason: "error" }` |

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| **테스트가 실제 Claude Code 를 호출** (`test_aligns_segments_to_slides` 가 예상과 다른 값 반환) | 기본 provider 를 claude-code 로 바꾸자 테스트의 LLM 호출이 실제로 성공해 버림 | `conftest.py` 에서 `CLAUDE_CODE_COMMAND` 를 실행 불가 경로로 지정 |
| 답변에 `**` 가 그대로 표시 | Claude 의 기본 마크다운 출력 | 시스템 프롬프트에 평문 지시 추가 |

## 검증 결과

- FastAPI 테스트 45개 통과 (신규 `test_llm_client.py` 5개 포함: 텍스트 델타 추출, thinking 무시, 도구 차단 확인, 실패 시 예외)
- 브라우저 전 구간: 업로드 → 녹음 → 실시간 자막 → 분석 → 자동 필기 → 질의응답까지 동작 확인
- 자동 필기 신뢰도 0.8 / 0.9, 하이라이트 4개 정상 매칭
- 응답 시간: 짧은 질문 기준 약 4초(프로세스 시작 포함)

## 사고 기록: 사용자 데이터 삭제

검증 데이터를 정리하면서 `rm -rf ~/lecturemate/storage/pdf` 로 폴더를 통째로 지웠는데, 그 안에 **사용자가 직접 업로드한 강의(id=17 "데이터베이스", 41쪽)의 PDF 원본**이 함께 있었습니다.

- DB 데이터(슬라이드 41쪽, 텍스트·좌표·임베딩)는 남아 검색은 동작하지만, PDF 파일은 복구 불가
- 이후에는 **내가 만든 강의 ID 의 파일만 지정해서 삭제**한다는 규칙을 기억에 저장

## 남은 일

- 프로세스 시작에 2~3초가 걸립니다. 세션 재사용(`--resume`)으로 줄일 여지가 있습니다
- 슬라이드 45장이면 LLM 호출도 45번이라 사용량이 많이 듭니다. 병렬 호출이나 묶음 처리 검토
- 시험 힌트는 이번 테스트 음원에 "시험" 언급이 없어 비어 있습니다. 실제 강의로 확인 필요
