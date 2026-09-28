"""녹음 정밀 전사 라우터 (SPEC §2.2-2: POST /ai/v1/recordings/{recording_id}/transcribe).

녹음은 특정 PDF 에 묶이지 않는다. 전사 결과는 임베딩과 함께 recording_segments 에 쌓여
과목 단위 RAG 검색의 한쪽 축이 된다. 슬라이드 정렬과 자동 필기는 이 단계에 없다
(추후 발전 과제).
"""

import logging
from datetime import date
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.concurrency import run_in_threadpool

from core.config import settings
from core.database import AsyncSessionLocal, get_session
from core.models import RecordingSegment
from services.embedding_service import embed_texts
from services.glossary_service import build_glossary
from services.spring_webhook import notify_transcription_complete
from services.summary_service import build_summary
from services.stt_service import transcribe_file

router = APIRouter(tags=["recordings"])
log = logging.getLogger(__name__)


class TranscribeRequest(BaseModel):
    course_id: int = Field(gt=0)
    audio_path: str


class TranscribeResponse(BaseModel):
    task_id: str
    status: Literal["QUEUED"]


@router.post(
    "/recordings/{recording_id}/transcribe",
    response_model=TranscribeResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def transcribe(
    recording_id: int,
    request: TranscribeRequest,
    background_tasks: BackgroundTasks,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> TranscribeResponse:
    """녹음 전체를 정밀 전사한다. 즉시 202 를 주고 백그라운드에서 처리한다."""
    if not Path(request.audio_path).is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"오디오 파일을 찾을 수 없습니다: {request.audio_path}",
        )

    task_id = f"transcribe_{recording_id}_{date.today():%Y%m%d}"
    background_tasks.add_task(
        run_transcription, recording_id, request.course_id, request.audio_path
    )
    log.info("전사 접수 recording_id=%s task_id=%s", recording_id, task_id)
    return TranscribeResponse(task_id=task_id, status="QUEUED")


class SummarizeRequest(BaseModel):
    """요약에 붙일 녹음 이름 (프롬프트에서 "9월 28일 수업 전사" 처럼 쓴다)."""

    title: str = Field(min_length=1, max_length=255)


class SummarizeResponse(BaseModel):
    summary: str | None


@router.post("/recordings/{recording_id}/summarize", response_model=SummarizeResponse)
async def summarize(
    recording_id: int,
    request: SummarizeRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> SummarizeResponse:
    """전사 전체를 읽어 복습용 요약을 만든다 (SPEC §2.2-5).

    동기 호출이다. 실측 35초 정도이고 Spring 의 read-timeout 은 120초다. 결과는 돌려주기만 하고
    저장은 Spring 이 한다 (`course_recordings` 는 Spring 소유 테이블이다).
    """
    return SummarizeResponse(summary=await build_summary(session, recording_id, request.title))


async def run_transcription(recording_id: int, course_id: int, audio_path: str) -> None:
    """용어 사전 → 전사 → 임베딩 → recording_segments 적재 → Webhook 통보."""
    try:
        # 같은 과목의 자료에서 전문 용어를 뽑아 Whisper 에 미리 알려 준다.
        # 실패해도 None 이 와서 전사는 그대로 진행된다.
        glossary = None
        if settings.stt_glossary_enabled:
            async with AsyncSessionLocal() as session:
                glossary = await build_glossary(session, course_id)
        segments = await run_in_threadpool(transcribe_file, audio_path, None, glossary)
        embeddings = await run_in_threadpool(embed_texts, [segment.text for segment in segments])

        async with AsyncSessionLocal() as session:
            # 재시도 시 중복되지 않도록 기존 결과를 지우고 다시 쌓는다
            await session.execute(
                delete(RecordingSegment).where(RecordingSegment.recording_id == recording_id)
            )
            session.add_all(
                RecordingSegment(
                    recording_id=recording_id,
                    course_id=course_id,
                    start_time_ms=segment.start_time_ms,
                    end_time_ms=segment.end_time_ms,
                    speaker_text=segment.text,
                    embedding=embedding,
                )
                for segment, embedding in zip(segments, embeddings, strict=True)
            )
            await session.commit()

        duration_ms = max((segment.end_time_ms for segment in segments), default=0)
        log.info(
            "전사 완료 recording_id=%s segments=%s 길이=%sms",
            recording_id,
            len(segments),
            duration_ms,
        )
        await notify_transcription_complete(recording_id, "READY", len(segments), duration_ms)
    except Exception:  # noqa: BLE001 - 어떤 실패든 녹음 상태를 FAILED 로 돌려야 한다
        log.exception("전사 실패 recording_id=%s", recording_id)
        await notify_transcription_complete(recording_id, "FAILED", 0, 0)
