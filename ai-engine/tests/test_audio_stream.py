"""WS /ai/v1/lectures/{id}/audio-stream 테스트 (SPEC §2.2-5).

Whisper 모델 다운로드/추론 없이 검증하려고 transcribe_pcm 을 대체한다.
"""

import pytest
from fastapi.testclient import TestClient

import main
import routers.audio as audio_router
from core.config import settings

SAMPLE_RATE = settings.audio_sample_rate
ONE_SECOND = b"\x00\x00" * SAMPLE_RATE


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(audio_router, "transcribe_pcm", lambda pcm: "테스트 자막")
    with TestClient(main.app) as test_client:
        yield test_client


def test_sends_preview_after_chunk_threshold(client):
    with client.websocket_connect("/ai/v1/lectures/1/audio-stream") as ws:
        # 3초 미만은 아직 전사하지 않는다
        ws.send_bytes(ONE_SECOND)
        ws.send_bytes(ONE_SECOND)
        # 3초를 채우면 프리뷰가 온다
        ws.send_bytes(ONE_SECOND)
        event = ws.receive_json()

    assert event == {
        "type": "TRANSCRIPT_PREVIEW",
        "startTimeMs": 0,
        "endTimeMs": 3000,
        "text": "테스트 자막",
    }


def test_timestamps_accumulate_across_chunks(client):
    with client.websocket_connect("/ai/v1/lectures/1/audio-stream") as ws:
        for _ in range(6):
            ws.send_bytes(ONE_SECOND)
        first = ws.receive_json()
        second = ws.receive_json()

    assert (first["startTimeMs"], first["endTimeMs"]) == (0, 3000)
    assert (second["startTimeMs"], second["endTimeMs"]) == (3000, 6000)


def test_silence_without_text_sends_nothing(client, monkeypatch):
    monkeypatch.setattr(audio_router, "transcribe_pcm", lambda pcm: "")
    with client.websocket_connect("/ai/v1/lectures/1/audio-stream") as ws:
        for _ in range(3):
            ws.send_bytes(ONE_SECOND)
        ws.send_bytes(b"\x00\x00")  # 연결이 살아 있는지 확인용
    # 예외 없이 종료되면 성공 (프리뷰 이벤트는 오지 않는다)
