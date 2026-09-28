"""녹음 전사 전체를 읽어 복습용 요약을 만든다 (SPEC §2.2-5).

**채팅 RAG 로는 할 수 없는 일이라 따로 둔다.** "교수님이 강조한 부분 찾아줘" 는 강조가 주제가
아니어서 임베딩이 매칭할 대상이 없고, 1,373개 세그먼트에서 top_k=5 만 가져오면 71분 수업을
요약할 수 없다. 검색 질문이 아니라 집계 질문이다.

그래서 검색하지 않고 **전사 전체를 한 번에** LLM 에 넘긴다. 실측으로 71분 녹음이 24,924자
(약 8천 토큰)이라 한 번에 들어간다. 슬라이드마다 호출해 128번이 되던 자동 필기(Step 12, 제거됨)와
달리 녹음당 1회다.
"""

import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.models import RecordingSegment
from services.llm_client import complete

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """너는 대학 강의 녹음의 전사본을 읽고 복습용 정리를 만드는 도구다.

- 교수님이 **강조하거나 반복한 것**, 시험·과제를 언급한 대목을 먼저 짚는다.
- 각 항목에 그 말이 나온 시각을 [분:초] 로 붙인다. 학생이 그 대목을 찾아 들을 수 있어야 한다.
- 전사는 음성 인식 결과라 전문 용어가 틀려 있다. 문맥으로 알아서 고쳐 읽는다
  (예: "외의 키" → "외래 키", "주키" → "기본 키", "테이프" → "테이블").
- 인식이 망가져 내용을 알 수 없는 구간은 지어내지 말고 그렇다고 적는다.
- 수업 진행과 무관한 잡담은 넣지 않는다.
- 화면에 그대로 표시되므로 마크다운(**, ##, - 등) 없이 평문으로 쓴다. 전체 20문장 이내."""


def _timestamp(start_time_ms: int) -> str:
    total_seconds = start_time_ms // 1000
    return f"{total_seconds // 60}:{total_seconds % 60:02d}"


async def build_summary(session: AsyncSession, recording_id: int, title: str) -> str | None:
    """녹음 전사를 요약한다. 전사가 없으면 ``None``."""
    rows = (
        await session.execute(
            select(RecordingSegment.start_time_ms, RecordingSegment.speaker_text)
            .where(RecordingSegment.recording_id == recording_id)
            .order_by(RecordingSegment.start_time_ms)
        )
    ).all()
    if not rows:
        log.info("요약 건너뜀: 녹음 %s 에 전사가 없다", recording_id)
        return None

    body = "\n".join(f"[{_timestamp(ms)}] {text}" for ms, text in rows)
    duration = _timestamp(rows[-1][0])
    log.info("요약 생성 시작 recording_id=%s 세그먼트=%s 글자=%s", recording_id, len(rows), len(body))

    summary = complete(SYSTEM_PROMPT, f"[{title} 전사 · 약 {duration}]\n{body}")
    log.info("요약 생성 완료 recording_id=%s %s자", recording_id, len(summary))
    return summary.strip() or None
