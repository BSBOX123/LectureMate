"""실시간 스트림 청크 STT 및 배치 정밀 분석 라우터.

- SPEC §2.2-2: POST /ai/v1/lectures/{lecture_id}/analyze-batch
- SPEC §2.2-5: WS   /ai/v1/lectures/{lecture_id}/audio-stream
"""

import logging
from datetime import date
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, WebSocket, WebSocketDisconnect, status
from pydantic import BaseModel
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.concurrency import run_in_threadpool

from core.config import settings
from core.database import AsyncSessionLocal, get_session
from core.models import LectureTranscript
from services.spring_webhook import notify_analysis_complete
from services.stt_service import transcribe_file, transcribe_pcm

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


class AnalyzeBatchRequest(BaseModel):
    audio_path: str


class AnalyzeBatchResponse(BaseModel):
    task_id: str
    status: Literal["QUEUED"]


@router.post(
    "/lectures/{lecture_id}/analyze-batch",
    response_model=AnalyzeBatchResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def analyze_batch(
    lecture_id: int,
    request: AnalyzeBatchRequest,
    background_tasks: BackgroundTasks,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> AnalyzeBatchResponse:
    """녹음 전체를 정밀 전사한다 (SPEC §2.2-2). 즉시 202 를 주고 백그라운드에서 처리한다."""
    if not Path(request.audio_path).is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"오디오 파일을 찾을 수 없습니다: {request.audio_path}",
        )

    task_id = f"batch_{lecture_id}_{date.today():%Y%m%d}"
    background_tasks.add_task(run_batch_analysis, lecture_id, request.audio_path)
    log.info("배치 분석 접수 lecture_id=%s task_id=%s", lecture_id, task_id)
    return AnalyzeBatchResponse(task_id=task_id, status="QUEUED")


async def run_batch_analysis(lecture_id: int, audio_path: str) -> None:
    """전사 → lecture_transcripts 적재 → Spring Boot Webhook 통보.

    슬라이드 정렬(matched_slide_page)과 자동 필기는 다음 단계에서 채운다.
    """
    try:
        segments = await run_in_threadpool(transcribe_file, audio_path)
        async with AsyncSessionLocal() as session:
            await session.execute(
                delete(LectureTranscript).where(LectureTranscript.lecture_id == lecture_id)
            )
            session.add_all(
                LectureTranscript(
                    lecture_id=lecture_id,
                    start_time_ms=segment.start_time_ms,
                    end_time_ms=segment.end_time_ms,
                    speaker_text=segment.text,
                )
                for segment in segments
            )
            await session.commit()
        log.info("배치 전사 완료 lecture_id=%s segments=%s", lecture_id, len(segments))
        await notify_analysis_complete(lecture_id, "READY", 0, len(segments))
    except Exception:  # noqa: BLE001 - 어떤 실패든 강의 상태를 FAILED 로 돌려야 한다
        log.exception("배치 분석 실패 lecture_id=%s", lecture_id)
        await notify_analysis_complete(lecture_id, "FAILED", 0, 0)
