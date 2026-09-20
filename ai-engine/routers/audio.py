"""실시간 스트림 청크 STT 및 배치 정밀 분석 라우터.

- SPEC §2.2-2: POST /ai/v1/lectures/{lecture_id}/analyze-batch (미구현)
- SPEC §2.2-5: WS   /ai/v1/lectures/{lecture_id}/audio-stream
"""

import logging

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

from core.config import settings
from services.stt_service import transcribe_pcm

router = APIRouter(tags=["audio"])
log = logging.getLogger(__name__)

BYTES_PER_SAMPLE = 2  # 16bit


def _chunk_bytes() -> int:
    return int(settings.audio_sample_rate * settings.realtime_chunk_seconds) * BYTES_PER_SAMPLE


def _duration_ms(pcm_bytes: int) -> int:
    return int(pcm_bytes / BYTES_PER_SAMPLE / settings.audio_sample_rate * 1000)


@router.websocket("/lectures/{lecture_id}/audio-stream")
async def audio_stream(websocket: WebSocket, lecture_id: int) -> None:
    """Spring Boot 가 중계한 PCM 청크를 받아 프리뷰 자막을 돌려준다 (SPEC §2.2-5).

    입력: 16kHz 모노 16bit LE PCM 바이너리
    출력: {"type": "TRANSCRIPT_PREVIEW", "startTimeMs", "endTimeMs", "text"}
    """
    await websocket.accept()
    log.info("audio-stream 연결 lecture_id=%s", lecture_id)

    buffer = bytearray()
    elapsed_ms = 0
    try:
        while True:
            buffer.extend(await websocket.receive_bytes())
            if len(buffer) < _chunk_bytes():
                continue

            pcm = bytes(buffer)
            buffer.clear()
            duration = _duration_ms(len(pcm))
            # Whisper 추론은 CPU 를 오래 쓰므로 이벤트 루프를 막지 않도록 스레드로 넘긴다
            text = await run_in_threadpool(transcribe_pcm, pcm)
            if text:
                await websocket.send_json(
                    {
                        "type": "TRANSCRIPT_PREVIEW",
                        "startTimeMs": elapsed_ms,
                        "endTimeMs": elapsed_ms + duration,
                        "text": text,
                    }
                )
            elapsed_ms += duration
    except WebSocketDisconnect:
        log.info("audio-stream 종료 lecture_id=%s 총 %sms", lecture_id, elapsed_ms)
