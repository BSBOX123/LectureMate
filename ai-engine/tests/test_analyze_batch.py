"""POST /ai/v1/lectures/{id}/analyze-batch 테스트 (SPEC §2.2-2, §2.2-4).

Whisper 추론과 Webhook 호출은 대체하고, DB 적재와 계약을 검증한다.
"""

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

import main
import routers.audio as audio_router
from core.database import AsyncSessionLocal
from services.stt_service import TranscribedSegment

TEST_EMAIL = "analyze-batch-test@example.com"


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
        SELECT id, '배치 분석 강의', 'ANALYZING' FROM new_user
        RETURNING id
        """,
        email=TEST_EMAIL,
    )
    created = result.scalar_one()
    yield created
    await _execute("DELETE FROM users WHERE email = :email", email=TEST_EMAIL)


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=main.app), base_url="http://test"
    ) as async_client:
        yield async_client


@pytest.fixture
def audio_file(tmp_path):
    path = tmp_path / "lecture.wav"
    path.write_bytes(b"RIFF....WAVEfmt ")  # 내용은 쓰이지 않는다 (전사를 대체하므로)
    return path


@pytest.fixture
def fake_transcription(monkeypatch):
    calls: list[str] = []

    def fake(audio_path, model_name=None):
        calls.append(str(audio_path))
        return [
            TranscribedSegment(0, 3000, "오늘은 다익스트라를 공부합니다."),
            TranscribedSegment(3000, 6000, "음수 가중치는 벨만 포드를 씁니다."),
        ]

    monkeypatch.setattr(audio_router, "transcribe_file", fake)
    return calls


@pytest.fixture
def webhook_calls(monkeypatch):
    calls: list[tuple] = []

    async def fake(lecture_id, status, total_pages, segments):
        calls.append((lecture_id, status, total_pages, segments))

    monkeypatch.setattr(audio_router, "notify_analysis_complete", fake)
    return calls


async def test_accepts_and_stores_transcripts(
    client, lecture_id, audio_file, fake_transcription, webhook_calls
):
    response = await client.post(
        f"/ai/v1/lectures/{lecture_id}/analyze-batch", json={"audio_path": str(audio_file)}
    )

    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "QUEUED"
    assert body["task_id"].startswith(f"batch_{lecture_id}_")

    # BackgroundTasks 는 응답 이후 실행된다 (httpx ASGITransport 가 끝까지 처리)
    rows = (
        await _execute(
            "SELECT start_time_ms, end_time_ms, speaker_text, matched_slide_page"
            " FROM lecture_transcripts WHERE lecture_id = :id ORDER BY start_time_ms",
            id=lecture_id,
        )
    ).all()
    assert len(rows) == 2
    assert rows[0].speaker_text == "오늘은 다익스트라를 공부합니다."
    assert rows[1].start_time_ms == 3000
    # 슬라이드 정렬은 다음 단계라 아직 비어 있다
    assert rows[0].matched_slide_page is None

    assert fake_transcription == [str(audio_file)]
    # 슬라이드가 없으면 정렬을 건너뛰므로 매칭 수는 0
    assert webhook_calls == [(lecture_id, "READY", 0, 0)]


async def test_reruns_replace_previous_transcripts(
    client, lecture_id, audio_file, fake_transcription, webhook_calls
):
    for _ in range(2):
        await client.post(
            f"/ai/v1/lectures/{lecture_id}/analyze-batch", json={"audio_path": str(audio_file)}
        )

    count = (
        await _execute(
            "SELECT count(*) FROM lecture_transcripts WHERE lecture_id = :id", id=lecture_id
        )
    ).scalar_one()
    assert count == 2


async def test_aligns_segments_to_slides(
    client, lecture_id, audio_file, fake_transcription, webhook_calls, monkeypatch
):
    """슬라이드 임베딩이 있으면 각 세그먼트에 슬라이드 번호가 단조 증가로 붙는다."""
    await _execute(
        """
        INSERT INTO lecture_slides (lecture_id, page_number, slide_text, layout_data, embedding)
        VALUES (:id, 1, '다익스트라', '[]', :v1), (:id, 2, '벨만 포드', '[]', :v2)
        """,
        id=lecture_id,
        v1=str([1.0] + [0.0] * 1023),
        v2=str([0.0, 1.0] + [0.0] * 1022),
    )

    # 첫 세그먼트는 슬라이드 1, 두 번째는 슬라이드 2 와 같은 방향의 벡터
    def fake_embed(texts):
        first = [1.0] + [0.0] * 1023
        second = [0.0, 1.0] + [0.0] * 1022
        return [first, second][: len(texts)]

    monkeypatch.setattr(audio_router, "embed_texts", fake_embed)

    await client.post(
        f"/ai/v1/lectures/{lecture_id}/analyze-batch", json={"audio_path": str(audio_file)}
    )

    rows = (
        await _execute(
            "SELECT matched_slide_page, embedding IS NOT NULL AS has_embedding"
            " FROM lecture_transcripts WHERE lecture_id = :id ORDER BY start_time_ms",
            id=lecture_id,
        )
    ).all()
    assert [row.matched_slide_page for row in rows] == [1, 2]
    assert all(row.has_embedding for row in rows)
    # totalPagesAnalyzed = 매칭된 슬라이드 수, matchedTranscriptSegments = 매칭된 세그먼트 수
    assert webhook_calls == [(lecture_id, "READY", 2, 2)]


async def test_missing_audio_file_returns_404(client, lecture_id):
    response = await client.post(
        f"/ai/v1/lectures/{lecture_id}/analyze-batch",
        json={"audio_path": "/tmp/nope-does-not-exist.wav"},
    )
    assert response.status_code == 404


async def test_failure_notifies_webhook_with_failed(
    client, lecture_id, audio_file, webhook_calls, monkeypatch
):
    def boom(audio_path, model_name=None):
        raise RuntimeError("전사 실패")

    monkeypatch.setattr(audio_router, "transcribe_file", boom)

    response = await client.post(
        f"/ai/v1/lectures/{lecture_id}/analyze-batch", json={"audio_path": str(audio_file)}
    )

    assert response.status_code == 202
    assert webhook_calls == [(lecture_id, "FAILED", 0, 0)]
