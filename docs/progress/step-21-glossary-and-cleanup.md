
# Step 21: 전사 용어 사전 + 옛 테이블 정리

- 기간: 2026-09-25
- 커밋: (검토 후 커밋 예정)
- 배경: 전사가 전문 용어를 자주 틀린다. 그리고 Step 19 에서 남겨 둔 강의 중심 시절 테이블을 정리할 때가 됐다.

## 1. 전사 용어 사전 (`glossary_service`)

### 착안점

실측된 오인식은 이런 것들이었다 (Step 18).

| 교수님이 말한 것 | 전사 결과 |
|---|---|
| 외래 키 | 외의 키 |
| 기본 키 | 주키 |

**정답은 이미 그 과목의 PDF 안에 있다.** 자료 텍스트에서 용어를 뽑아 Whisper 의 `initial_prompt` 로
미리 알려 주면 같은 발음을 그 용어로 옮길 가능성이 올라간다. 과목이 자료와 녹음을 함께 담는
Step 19 구조라서 가능해진 일이다.

### 용어 추출에 LLM 을 쓴 이유

PDF 에서 뽑은 한국어는 띄어쓰기가 깨져 있는 경우가 많다.

```
DISTINCT를이용하여중복제거
릴레이션의특징(3) 속성의명칭과값 릴레이션내에서속성들의명칭은상이해야하지만...
```

빈도 기반 토큰 추출로는 쓸 만한 용어가 나오지 않는다. 전사 자체가 수십 분 걸리는 작업이라
LLM 호출 한 번(실측 11.6초)은 비용이 되지 않는다.

### 시행착오 3번

**(1) 글자 수 한도에 잘려 정작 필요한 용어가 빠졌다.**

```
이 강의에서 쓰는 용어: 관계형 모델, Relational Model, 관계형 데이터베이스, 릴레이션, Relation,
테이블, Table, 스키마, 인스턴스, 레코드, 필드, 속성, Attribute, 열, Column, 차수, Degree,
행, Row, 튜플, Tuple, Cardinality, 기수, 도메인, Domain, 후보 키, 주 키   (196자, 여기서 잘림)
```

한국어와 영어 원어가 짝으로 들어가 예산을 절반 낭비했고, "외래 키"는 한도를 넘어 빠졌다.
→ 한국어 용어만 쓰고, 개수를 25개로 제한했다.

**(2) 간격 샘플링이 핵심 페이지를 건너뛰었다.**

169쪽에서 60쪽만 고르게 뽑았는데, `Foreign Key` 가 적힌 페이지는 과목 전체에서 **2쪽뿐**이라
샘플에서 빠졌다. → 200쪽까지 전부 넣도록 바꿨다. 169쪽 × 300자면 6만 자 정도로 sonnet 에
한 번에 들어간다.

**(3) "자료 표기를 그대로 쓰라"는 규칙이 오히려 방해였다.**

이 강의 슬라이드는 `Foreign Key` / `Primary Key` 를 **영어로** 적고, 교수님은 **한국어로 말한다.**
용어 사전은 "말을 받아 적는" 데 쓰이므로 영어 표기를 그대로 넣으면 아무 도움이 안 된다.
→ "자료에 영어로 적혀 있어도 교수님이 한국어로 부르는 표준 용어를 넣는다"로 바꿨다.

최종 결과 (실제 데이터베이스 과목, 169쪽):

```
이 강의에서 쓰는 용어: 튜플, 차수, 기수, 도메인, 릴레이션, 셀렉트, 프로젝트, 후보 키, 외래 키,
기본 키, 원자성, 카티전 곱, 차집합, 합집합, 교집합, 자연 조인, 외부 조인, 관계 대수, DISTINCT,
GROUP BY, HAVING, 서브쿼리, 인라인 뷰, TRUNCATE, 뷰   (166자)
```

### Whisper 가 프롬프트를 버리는 지점

`mlx_whisper/transcribe.py` 를 읽어 확인한 것.

```python
if not condition_on_previous_text or result.temperature > 0.5:
    # do not feed the prompt tokens if a high temperature was used
    prompt_reset_since = len(all_tokens)
```

반복 루프를 막으려고 `condition_on_previous_text=False` 로 두었는데(Step 18), 그 때문에
**첫 30초 window 이후 `initial_prompt` 이 버려진다.** 다행히 지금은 구간을 나눠 `transcribe()` 를
여러 번 호출하는 구조라, 구간마다 사전이 다시 들어간다.

### 실측 결과: 도입하지 않는다

실제 녹음(`19.wav`)의 16~20분 구간(4분, 외래 키·기본 키를 설명하는 대목)으로 측정했다.
이 구간을 고른 이유는 Step 18 에서 오인식이 관찰된 바로 그 내용이기 때문이다.

| | 창 | 시간 | 세그먼트 | `외래키`·`주키`(정답) | `외의 key`·`주 key`(오류) |
|---|---|---|---|---|---|
| **A 사전 없음** | 300초 | 165.3초 | 102 | **5회** | 0 |
| B 사전(영어 포함) | 300초 | 264.9초 | 44 | 1 | 4 |
| C 사전(영어 포함) | 120초 | 149.9초 | 70 | 1 | 4 |
| D 사전(한국어만) | 300초 | 129.5초 | 45 | 1 | 4 |
| E 사전(한국어만) | 120초 | 73.9초 | 44 | 1 | 4 |

**사전을 넣은 4종 모두 핵심 용어를 틀렸고, 사전 없는 A만 맞혔다.**

```
A 사전없음: "d는 외래키나 주키랑 똑같은 거야. 각 개의 룰 수도 있고 외래키는 주키와 다른 룰 수도 있고"
B 사전있음: "d는 외의 key나 주 key 이름이 똑같은거에요"
E 사전있음: "외의 key 이름과 주 key 이름이 다르게 되어야 돼요"
```

"사전에 넣은 영어 SQL 예약어(DISTINCT, GROUP BY 등)가 영어 혼입을 유도했다"는 가설을 세우고
영어를 전부 뺀 D·E 를 돌렸는데 **결과가 같았다.** 가설이 틀렸다. 원인은 영어 혼입이 아니라,
프롬프트가 디코딩 경로를 바꿔 다른(더 나쁜) 후보로 가게 만드는 것으로 보인다.

→ `STT_GLOSSARY_ENABLED` **기본값을 꺼뒀다.** 코드는 남겼다. 이 강의 자료는 핵심 용어를
영어로 적어 두었고 한국어는 띄어쓰기가 깨져 있어 사전 품질 자체가 좋지 않았다. 용어가 한국어로
또박또박 적힌 자료라면 결과가 다를 수 있고, 측정 근거가 여기 남아 있으니 다시 판단할 수 있다.

### 예상 못 한 소득: 전사 구간 크기

사전과 무관하게 `BATCH_WINDOW_SECONDS` 300초 → 120초가 **속도와 품질을 모두** 개선했다.

| | 시간 | 쓰레기 구간 (짧은 토큰 6회 이상 연속) |
|---|---|---|
| 300초 | 129.5 ~ 264.9초 | 최장 **449자** |
| 120초 | 73.9 ~ 149.9초 | 최장 29 ~ 47자 |

Whisper 가 무의미한 토큰을 뱉기 시작하면 그 구간이 끝날 때까지 이어지는데, 구간을 짧게 끊으면
그 피해가 그 구간에서 멈춘다. Step 18 에서 반복 루프를 막으려고 도입한 구간 분할이었는데,
더 짧게 하면 더 좋다는 것을 이번에 확인했다.

## 2. 옛 테이블 정리 (`V5__drop_legacy_lecture_tables.sql`)

Step 19 에서 남겨 둔 `lectures`, `lecture_slides`, `lecture_transcripts`, `slide_annotations` 를
지웠다. 지우기 전에 이관 결과를 엄격하게 확인했다.

```sql
-- 내용·layout_data·임베딩 유무 불일치: 0
-- 임베딩 값 불일치(코사인 거리 > 1e-9): 0
select count(*) from lecture_slides s
full outer join material_pages p on p.material_id = s.lecture_id and p.page_number = s.page_number
where s.id is null or p.id is null
   or p.page_text is distinct from s.slide_text
   or p.layout_data::text is distinct from s.layout_data::text
   or (p.embedding is null) <> (s.embedding is null);
```

- `lecture_slides` 169행 → `material_pages` 169행, **임베딩 값까지 동일**
- `lecture_transcripts`, `slide_annotations` 는 0행 (정렬·자동 필기를 돌린 적이 없다)
- 코드에 남은 참조 없음
- 되살릴 수 있게 `~/lecturemate/backup/legacy-lecture-tables-20260925.sql` (2.7MB) 을 남겼다

`slide_annotations` 가 `lecture_slides` 를 참조하므로 삭제 순서를 맞췄다.
