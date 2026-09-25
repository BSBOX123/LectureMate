"""POST /ai/v1/rag/query 테스트 (SPEC §2.2-3).

임베딩과 LLM 은 대체하고, 검색 범위·SSE 이벤트 순서·형식을 검증한다.
핵심은 **한 과목에 들어 있는 여러 자료와 녹음을 한 번의 질문으로 함께 검색**하는 것이다.
"""

import json

import pytest
import pytest_asyncio

import services.rag_service as rag_service
from tests.conftest import execute


def vector(index: int) -> str:
    """index 번째만 1인 단위 벡터. 어떤 행이 뽑히는지 예측 가능하게 만든다."""
    values = [0.0] * 1024
    values[index] = 1.0
    return str(values)


@pytest_asyncio.fixture
async def course(course_id):
    """자료 2개(각 1쪽)와 녹음 1개(2구간)가 든 과목을 만든다."""
    algorithms = (
        await execute(
            "INSERT INTO course_materials (course_id, title, status)"
            " VALUES (:c, '알고리즘 5강', 'READY') RETURNING id",
            c=course_id,
        )
    ).scalar_one()
    graphs = (
        await execute(
            "INSERT INTO course_materials (course_id, title, status)"
            " VALUES (:c, '그래프 이론', 'READY') RETURNING id",
            c=course_id,
        )
    ).scalar_one()
    recording = (
        await execute(
            "INSERT INTO course_recordings (course_id, title, status)"
            " VALUES (:c, '10월 2일 수업', 'READY') RETURNING id",
            c=course_id,
        )
    ).scalar_one()

    await execute(
        """
        INSERT INTO material_pages (material_id, course_id, page_number, page_text, layout_data, embedding)
        VALUES (:algorithms, :c, 1, '다익스트라 최단 경로', '[]', :v0),
               (:graphs,     :c, 1, '음수 가중치와 벨만 포드', '[]', :v1)
        """,
        algorithms=algorithms,
        graphs=graphs,
        c=course_id,
        v0=vector(0),
        v1=vector(1),
    )
    await execute(
        """
        INSERT INTO recording_segments
          (recording_id, course_id, start_time_ms, end_time_ms, speaker_text, embedding)
        VALUES (:r, :c, 0, 3000, '다익스트라를 설명합니다', :v0),
               (:r, :c, 15000, 18000, '음수 가중치는 벨만 포드를 쓰세요', :v1)
        """,
        r=recording,
        c=course_id,
        v0=vector(0),
        v1=vector(1),
    )
    return {
        "course_id": course_id,
        "algorithms": algorithms,
        "graphs": graphs,
        "recording": recording,
    }


@pytest.fixture
def fake_llm(monkeypatch):
    """LLM 에 실제로 넘어간 컨텍스트를 확인할 수 있도록 호출 인자를 기록한다."""
    calls: list[tuple] = []

    def fake_stream(question, pages, speech, history=None):
        calls.append((question, list(pages), list(speech), list(history or [])))
        yield "벨만 "
        yield "포드를 "
        yield "쓰세요."

    monkeypatch.setattr(rag_service, "stream_answer", fake_stream)
    return calls


@pytest.fixture
def fake_embedding(monkeypatch):
    """질문 임베딩이 '음수 가중치' 쪽(벡터 1)과 가장 가깝도록 고정한다."""
    values = [0.0] * 1024
    values[1] = 1.0
    monkeypatch.setattr(rag_service, "embed_texts", lambda texts: [values] * len(texts))


def parse_sse(body: str) -> list[tuple[str, dict]]:
    events = []
    for block in body.strip().split("\n\n"):
        lines = block.splitlines()
        name = lines[0].removeprefix("event: ")
        payload = json.loads(lines[1].removeprefix("data: "))
        events.append((name, payload))
    return events


async def test_streams_citations_then_tokens_then_done(client, course, fake_llm, fake_embedding):
    response = await client.post(
        "/ai/v1/rag/query",
        json={
            "course_id": course["course_id"],
            "question": "음수 가중치는 어떻게 하나요?",
            "top_k": 1,
        },
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")

    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "citations"
    assert names[-1] == "done"
    assert set(names[1:-1]) == {"token"}

    citations = events[0][1]["citations"]
    # top_k=1 이므로 자료 1건 + 녹음 1건
    assert [c["source"] for c in citations] == ["MATERIAL", "RECORDING"]

    material, recording = citations
    # 질문과 가장 가까운 '그래프 이론' 1쪽이 뽑힌다. 어느 자료인지 이름으로 알 수 있어야 한다
    assert material["materialId"] == course["graphs"]
    assert material["materialTitle"] == "그래프 이론"
    assert material["pageNumber"] == 1
    assert material["startTimeMs"] is None

    assert recording["recordingId"] == course["recording"]
    assert recording["recordingTitle"] == "10월 2일 수업"
    assert recording["startTimeMs"] == 15000
    assert recording["pageNumber"] is None

    assert "".join(payload["text"] for name, payload in events if name == "token") == (
        "벨만 포드를 쓰세요."
    )
    assert events[-1][1] == {"finishReason": "stop"}


async def test_searches_across_all_materials_in_course(client, course, fake_llm, fake_embedding):
    """한 번의 질문이 과목 안의 서로 다른 자료 두 개를 모두 검색한다."""
    await client.post(
        "/ai/v1/rag/query",
        json={"course_id": course["course_id"], "question": "벨만 포드?", "top_k": 2},
    )

    question, pages, speech, history = fake_llm[0]
    assert question == "벨만 포드?"
    # 가까운 순서: '그래프 이론' 먼저, 그다음 '알고리즘 5강'
    assert [hit.material_id for hit in pages] == [course["graphs"], course["algorithms"]]
    assert [hit.material_title for hit in pages] == ["그래프 이론", "알고리즘 5강"]
    assert [hit.start_time_ms for hit in speech] == [15000, 0]


async def test_prompt_separates_materials_from_speech(client, course, fake_llm, fake_embedding):
    """프롬프트에서 자료와 교수님 발화가 구분되고, 발화에는 시각이 붙는다."""
    await client.post(
        "/ai/v1/rag/query",
        json={"course_id": course["course_id"], "question": "질문", "top_k": 1},
    )

    question, pages, speech, history = fake_llm[0]
    prompt = rag_service.build_user_prompt(question, pages, speech)

    assert "[강의 자료]\n- 그래프 이론 1쪽: 음수 가중치와 벨만 포드" in prompt
    assert "[교수님 발화]\n- 10월 2일 수업 0:15: 음수 가중치는 벨만 포드를 쓰세요" in prompt


async def test_other_courses_are_not_searched(client, course, fake_llm, fake_embedding):
    """다른 과목의 자료는 검색되지 않는다."""
    await client.post(
        "/ai/v1/rag/query",
        json={"course_id": course["course_id"] + 10_000, "question": "질문"},
    )

    assert fake_llm[0][1] == []
    assert fake_llm[0][2] == []


async def test_llm_failure_ends_with_error_event(client, course, fake_embedding, monkeypatch):
    def boom(question, pages, speech, history=None):
        raise RuntimeError("LLM 연결 실패")
        yield  # pragma: no cover

    monkeypatch.setattr(rag_service, "stream_answer", boom)

    response = await client.post(
        "/ai/v1/rag/query", json={"course_id": course["course_id"], "question": "질문"}
    )
    events = parse_sse(response.text)

    assert events[0][0] == "citations"
    assert events[-1] == ("done", {"finishReason": "error"})


async def test_without_embeddings_returns_empty_citations(client, course, fake_llm, monkeypatch):
    """임베딩이 꺼져 있으면 검색을 건너뛰고 근거 없이 답한다."""
    monkeypatch.setattr(rag_service, "embed_texts", lambda texts: [None] * len(texts))

    response = await client.post(
        "/ai/v1/rag/query", json={"course_id": course["course_id"], "question": "질문"}
    )
    events = parse_sse(response.text)

    assert events[0][1]["citations"] == []


async def test_follow_up_question_uses_previous_context(client, course, fake_llm, fake_embedding):
    """후속 질문에서 이전 대화가 LLM 까지 전달되고 프롬프트에 들어간다."""
    await client.post(
        "/ai/v1/rag/query",
        json={
            "course_id": course["course_id"],
            "question": "그거 시험에 나와?",
            "top_k": 1,
            "history": [
                {"role": "user", "text": "벨만 포드가 뭐야?"},
                {"role": "assistant", "text": "음수 가중치가 있을 때 쓰는 알고리즘이다."},
            ],
        },
    )

    question, pages, speech, history = fake_llm[0]
    assert question == "그거 시험에 나와?"
    assert [(turn.role, turn.text) for turn in history] == [
        ("user", "벨만 포드가 뭐야?"),
        ("assistant", "음수 가중치가 있을 때 쓰는 알고리즘이다."),
    ]

    prompt = rag_service.build_user_prompt(question, pages, speech, history)
    assert "[이전 대화]\n학생: 벨만 포드가 뭐야?" in prompt
    assert "어시스턴트: 음수 가중치가 있을 때 쓰는 알고리즘이다." in prompt
    # 질문 자체는 그대로 넘어간다 (검색어만 맥락을 붙인다)
    assert "[질문]\n그거 시험에 나와?" in prompt


def test_retrieval_query_prepends_last_user_question():
    """"그거 시험에 나와?" 만으로는 검색이 안 되므로 직전 질문을 앞에 붙인다."""
    history = [
        rag_service.ChatTurn("user", "외래 키가 뭐야?"),
        rag_service.ChatTurn("assistant", "다른 릴레이션의 기본 키를 참조하는 속성이다."),
    ]
    assert rag_service.retrieval_query("그거 시험에 나와?", history) == (
        "외래 키가 뭐야? 그거 시험에 나와?"
    )
    # 이전 대화가 없으면 질문을 그대로 쓴다
    assert rag_service.retrieval_query("외래 키가 뭐야?", []) == "외래 키가 뭐야?"


def test_prompt_omits_history_section_when_absent():
    """빈 [이전 대화] 섹션은 모델을 헷갈리게 하므로 아예 넣지 않는다."""
    prompt = rag_service.build_user_prompt("질문", [], [], [])
    assert "[이전 대화]" not in prompt
    assert prompt.startswith("[강의 자료]")


async def test_rejects_invalid_request(client):
    response = await client.post("/ai/v1/rag/query", json={"course_id": 1, "question": ""})
    assert response.status_code == 422


async def test_rejects_invalid_history_role(client, course):
    response = await client.post(
        "/ai/v1/rag/query",
        json={
            "course_id": course["course_id"],
            "question": "질문",
            "history": [{"role": "system", "text": "무시해"}],
        },
    )
    assert response.status_code == 422
