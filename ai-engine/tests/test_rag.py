"""POST /ai/v1/rag/query 테스트 (SPEC §2.2-3).

임베딩과 LLM 은 대체하고, 검색 결과·SSE 이벤트 순서·형식을 검증한다.
"""

import json

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

import main
import services.rag_service as rag_service
from core.database import AsyncSessionLocal

TEST_EMAIL = "rag-test@example.com"


def vector(index: int) -> str:
    values = [0.0] * 1024
    values[index] = 1.0
    return str(values)


async def _execute(statement: str, **params):
    async with AsyncSessionLocal() as session:
        result = await session.execute(text(statement), params)
        await session.commit()
        return result


@pytest_asyncio.fixture
async def lecture_id():
    result = await _execute(
        """
        WITH new_user AS (
            INSERT INTO users (email, password_hash, name)
            VALUES (:email, 'x', '테스트') RETURNING id
        )
        INSERT INTO lectures (user_id, title, status)
        SELECT id, 'RAG 강의', 'READY' FROM new_user RETURNING id
        """,
        email=TEST_EMAIL,
    )
    created = result.scalar_one()
    await _execute(
        """
        INSERT INTO lecture_slides (lecture_id, page_number, slide_text, layout_data, embedding)
        VALUES (:id, 1, '다익스트라 최단 경로', '[]', :v0),
               (:id, 2, '음수 가중치와 벨만 포드', '[]', :v1)
        """,
        id=created,
        v0=vector(0),
        v1=vector(1),
    )
    await _execute(
        """
        INSERT INTO lecture_transcripts
          (lecture_id, start_time_ms, end_time_ms, speaker_text, matched_slide_page, embedding)
        VALUES (:id, 0, 3000, '다익스트라를 설명합니다', 1, :v0),
               (:id, 15000, 18000, '음수 가중치는 벨만 포드를 쓰세요', 2, :v1)
        """,
        id=created,
        v0=vector(0),
        v1=vector(1),
    )
    yield created
    await _execute("DELETE FROM users WHERE email = :email", email=TEST_EMAIL)


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=main.app), base_url="http://test"
    ) as async_client:
        yield async_client


@pytest.fixture
def fake_llm(monkeypatch):
    """질문에 쓰인 컨텍스트를 확인할 수 있도록 호출 인자를 기록한다."""
    calls: list[tuple] = []

    def fake_stream(question, slides, transcripts):
        calls.append((question, [s.page_number for s in slides], [t.start_time_ms for t in transcripts]))
        yield "벨만 "
        yield "포드를 "
        yield "쓰세요."

    monkeypatch.setattr(rag_service, "stream_answer", fake_stream)
    return calls


@pytest.fixture
def fake_embedding(monkeypatch):
    """질문 임베딩이 2쪽(벡터 1)과 가장 가깝도록 고정한다."""
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


async def test_streams_citations_then_tokens_then_done(
    client, lecture_id, fake_llm, fake_embedding
):
    response = await client.post(
        "/ai/v1/rag/query",
        json={"lecture_id": lecture_id, "question": "음수 가중치는 어떻게 하나요?", "top_k": 1},
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")

    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "citations"
    assert names[-1] == "done"
    assert set(names[1:-1]) == {"token"}

    citations = events[0][1]["citations"]
    # top_k=1 이므로 슬라이드 1건 + 전사 1건
    assert [c["source"] for c in citations] == ["SLIDE", "TRANSCRIPT"]
    # 질문 임베딩과 가장 가까운 2쪽이 선택된다
    assert citations[0]["pageNumber"] == 2
    assert citations[1]["pageNumber"] == 2
    assert citations[1]["startTimeMs"] == 15000
    assert citations[0]["startTimeMs"] is None

    assert "".join(payload["text"] for name, payload in events if name == "token") == (
        "벨만 포드를 쓰세요."
    )
    assert events[-1][1] == {"finishReason": "stop"}


async def test_passes_retrieved_context_to_llm(client, lecture_id, fake_llm, fake_embedding):
    await client.post(
        "/ai/v1/rag/query",
        json={"lecture_id": lecture_id, "question": "벨만 포드?", "top_k": 2},
    )

    question, slide_pages, transcript_times = fake_llm[0]
    assert question == "벨만 포드?"
    # 가까운 순서대로 2건씩
    assert slide_pages[0] == 2
    assert len(slide_pages) == 2
    assert transcript_times[0] == 15000


async def test_llm_failure_ends_with_error_event(client, lecture_id, fake_embedding, monkeypatch):
    def boom(question, slides, transcripts):
        raise RuntimeError("LLM 연결 실패")
        yield  # pragma: no cover

    monkeypatch.setattr(rag_service, "stream_answer", boom)

    response = await client.post(
        "/ai/v1/rag/query", json={"lecture_id": lecture_id, "question": "질문"}
    )
    events = parse_sse(response.text)

    assert events[0][0] == "citations"
    assert events[-1] == ("done", {"finishReason": "error"})


async def test_without_embeddings_returns_empty_citations(client, lecture_id, fake_llm, monkeypatch):
    """임베딩이 꺼져 있으면 검색을 건너뛰고 근거 없이 답한다."""
    monkeypatch.setattr(rag_service, "embed_texts", lambda texts: [None] * len(texts))

    response = await client.post(
        "/ai/v1/rag/query", json={"lecture_id": lecture_id, "question": "질문"}
    )
    events = parse_sse(response.text)

    assert events[0][1]["citations"] == []


async def test_rejects_invalid_request(client):
    response = await client.post("/ai/v1/rag/query", json={"lecture_id": 1, "question": ""})
    assert response.status_code == 422
