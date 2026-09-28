"""pgvector 하이브리드 검색 + LLM 스트리밍 답변 (SPEC §2.2-3, §4.2).

검색 범위는 **과목 전체**다. 한 과목에 들어 있는 모든 PDF 자료(material_pages)와
모든 녹음(recording_chunks)을 각각 찾아 함께 컨텍스트로 넣는다.

녹음은 Whisper 세그먼트가 아니라 **약 300자로 묶은 덩어리**를 검색한다. 세그먼트는 평균 21자로
너무 잘아 임베딩할 의미가 없었다 (chunking 모듈 주석 참고).

두 출처를 모두 쓰는 이유가 이 서비스의 핵심이다. 자료에 적힌 정의와 교수님이 실제로
말씀하신 설명·강조점이 다르기 때문에, 답변에서 둘을 구분해 보여 줘야 복습에 쓸모가 있다.
"""

import json
import logging
from collections.abc import AsyncIterator, Iterator
from dataclasses import asdict, dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.models import CourseMaterial, CourseRecording, MaterialPage, RecordingChunk
from services.embedding_service import embed_texts
from services.llm_client import stream as llm_stream

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """너는 대학생의 강의 복습을 돕는 어시스턴트다.
아래 [강의 자료]와 [교수님 발화]만 근거로 학생의 질문에 한국어로 답한다.

규칙:
- 자료에 적힌 내용과 교수님이 실제로 말씀하신 내용을 구분해서 알려준다.
  교수님 발화에 관련 언급이 있으면 "교수님은 ...라고 하셨다"처럼 따로 짚어 준다.
- 자료를 가리킬 때는 "데이터베이스 3장 14쪽"처럼 자료 이름과 쪽수를 함께 쓴다.
- 교수님 발화는 음성 인식 결과라 전문 용어가 잘못 적혔을 수 있다. 문맥으로 알아서 이해하고
  자료의 표기를 따른다. 표기가 틀렸다는 이야기를 답변에 쓰지는 않는다.
- [이전 대화]가 있으면 그 흐름을 이어서 답한다. "그거", "방금 그건" 같은 말은 이전 대화에서
  무엇을 가리키는지 찾아 이해한다.
- 근거가 없으면 모른다고 답한다. 지어내지 않는다.
- **짧게 답한다. 3~5문장.** 핵심만 말하고 같은 내용을 되풀이하지 않는다. 근거가 여러 건이면 가장
  분명한 것만 짚는다. 확인할 수 없는 부분은 한 문장으로만 언급한다.
  (실측: 이 지시 하나로 답변이 722자 → 333자, 9.4초 → 6.0초가 되었다. 생성 시간이 전체 지연의
  96%를 차지하므로 답변 길이가 곧 체감 속도다.)
- 화면에 그대로 표시되므로 마크다운(**, ##, - 등) 없이 평문으로 답한다. 목록도 쓰지 않는다."""

USER_PROMPT = """{history}[강의 자료]
{materials}

[교수님 발화]
{speech}

[질문]
{question}"""

HISTORY_BLOCK = """[이전 대화]
{turns}

"""

# 프롬프트와 검색어에 넣을 이전 대화 개수. 짧게 유지해야 답변이 느려지지 않는다.
MAX_HISTORY_TURNS = 4


@dataclass(frozen=True)
class ChatTurn:
    """이전 대화 한 마디 (SPEC §2.1-11 history)."""

    role: str  # user | assistant
    text: str


@dataclass(frozen=True)
class PageHit:
    """검색된 자료 페이지."""

    material_id: int
    material_title: str
    page_number: int
    text: str


@dataclass(frozen=True)
class SpeechHit:
    """검색된 녹음 발화 구간."""

    recording_id: int
    recording_title: str
    start_time_ms: int
    text: str


@dataclass(frozen=True)
class Citation:
    """답변 근거 (SPEC §2.1-5 citations).

    자료 출처면 material* 와 pageNumber 가, 녹음 출처면 recording* 와 startTimeMs 가 채워진다.
    """

    source: str  # MATERIAL | RECORDING
    snippet: str
    materialId: int | None = None
    materialTitle: str | None = None
    pageNumber: int | None = None
    recordingId: int | None = None
    recordingTitle: str | None = None
    startTimeMs: int | None = None


def sse_event(event: str, payload: dict) -> str:
    """SSE 한 건. 이벤트 사이는 빈 줄로 구분한다."""
    return f"event: {event}\ndata: {json.dumps(payload, ensure_ascii=False)}\n\n"


def retrieval_query(question: str, history: list[ChatTurn]) -> str:
    """검색에 쓸 문장을 만든다.

    "그거 시험에 나와?" 같은 후속 질문은 그 자체로는 검색어가 되지 못한다. 직전 사용자 질문을
    앞에 붙여 맥락을 준다. LLM 으로 질문을 재작성하는 방법이 더 정확하겠지만 호출이 한 번 늘어
    답변이 느려진다. 빠른 응답을 우선해 이 방식을 골랐다.
    """
    previous = [turn.text for turn in history if turn.role == "user"]
    return f"{previous[-1]} {question}" if previous else question


async def search(
    session: AsyncSession,
    course_id: int,
    question: str,
    top_k: int,
    history: list[ChatTurn] | None = None,
) -> tuple[list[PageHit], list[SpeechHit]]:
    """질문과 가까운 자료 페이지/발화 구간을 과목 전체에서 각각 top_k 개 찾는다."""
    embedding = await _embed(retrieval_query(question, history or []))
    if embedding is None:
        return [], []

    page_rows = (
        await session.execute(
            select(MaterialPage, CourseMaterial.title)
            .join(CourseMaterial, CourseMaterial.id == MaterialPage.material_id)
            .where(
                MaterialPage.course_id == course_id,
                MaterialPage.embedding.is_not(None),
            )
            .order_by(MaterialPage.embedding.cosine_distance(embedding))
            .limit(top_k)
        )
    ).all()
    speech_rows = (
        await session.execute(
            select(RecordingChunk, CourseRecording.title)
            .join(CourseRecording, CourseRecording.id == RecordingChunk.recording_id)
            .where(
                RecordingChunk.course_id == course_id,
                RecordingChunk.embedding.is_not(None),
            )
            .order_by(RecordingChunk.embedding.cosine_distance(embedding))
            .limit(top_k)
        )
    ).all()

    pages = [
        PageHit(
            material_id=page.material_id,
            material_title=title,
            page_number=page.page_number,
            text=page.page_text,
        )
        for page, title in page_rows
    ]
    speech = [
        SpeechHit(
            recording_id=chunk.recording_id,
            recording_title=title,
            start_time_ms=chunk.start_time_ms,
            text=chunk.chunk_text,
        )
        for chunk, title in speech_rows
    ]
    return pages, speech


async def _embed(question: str):
    from starlette.concurrency import run_in_threadpool

    vectors = await run_in_threadpool(embed_texts, [question])
    return vectors[0]


def build_citations(pages: list[PageHit], speech: list[SpeechHit]) -> list[Citation]:
    citations = [
        Citation(
            source="MATERIAL",
            snippet=_snippet(hit.text),
            materialId=hit.material_id,
            materialTitle=hit.material_title,
            pageNumber=hit.page_number,
        )
        for hit in pages
    ]
    citations += [
        Citation(
            source="RECORDING",
            snippet=_snippet(hit.text),
            recordingId=hit.recording_id,
            recordingTitle=hit.recording_title,
            startTimeMs=hit.start_time_ms,
        )
        for hit in speech
    ]
    return citations


def _snippet(text: str, limit: int = 120) -> str:
    flattened = " ".join(text.split())
    return flattened[:limit] + ("..." if len(flattened) > limit else "")


def _timestamp(start_time_ms: int) -> str:
    """1043880 → '17:23'. 프롬프트에서 교수님이 언제 말씀했는지 알려 준다."""
    total_seconds = start_time_ms // 1000
    return f"{total_seconds // 60}:{total_seconds % 60:02d}"


def build_user_prompt(
    question: str,
    pages: list[PageHit],
    speech: list[SpeechHit],
    history: list[ChatTurn] | None = None,
) -> str:
    return USER_PROMPT.format(
        history=_history_block(history or []),
        materials="\n".join(
            f"- {hit.material_title} {hit.page_number}쪽: {_snippet(hit.text, 500)}"
            for hit in pages
        )
        or "(없음)",
        speech="\n".join(
            f"- {hit.recording_title} {_timestamp(hit.start_time_ms)}: {_snippet(hit.text, 500)}"
            for hit in speech
        )
        or "(없음)",
        question=question,
    )


def _history_block(history: list[ChatTurn]) -> str:
    """이전 대화가 없으면 아무것도 넣지 않는다 (빈 섹션은 모델을 헷갈리게 한다)."""
    if not history:
        return ""
    turns = "\n".join(
        f"{'학생' if turn.role == 'user' else '어시스턴트'}: {_snippet(turn.text, 300)}"
        for turn in history[-MAX_HISTORY_TURNS:]
    )
    return HISTORY_BLOCK.format(turns=turns)


def stream_answer(
    question: str,
    pages: list[PageHit],
    speech: list[SpeechHit],
    history: list[ChatTurn] | None = None,
) -> Iterator[str]:
    """검색한 컨텍스트로 LLM 답변 토큰을 순서대로 돌려준다 (동기 이터레이터).

    LLM 백엔드(Claude Code / Ollama)는 llm_client 가 고른다.
    """
    yield from llm_stream(SYSTEM_PROMPT, build_user_prompt(question, pages, speech, history))


async def answer_stream(
    session: AsyncSession,
    course_id: int,
    question: str,
    top_k: int,
    history: list[ChatTurn] | None = None,
) -> AsyncIterator[str]:
    """citations → token... → done 순서로 SSE 문자열을 내보낸다 (SPEC §2.2-3)."""
    from starlette.concurrency import iterate_in_threadpool

    pages, speech = await search(session, course_id, question, top_k, history)
    citations = build_citations(pages, speech)
    yield sse_event("citations", {"citations": [asdict(c) for c in citations]})

    try:
        async for token in iterate_in_threadpool(
            stream_answer(question, pages, speech, history)
        ):
            yield sse_event("token", {"text": token})
    except Exception:  # noqa: BLE001 - 스트리밍 중 실패도 클라이언트에 알려야 한다
        log.exception("RAG 답변 생성 실패 course_id=%s", course_id)
        yield sse_event("done", {"finishReason": "error"})
        return

    yield sse_event("done", {"finishReason": "stop"})
