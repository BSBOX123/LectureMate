"""과목 단위 복합 벡터 검색 및 LLM 질의응답 라우터 (SPEC §2.2-3: POST /ai/v1/rag/query)."""

from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_session
from services.rag_service import ChatTurn, answer_stream

router = APIRouter(tags=["rag"])


class ChatTurnRequest(BaseModel):
    """이전 대화 한 마디. 서버는 대화를 저장하지 않고 클라이언트가 매번 보낸다."""

    role: Literal["user", "assistant"]
    text: str = Field(min_length=1, max_length=4000)


class RagQueryRequest(BaseModel):
    course_id: int = Field(gt=0)
    question: str = Field(min_length=1, max_length=2000)
    top_k: int = Field(default=5, ge=1, le=20)
    # 후속 질문("그거 시험에 나와?")의 맥락. 오래된 것은 rag_service 가 잘라 낸다
    history: list[ChatTurnRequest] = Field(default_factory=list, max_length=20)


@router.post("/rag/query")
async def query(
    request: RagQueryRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> StreamingResponse:
    """citations → token → done 순서의 SSE 스트림."""
    history = [ChatTurn(role=turn.role, text=turn.text) for turn in request.history]
    return StreamingResponse(
        answer_stream(session, request.course_id, request.question, request.top_k, history),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
