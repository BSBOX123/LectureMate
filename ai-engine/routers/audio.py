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

import numpy as np
from sqlalchemy import select

from core.config import settings
from core.database import AsyncSessionLocal, get_session
from core.models import LectureSlide, LectureTranscript, SlideAnnotation
from services.alignment_service import align_segments_to_pages
from services.annotation_service import generate_annotation
from services.embedding_service import embed_texts
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


async def _generate_annotations(
    session: AsyncSession, lecture_id: int, matched_pages: list[int | None], segments
) -> int:
    """슬라이드별로 모인 발화를 LLM 에 넣어 자동 필기를 만든다 (SPEC §2.1-4).

    한 슬라이드가 실패해도 나머지는 계속 만든다.
    """
    speech_by_page: dict[int, list[str]] = {}
    for segment, page in zip(segments, matched_pages, strict=True):
        if page is not None:
            speech_by_page.setdefault(page, []).append(segment.text)
    if not speech_by_page:
        return 0

    slides = (
        (
            await session.execute(
                select(LectureSlide)
                .where(LectureSlide.lecture_id == lecture_id)
                .order_by(LectureSlide.page_number)
            )
        )
        .scalars()
        .all()
    )
    await session.execute(
        delete(SlideAnnotation).where(SlideAnnotation.lecture_id == lecture_id)
    )

    created = 0
    for slide in slides:
        speech = speech_by_page.get(slide.page_number)
        if not speech:
            continue
        try:
            annotation = await run_in_threadpool(
                generate_annotation, slide.page_number, slide.slide_text, slide.layout_data, speech
            )
        except Exception:  # noqa: BLE001 - LLM 실패가 전체 분석을 막으면 안 된다
            log.exception(
                "자동 필기 생성 실패 lecture_id=%s page=%s", lecture_id, slide.page_number
            )
            continue
        if annotation is None:
            continue
        session.add(
            SlideAnnotation(
                slide_id=slide.id,
                lecture_id=lecture_id,
                page_number=annotation.page_number,
                professor_summary=annotation.professor_summary,
                exam_hints=annotation.exam_hints,
                highlight_bboxes=annotation.highlight_bboxes,
                confidence_score=annotation.confidence_score,
            )
        )
        created += 1

    await session.commit()
    return created


async def _slide_embeddings(session: AsyncSession, lecture_id: int):
    """슬라이드 페이지 번호와 임베딩 행렬을 돌려준다.

    PDF 파싱 당시 임베딩이 꺼져 있었다면 여기서 만들어 채운다.
    """
    slides = (
        (
            await session.execute(
                select(LectureSlide)
                .where(LectureSlide.lecture_id == lecture_id)
                .order_by(LectureSlide.page_number)
            )
        )
        .scalars()
        .all()
    )
    if not slides:
        return [], None

    missing = [slide for slide in slides if slide.embedding is None]
    if missing:
        vectors = await run_in_threadpool(embed_texts, [slide.slide_text for slide in missing])
        if vectors[0] is None:
            return [slide.page_number for slide in slides], None
        for slide, vector in zip(missing, vectors, strict=True):
            slide.embedding = vector
        await session.commit()

    return (
        [slide.page_number for slide in slides],
        np.array([slide.embedding for slide in slides], dtype=np.float32),
    )


async def run_batch_analysis(lecture_id: int, audio_path: str) -> None:
    """전사 → 임베딩 → 슬라이드 정렬 → lecture_transcripts 적재 → Webhook 통보.

    자동 필기(annotation)는 다음 단계에서 붙인다.
    """
    try:
        segments = await run_in_threadpool(transcribe_file, audio_path)
        embeddings = await run_in_threadpool(embed_texts, [segment.text for segment in segments])

        async with AsyncSessionLocal() as session:
            page_numbers, slide_matrix = await _slide_embeddings(session, lecture_id)

            matched_pages: list[int | None] = [None] * len(segments)
            if segments and slide_matrix is not None and embeddings[0] is not None:
                matched_pages = align_segments_to_pages(
                    np.array(embeddings, dtype=np.float32), slide_matrix, page_numbers
                )
            else:
                log.warning(
                    "정렬 건너뜀 lecture_id=%s (슬라이드 %s개, 임베딩 %s)",
                    lecture_id,
                    len(page_numbers),
                    "있음" if embeddings and embeddings[0] is not None else "없음",
                )

            await session.execute(
                delete(LectureTranscript).where(LectureTranscript.lecture_id == lecture_id)
            )
            session.add_all(
                LectureTranscript(
                    lecture_id=lecture_id,
                    start_time_ms=segment.start_time_ms,
                    end_time_ms=segment.end_time_ms,
                    speaker_text=segment.text,
                    matched_slide_page=page,
                    embedding=embedding,
                )
                for segment, page, embedding in zip(
                    segments, matched_pages, embeddings, strict=True
                )
            )
            await session.commit()

            annotated_pages = await _generate_annotations(
                session, lecture_id, matched_pages, segments
            )

        matched = [page for page in matched_pages if page is not None]
        log.info(
            "배치 분석 완료 lecture_id=%s segments=%s matched=%s pages=%s 필기=%s",
            lecture_id,
            len(segments),
            len(matched),
            len(set(matched)),
            annotated_pages,
        )
        await notify_analysis_complete(lecture_id, "READY", annotated_pages, len(matched))
    except Exception:  # noqa: BLE001 - 어떤 실패든 강의 상태를 FAILED 로 돌려야 한다
        log.exception("배치 분석 실패 lecture_id=%s", lecture_id)
        await notify_analysis_complete(lecture_id, "FAILED", 0, 0)
