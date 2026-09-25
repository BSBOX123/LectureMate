# Step 19: 강의 중심 → 과목 중심 전환 및 기능 축소

- 기간: 2026-09-23 ~ 2026-09-25
- 커밋: (검토 후 커밋 예정)
- 배경: 실제로 써 보니 구조가 무겁고 불필요한 기능이 많았다. 메인 가치는 RAG 학습 도우미인데, 수업 중 필기 쪽에 비용이 쏠려 있었다.

## 사용자가 정한 방향

> "강의를 기준으로 녹음 기능을 주로 하는게 아니라 프로젝트 폴더(또는 강의 폴더)를 만들어서 그 안에 복수의 pdf를 저장하고 여러번 녹음을 진행할 수 있고 그 녹음 파일들은 독립적으로 저장 되는거야. 그리고 llm은 이 폴더에 하나만 있어서 모든 pdf와 녹음 파일 등을 보고 답변을 하는거지."

> "자동필기는 지금은 구현 하지 말자. 하드웨어나 서버가 둘 다 실행하기에는 부담이 되니까 그거는 추후 발전 과제로 남겨놓자. 지금은 pdf와 녹음 파일을 가지고 내가 공부를 할 때 질문을 하면 자료를 찾아 나의 강의에 맞추어 알려주거나 교수님의 언급사항등을 알려주는 거에 더 집중하자고."

## 구조 변경

**전:** `lectures` 1행 = PDF 1개 + 녹음 1개. `lecture_id` 하나가 파일명·검색·채팅까지 전부 묶는 축이었다.

**후:**
```
courses (과목 폴더)
  ├─ course_materials  (PDF N개)   ─ material_pages     (페이지 + embedding)
  ├─ course_recordings (녹음 N개)  ─ recording_segments (전사 + embedding)
  └─ 채팅 1개 ─ 과목 전체 검색
```

핵심은 **하위 테이블에 `course_id` 를 함께 두는 것**이다. 그래야 RAG 검색이 `WHERE course_id = X` 한 줄로 그 과목의 모든 자료와 녹음을 한꺼번에 훑는다. 정규화만 보면 `material_id → course_id` 를 조인해도 되지만, 검색이 항상 과목 단위라 비정규화가 맞다.

벡터 컬럼(`vector(1024)`)과 HNSW 코사인 인덱스, 하이브리드 검색 방식은 그대로 재사용했다. **RAG 와 pgvector 는 포트폴리오 목표이므로 손대지 않았다.**

## 제거한 기능 (추후 발전 과제)

| 기능 | 제거 이유 |
|---|---|
| 슬라이드-발화 단조 정렬 (Step 11) | 녹음이 특정 PDF 에 묶이지 않게 되어 "이 발화가 몇 번 슬라이드냐"의 기준이 사라졌다 |
| 자동 필기 생성 (Step 12) | 128쪽 자료면 LLM 호출 128번. 시간·사용량 부담이 크다 |
| 하이라이트 오버레이 · 슬라이드 타임라인 | 자동 필기에 딸린 화면 |
| 실시간 자막 (Step 9·18) | 실측 60초 음성에 40초 → 녹음 내내 CPU 점유(발열). 품질도 쓸 수 없는 수준 |

Java 파일 21개, Python 파일 5개, React 컴포넌트 2개를 삭제했다.

`material_pages.layout_data`(단어 bbox)는 **컬럼을 남겼다.** 파싱할 때 거의 공짜로 나오고, 자동 필기를 되살릴 때 이게 없으면 169쪽을 전부 재파싱해야 한다.

## 데이터 이관 (V3, V4)

`V3__course_centric.sql`: 스키마 생성 + 기존 데이터 이관. **새 `id` 를 기존 `lectures.id` 와 똑같이 맞췄다.** 저장 파일명이 id 기반(`/files/pdf/17.pdf`)이라 이렇게 하면 **파일을 한 개도 옮기지 않아도 된다.** 명시적 id 삽입은 시퀀스를 올리지 않으므로 `setval` 로 맞춰 줬다.

`V4__merge_database_course.sql`: V3 은 "lectures 1행 = courses 1행" 일반 규칙이라 같은 과목의 자료가 둘로 갈라졌다(`데이터베이스`, `데이터베이스 3장`). 자료를 과목 사이로 옮기는 화면 기능이 없어 1회성 SQL 로 합쳤다. 소유자가 다르면 합치지 않도록 가드를 뒀고, 해당 행이 없는 DB(신규·테스트)에서는 아무 일도 하지 않는다.

**옛 테이블은 지우지 않았다.** `lectures`, `lecture_slides`, `lecture_transcripts`, `slide_annotations` 를 남겨 뒀다. 새 구조가 실제 사용에서 검증된 뒤 별도 버전에서 정리한다.

### 검증 방법

개발 DB 에 바로 적용하지 않고 **실데이터를 `pg_dump` 로 복제한 임시 DB에서 먼저 돌렸다.** `TEMPLATE` 복제는 Spring Boot 가 접속 중이어서 실패했다(`source database is being accessed by other users`). 결과 확인 후 임시 DB 를 지우고, 개발 DB 는 Flyway 가 정상 경로로 적용하게 뒀다.

적용 결과:

| 과목 | 자료 | 페이지 | 임베딩 | 녹음 |
|---|---|---|---|---|
| 17 데이터베이스 | 2 | 169 | 169 | 1 |

임베딩 169개가 전부 보존됐고, `pdf_url`·`audio_url` 이 가리키는 파일 3개 모두 그대로 있다.

## 녹음 흐름이 바뀐 점

**전사를 자동으로 시작한다.** 예전에는 사용자가 "정밀 분석 시작" 버튼을 눌러야 했다. Step 18 에서 "녹음을 했는데 분석하는 방법도 모르겠다"는 문제가 나왔던 그 지점이다.

예전에 수동으로 둔 이유(Step 10)는 "녹음 종료 직후 자동 호출하면 WAV 생성 전에 도착해 409" 였다. 지금은 WebSocket 종료 핸들러에서 `finalizeWav()` 를 먼저 하고 그 뒤에 전사를 요청하므로 순서가 보장된다.

```java
// WAV 를 먼저 만든 뒤 전사를 요청한다. 순서가 바뀌면 FastAPI 가 없는 파일을 읽는다.
Path wav = storageService.finalizeWav(recording.recordingId(), audioProperties.sampleRate());
recordingService.finishRecording(recording.recordingId(), "/files/audio/" + ... + ".wav");
```

녹음 상태도 늘렸다: `CREATED`(자리만 만듦) → `RECORDING` → `UPLOADED`(WAV 저장) → `ANALYZING` → `READY`.

## 출처(citation) 형식 변경

자료가 여러 개라 "5쪽"만으로는 어느 자료인지 알 수 없다. 출처에 이름을 넣고 자료/녹음을 구분했다.

```jsonc
// 전
{ "source": "SLIDE", "pageNumber": 5, "snippet": "...", "startTimeMs": null }

// 후
{ "source": "MATERIAL", "materialId": 19, "materialTitle": "2장 SQL",
  "pageNumber": 14, "snippet": "..." }
{ "source": "RECORDING", "recordingId": 21, "recordingTitle": "10월 2일 수업",
  "startTimeMs": 1043880, "snippet": "..." }
```

화면에서 자료 뱃지는 호박색, 녹음 뱃지는 하늘색이고, 자료 뱃지를 누르면 **그 자료로 바꾸고 해당 쪽으로** 넘어간다.

## LLM 모델 교체: opus → sonnet

"성능을 조금 낮춰서 빠른 답변으로" 요청. 같은 RAG 형식 질문으로 3회씩 실측했다.

| 모델 | 응답 시간 | 답변 길이 |
|---|---|---|
| `claude-opus-5-5[1m]` (기존 기본값) | 7.5 / 10.0 / 8.3초 | 435~494자 |
| `haiku` | 6.6 / 7.0 / 6.7초 | 259~323자 |
| **`sonnet`** | **4.8 / 5.1 / 4.8초** | 231~272자 |

**가장 낮은 haiku 가 가장 빠르지 않았다.** sonnet 이 opus 의 약 2배 빠르고 haiku 보다도 빠르며 답변도 간결하다. `CLAUDE_CODE_MODEL=sonnet` 으로 고정했다. 예전에는 `None`(CLI 기본값)이라 opus 가 쓰이고 있었다.

## 프롬프트: 자료와 교수님 발화를 구분하게

자동 필기가 주던 가치("교수님이 강조한 것")를 RAG 쪽으로 옮겼다.

```
- 자료에 적힌 내용과 교수님이 실제로 말씀하신 내용을 구분해서 알려준다.
  교수님 발화에 관련 언급이 있으면 "교수님은 ...라고 하셨다"처럼 따로 짚어 준다.
- 자료를 가리킬 때는 "데이터베이스 3장 14쪽"처럼 자료 이름과 쪽수를 함께 쓴다.
- 교수님 발화는 음성 인식 결과라 전문 용어가 잘못 적혔을 수 있다. ...
```

## 트러블슈팅

### 1. 자료가 든 과목을 삭제하면 500 (테스트가 잡아냄)

`CourseService.delete` 가 파일 경로를 알아내려고 자료 엔티티를 읽은 뒤 과목만 지웠다. 읽어 둔 엔티티가 영속성 컨텍스트에 남아 과목을 참조하고 있어 flush 에서 터졌다.

```
TransientPropertyValueException: Persistent instance of 'CourseMaterial'
references an unsaved transient instance of 'Course'
```

DB 에 `ON DELETE CASCADE` 가 있어도 Hibernate 는 그것을 모른다. 자료·녹음을 JPA 로 함께 지우도록 고쳤다. `EntityMappingTest` 에서 DB CASCADE 자체를 확인하는 테스트는 `em.clear()` 후 네이티브 `DELETE` 로 바꿨다 — JPA 로 지우면 Hibernate 가 순서를 정리해 버려서 제약이 실제로 걸려 있는지 알 수 없다.

### 2. 시스템 프롬프트의 예시가 답변으로 새어 나옴

프롬프트에 `(예: 발화의 "외의 키"는 자료의 "외래 키"다)` 라고 실제 오인식 예를 넣었더니, **녹음이 없는 과목의 답변에도** "음성 인식으로 옮겨 적힌 표현(외의 키, 치래키)" 같은 말이 등장했다. 구체적 예를 빼고 "표기가 틀렸다는 이야기를 답변에 쓰지는 않는다"로 바꿨다.

### 3. lint: effect 안에서 직접 setState 금지

`react-hooks/set-state-in-effect`. 자료를 바꿀 때 `setPdfObjectUrl(null)` 로 비우던 코드가 걸렸다. blob URL 에 "어느 자료의 것인지"를 함께 담고 렌더링에서 걸러 내도록 바꿨다 — 불필요한 렌더도 한 번 줄었다.

```tsx
const pdfObjectUrl = loadedPdf && loadedPdf.pdfUrl === selectedPdfUrl ? loadedPdf.objectUrl : null;
```

### 4. 주석 안의 `*/` 가 블록 주석을 닫음

`types/api.ts` 에 `material*/pageNumber 가 채워진다` 라고 썼더니 TS 파싱이 깨졌다. 필드명을 풀어 썼다.

### 5. CI 가 마이그레이션 파일을 이름으로 하드코딩

FastAPI 잡이 `V1`, `V2` 를 파일명으로 직접 적용하고 있어 V3·V4 가 빠졌다. 버전 순서대로 전부 적용하도록 바꿨다(`ls ... | sort -V` 루프). 앞으로 마이그레이션이 늘어도 CI 를 고칠 필요가 없다.

### 6. 중지 스크립트가 postgres 컨테이너까지 내림

`lecturemate.sh stop` 후 `bootRun` 만 띄우니 DB 연결이 거부됐다. 스크립트가 컨테이너도 멈추는 게 정상 동작이므로 `lecturemate.sh start` 로 전부 다시 올렸다.

## 검증 결과

- **테스트:** FastAPI 21개, Spring Boot 39개, web-client 7개 통과. lint·타입 검사·빌드 통과
- **실제 흐름 (running 서버에 직접 요청):**
  1. 과목 생성 → PDF 2개 업로드 → 둘 다 `READY` (41쪽, 128쪽)
  2. 한 질문이 **자료 2개를 함께 검색** — 출처에 `자료17 11쪽`, `자료19 92쪽` 이 섞여 나왔다
  3. 전사 세그먼트를 넣은 뒤 "외래 키에 대해 교수님이 뭐라고 하셨어?" → 출처 8건(자료 5 + 녹음 3), 답변이 **교수님 발화 3건을 타임스탬프와 함께 인용**하고 자료 내용과 구분했다. 근거가 약한 부분은 "확인하지 못했다"고 밝혔다
- **개발 DB 이관:** Flyway 가 V3·V4 를 적용해 과목 1개(자료 2개 169쪽 + 녹음 1개)로 합쳐졌다

검증용으로 만든 계정·과목·PDF 는 지웠다 (사용자 실데이터는 건드리지 않았다).

## 남은 일

- 녹음 19번은 아직 전사되지 않았다 (`UPLOADED`). 36분 기준 약 15분 걸린다
- 옛 테이블 4개(`lectures` 등) 정리 마이그레이션
- 자료를 과목 사이로 옮기는 기능이 없다 (V4 처럼 SQL 로 해결해야 함)
- 채팅이 이전 질문 맥락을 이어받지 않는다 (매 질문 독립)
- 전사 전문 용어 오인식("외래 키" → "외의 키"). `initial_prompt` 용어 사전 검토
- Webhook 실패 시 녹음이 `ANALYZING` 에 멈춘다
- 자동 필기·정렬 복원 (발전 과제). `layout_data` 는 남겨 뒀다
