"""pgvector 하이브리드 검색 + LLM 스트리밍 답변 (SPEC §2.2-3, §4.2).

슬라이드(교재 내용)와 전사(교수님 발화)를 각각 검색해 함께 컨텍스트로 넣는다.
"같은 말을 교수님이 어떻게 설명했는지"가 이 서비스의 핵심이라 두 출처가 모두 필요하다.
"""

import json
import logging
from collections.abc import AsyncIterator, Iterator
from dataclasses import dataclass, asdict

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.config import settings
from core.models import LectureSlide, LectureTranscript
from services.embedding_service import embed_texts
from services.llm_client import stream as llm_stream

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """너는 대학 강의 복습을 돕는 어시스턴트다.
아래 [강의 자료]와 [교수님 발화]만 근거로 학생의 질문에 한국어로 답한다.
근거가 없으면 모른다고 답한다. 답변에서 슬라이드를 언급할 때는 "5쪽"처럼 쪽수를 쓴다.
화면에 그대로 표시되므로 마크다운(**, ##, - 등) 없이 평문으로 답한다."""

USER_PROMPT = """[강의 자료]
{slides}

[교수님 발화]
{speech}

[질문]
{question}"""


@dataclass(frozen=True)
class Citation:
    """답변 근거 (SPEC §2.1-5 citations)."""

    source: str  # SLIDE | TRANSCRIPT
    pageNumber: int | None
    snippet: str
    startTimeMs: int | None = None


def sse_event(event: str, payload: dict) -> str:
    """SSE 한 건. 이벤트 사이는 빈 줄로 구분한다."""
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


async def search(session: AsyncSession, lecture_id: int, question: str, top_k: int):
    """질문과 가까운 슬라이드/전사를 각각 top_k 개 찾는다."""
    embedding = (await _embed(question))
    if embedding is None:
        return [], []

    slides = (
        (
            await session.execute(
                select(LectureSlide)
                .where(
                    LectureSlide.lecture_id == lecture_id,
                    LectureSlide.embedding.is_not(None),
                )
                .order_by(LectureSlide.embedding.cosine_distance(embedding))
                .limit(top_k)
            )
        )
        .scalars()
        .all()
    )
    transcripts = (
        (
            await session.execute(
                select(LectureTranscript)
                .where(
                    LectureTranscript.lecture_id == lecture_id,
                    LectureTranscript.embedding.is_not(None),
                )
                .order_by(LectureTranscript.embedding.cosine_distance(embedding))
                .limit(top_k)
            )
        )
        .scalars()
        .all()
    )
    return slides, transcripts


async def _embed(question: str):
    from starlette.concurrency import run_in_threadpool

    vectors = await run_in_threadpool(embed_texts, [question])
    return vectors[0]


def build_citations(slides, transcripts) -> list[Citation]:
    citations = [
        Citation(source="SLIDE", pageNumber=slide.page_number, snippet=_snippet(slide.slide_text))
        for slide in slides
    ]
    citations += [
        Citation(
            source="TRANSCRIPT",
            pageNumber=transcript.matched_slide_page,
            snippet=_snippet(transcript.speaker_text),
            startTimeMs=transcript.start_time_ms,
        )
        for transcript in transcripts
    ]
    return citations


def _snippet(text: str, limit: int = 120) -> str:
    flattened = " ".join(text.split())
    return flattened[:limit] + ("..." if len(flattened) > limit else "")


def stream_answer(question: str, slides, transcripts) -> Iterator[str]:
    """검색한 컨텍스트로 LLM 답변 토큰을 순서대로 돌려준다 (동기 이터레이터).

    LLM 백엔드(Claude Code / Ollama)는 llm_client 가 고른다.
    """
    user_prompt = USER_PROMPT.format(
        slides="\n".join(
            f"- {slide.page_number}쪽: {_snippet(slide.slide_text, 500)}" for slide in slides
        )
        or "(없음)",
        speech="\n".join(
            f"- {transcript.matched_slide_page or '?'}쪽"
            f" ({transcript.start_time_ms // 1000}초): "
            f"{_snippet(transcript.speaker_text, 500)}"
            for transcript in transcripts
        )
        or "(없음)",
        question=question,
    )
    yield from llm_stream(SYSTEM_PROMPT, user_prompt)


async def answer_stream(
    session: AsyncSession, lecture_id: int, question: str, top_k: int
) -> AsyncIterator[str]:
    """citations → token... → done 순서로 SSE 문자열을 내보낸다 (SPEC §2.2-3)."""
    from starlette.concurrency import iterate_in_threadpool

    slides, transcripts = await search(session, lecture_id, question, top_k)
    citations = build_citations(slides, transcripts)
    yield sse_event("citations", {"citations": [asdict(c) for c in citations]})

    try:
        async for token in iterate_in_threadpool(stream_answer(question, slides, transcripts)):
            yield sse_event("token", {"text": token})
    except Exception:  # noqa: BLE001 - 스트리밍 중 실패도 클라이언트에 알려야 한다
        log.exception("RAG 답변 생성 실패 lecture_id=%s", lecture_id)
        yield sse_event("done", {"finishReason": "error"})
        return

    yield sse_event("done", {"finishReason": "stop"})
