# Step 11: 슬라이드-음성 정렬 (Monotonic DP) + 임베딩 활성화

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §4.2 `alignment_service`. 전사 세그먼트에 슬라이드 번호를 붙이고, bge-m3 임베딩을 실제로 켠다

## 핵심 아이디어

강의는 슬라이드를 앞에서 뒤로 넘기며 진행합니다. 그래서 **시간 순 발화에 붙는 슬라이드 번호는 절대 뒤로 돌아가지 않습니다.** 이 제약(단조성)을 동적 계획법으로 강제하면, 중간에 한두 문장이 엉뚱한 슬라이드와 비슷해도 전체 흐름이 흐트러지지 않습니다.

```
dp[i][p] = 유사도(세그먼트 i, 슬라이드 p) + max(dp[i-1][p'] for p' <= p) - (슬라이드를 옮겼으면 전환 패널티)
```

- `p' <= p` 조건이 "뒤로 갈 수 없다"는 제약입니다.
- prefix max를 누적하며 계산해 O(세그먼트 × 슬라이드)로 끝납니다.
- 전환 패널티(기본 0.05)는 유사도가 비슷할 때 같은 슬라이드에 머무르게 합니다. 슬라이드가 한 문장마다 튀는 것을 막습니다.
- 역추적(backtrack)으로 최적 경로를 복원합니다.

## 구현한 것

| 파일 | 내용 |
|---|---|
| `services/alignment_service.py` | `cosine_similarity_matrix()`, `align_monotonic()`(DP), `align_segments_to_pages()` |
| `routers/audio.py` | 배치 분석에 임베딩·정렬 단계 추가, `_slide_embeddings()` |
| `core/config.py`, `.env.example` | `EMBEDDING_ENABLED` 기본값을 **true**로 변경 |
| `tests/test_alignment_service.py` | 정렬 알고리즘 단위 테스트 6개 |
| `tests/conftest.py` | 테스트에서는 임베딩 비활성 (모델 다운로드 회피) |

### 바뀐 배치 분석 흐름

```
전사(Whisper)
  → 세그먼트 임베딩(bge-m3)
  → 슬라이드 임베딩 로드 (없으면 이 시점에 만들어 DB에 채움)
  → 단조 DP 정렬 → 세그먼트별 슬라이드 번호
  → lecture_transcripts 적재 (matched_slide_page, embedding 포함)
  → Webhook: totalPagesAnalyzed=매칭된 슬라이드 수, matchedTranscriptSegments=매칭된 세그먼트 수
```

## 이렇게 한 이유

- **임베딩 기본 활성화:** 정렬과 RAG 모두 임베딩이 있어야 동작합니다. Step 6에서는 모델 다운로드(약 2GB)를 피하려고 꺼 뒀지만, 이제 필요한 시점입니다. `EMBEDDING_ENABLED=false`로 두면 임베딩과 정렬을 건너뛰고 기존처럼 전사만 합니다.
- **슬라이드 임베딩을 정렬 시점에 보충:** 임베딩이 꺼진 상태에서 파싱한 강의는 슬라이드 임베딩이 비어 있습니다. 정렬할 때 발견하면 그 자리에서 만들어 DB에 채웁니다. 다시 업로드하지 않아도 됩니다.
- **테스트에서는 임베딩 비활성:** 모델을 내려받으면 테스트가 2분 가까이 걸립니다(실제로 겪었습니다). 정렬 로직은 가짜 벡터로 충분히 검증할 수 있어서, `conftest.py`에서 꺼 두고 필요한 테스트만 `embed_texts`를 대체합니다.
- **전환 패널티를 상수로 분리:** 값에 따라 결과가 달라지는 파라미터라 테스트로 동작을 고정해 뒀습니다(`test_switch_penalty_prefers_staying_on_a_slide`).

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| 임베딩을 켜자 기존 테스트 2개 실패, 실행 시간 1초 → 116초 | 테스트가 실제 bge-m3 모델을 내려받아 추론 | `conftest.py`에서 `EMBEDDING_ENABLED=false` 고정, 기대값 수정, 정렬 테스트는 가짜 임베딩 사용 |

## 검증 결과

**자동 테스트 (총 50개 통과)**
- FastAPI 21개: 기존 14개 + 정렬 단위 테스트 6개 + 정렬 통합 테스트 1개
  - 세그먼트가 각자 맞는 슬라이드에 붙는다
  - **유사도가 앞 슬라이드를 가리켜도 뒤로 돌아가지 않는다**
  - 슬라이드가 하나면 전부 그 슬라이드
  - 빈 입력, 0 벡터 처리
  - 전환 패널티가 클수록 같은 슬라이드에 머무른다
  - 통합: 슬라이드 임베딩이 있으면 `matched_slide_page`가 채워지고 Webhook 값이 맞는다
- Spring Boot 24개, web-client 5개 (변경 없음)

**실제 임베딩으로 전 구간 검증** (한국어 슬라이드 2장 + 한국어 음성, 헤드리스 Chrome)

슬라이드: 1쪽 "다익스트라 최단 경로 알고리즘", 2쪽 "음수 가중치와 벨만 포드 알고리즘"

| 시간 | 전사 내용 | 매칭된 슬라이드 |
|---|---|---|
| 0초 | 오늘은 다익스트라 최단 경로 알고리즘을 공부합니다. | **1쪽** ✓ |
| 3초 | 음소 가중치가 있으면 벨만 포드를 사용해야 합니다. | **2쪽** ✓ |
| 7초 | 오늘은 다익스트라 최단 경로 알고리즘을 공부합니다. | **2쪽** (단조성 제약이 지켜짐) |
| 10초 | 음소 가중 | 2쪽 |

세 번째 문장은 내용상 1쪽이지만, 강의가 이미 2쪽으로 넘어갔으므로 되돌아가지 않았습니다. **알고리즘이 의도대로 동작한다는 증거입니다.** (테스트 음원을 반복 재생했기 때문에 같은 문장이 다시 나왔습니다.)

전사 세그먼트와 슬라이드 모두 `embedding` 컬럼이 채워졌고, Webhook은 `pages=2 segments=4`로 통보했습니다.

검증에 사용한 계정과 파일은 모두 삭제했습니다.

## 남은 일

- **자동 필기 생성(`annotation_service`)**: 매칭된 발화를 LLM에 넣어 요약·시험 힌트·강조 단어를 만들고 `slide_annotations`에 저장 — 다음 단계. **여기서 처음으로 LLM(EC2 Ollama)이 필요합니다.**
- 주석 조회 API(§2.1-4)와 화면 연결
- 정렬 품질 평가 수단이 없습니다. 실제 강의 녹음으로 확인이 필요합니다
- 전환 패널티(0.05)는 임의 값입니다. 실제 데이터로 조정해야 합니다

## 사용법

```bash
# 임베딩을 끄고 싶으면 (모델 다운로드 회피)
EMBEDDING_ENABLED=false uvicorn main:app --port 8000

# 정렬 결과 확인
docker exec lecturemate-postgres psql -U postgres -d lecturemate \
  -c "select start_time_ms/1000 as sec, matched_slide_page, left(speaker_text,40) from lecture_transcripts where lecture_id=<ID> order by start_time_ms"
```
