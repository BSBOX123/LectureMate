"""POST /ai/v1/recordings/{id}/transcribe 테스트 (SPEC §2.2-2, §2.2-4).

Whisper 추론과 Webhook 호출은 대체하고, DB 적재와 계약을 검증한다.
"""

import pytest

import routers.recordings as recordings_router
from services.stt_service import TranscribedSegment
from tests.conftest import execute


@pytest.fixture
def audio_file(tmp_path):
    path = tmp_path / "recording.wav"
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

    monkeypatch.setattr(recordings_router, "transcribe_file", fake)
    return calls


@pytest.fixture
def webhook_calls(monkeypatch):
    calls: list[tuple] = []

    async def fake(recording_id, status, segment_count, duration_ms):
        calls.append((recording_id, status, segment_count, duration_ms))

    monkeypatch.setattr(recordings_router, "notify_transcription_complete", fake)
    return calls


async def test_accepts_and_stores_segments(
    client, course_id, recording_id, audio_file, fake_transcription, webhook_calls
):
    response = await client.post(
        f"/ai/v1/recordings/{recording_id}/transcribe",
        json={"course_id": course_id, "audio_path": str(audio_file)},
    )

    assert response.status_code == 202
    body = response.json()
    assert body["status"] == "QUEUED"
    assert body["task_id"].startswith(f"transcribe_{recording_id}_")
    assert fake_transcription == [str(audio_file)]

    result = await execute(
        "SELECT start_time_ms, end_time_ms, speaker_text, course_id"
        " FROM recording_segments WHERE recording_id = :id ORDER BY start_time_ms",
        id=recording_id,
    )
    rows = result.all()
    assert len(rows) == 2
    assert rows[0].speaker_text == "오늘은 다익스트라를 공부합니다."
    assert rows[1].start_time_ms == 3000
    # 과목 단위 검색을 위해 세그먼트에도 course_id 가 들어가야 한다
    assert rows[0].course_id == course_id

    # 녹음 길이는 마지막 세그먼트의 끝 시각이다
    assert webhook_calls == [(recording_id, "READY", 2, 6000)]


async def test_retry_replaces_previous_segments(
    client, course_id, recording_id, audio_file, fake_transcription, webhook_calls
):
    for _ in range(2):
        await client.post(
            f"/ai/v1/recordings/{recording_id}/transcribe",
            json={"course_id": course_id, "audio_path": str(audio_file)},
        )

    result = await execute(
        "SELECT count(*) FROM recording_segments WHERE recording_id = :id", id=recording_id
    )
    assert result.scalar_one() == 2


async def test_missing_audio_file_returns_404(client, course_id, recording_id):
    response = await client.post(
        f"/ai/v1/recordings/{recording_id}/transcribe",
        json={"course_id": course_id, "audio_path": "/tmp/does-not-exist.wav"},
    )
    assert response.status_code == 404


async def test_transcription_failure_reports_failed(
    client, course_id, recording_id, audio_file, webhook_calls, monkeypatch
):
    def boom(audio_path, model_name=None):
        raise RuntimeError("전사 실패")

    monkeypatch.setattr(recordings_router, "transcribe_file", boom)

    response = await client.post(
        f"/ai/v1/recordings/{recording_id}/transcribe",
        json={"course_id": course_id, "audio_path": str(audio_file)},
    )

    assert response.status_code == 202
    assert webhook_calls == [(recording_id, "FAILED", 0, 0)]
