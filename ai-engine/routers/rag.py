"""복합 벡터 검색 및 LLM 질의응답 라우터 (SPEC §2.2-3: POST /ai/v1/rag/query)."""

from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_session
from services.rag_service import answer_stream

router = APIRouter(tags=["rag"])


class RagQueryRequest(BaseModel):
    lecture_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=5, ge=1, le=20)


@router.post("/rag/query")
async def query(
    request: RagQueryRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> StreamingResponse:
    """citations → token → done 순서의 SSE 스트림."""
    return StreamingResponse(
        answer_stream(session, request.lecture_id, request.question, request.top_k),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
