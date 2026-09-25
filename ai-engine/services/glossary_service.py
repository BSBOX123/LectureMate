"""과목 자료에서 전문 용어를 뽑아 Whisper 용어 사전(initial_prompt)을 만든다.

전사에서 전문 용어가 자주 틀린다 (실측: "외래 키" → "외의 키", "기본 키" → "주키").
그런데 정답은 이미 그 과목의 PDF 안에 있다. 자료 텍스트에서 용어를 뽑아 Whisper 에
미리 알려 주면 같은 발음을 그 용어로 옮길 가능성이 올라간다.

용어 추출에 LLM 을 한 번 쓴다. PDF 에서 뽑은 한국어 텍스트는 띄어쓰기가 깨져 있는 경우가
많아(예: "DISTINCT를이용하여중복제거") 빈도 기반 토큰 추출로는 쓸 만한 용어가 나오지 않는다.
전사 자체가 수십 분 걸리는 작업이라 호출 한 번은 비용이 되지 않는다.
"""

import logging

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from core.models import MaterialPage
from services.llm_client import complete

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """너는 강의 자료에서 전문 용어를 뽑아내는 도구다.
아래 자료에 나오는 과목 전문 용어를 쉼표로 구분해 한 줄로 출력한다.

규칙:
- 이 목록은 **교수님이 강의에서 소리 내어 말하는 말**을 받아 적는 데 쓴다. 그래서
  자료에 영어로 적혀 있어도 **교수님이 한국어로 부르는 표준 용어를 넣는다**
  (예: 자료의 "Foreign Key" → "외래 키", "Primary Key" → "기본 키").
  한국어로 옮겨 말하지 않는 SQL 예약어(DISTINCT, GROUP BY 등)만 영어 그대로 넣는다.
- 띄어쓰기가 붙어 있으면 올바르게 띄어 쓴다 (예: "외래키를참조" → "외래 키").
- **말로 했을 때 잘못 들릴 만한 용어를 앞에 둔다.** 짧고 발음이 흔한 용어가 특히 그렇다.
- 일반 단어(그것, 내용, 경우, 사용)는 넣지 않는다.
- 25개 이내. 설명·번호·따옴표 없이 용어만 쉼표로 나열한다."""

# Whisper 의 initial_prompt 는 컨텍스트 절반(224토큰)까지만 쓰인다. 한국어는 토큰을 많이
# 먹으므로 글자 수로 넉넉히 잘라 둔다.
MAX_PROMPT_CHARS = 200
# 용어 추출에 넣을 페이지 수와 페이지당 글자 수.
# 간격 샘플링으로 줄였더니 "Foreign Key" 가 적힌 2쪽이 빠져 핵심 용어를 놓쳤다. 200쪽 × 300자면
# 6만 자 정도로 sonnet 에 한 번에 넣을 수 있으니 웬만한 과목은 전부 본다.
SAMPLE_PAGES = 200
SAMPLE_CHARS_PER_PAGE = 300


async def build_glossary(session: AsyncSession, course_id: int) -> str | None:
    """과목 자료에서 용어를 뽑아 Whisper 에 넘길 문장을 만든다.

    자료가 없거나 LLM 호출이 실패하면 ``None`` 을 돌려준다. 용어 사전은 있으면 좋은 것이고
    없어도 전사는 되어야 하므로 실패를 위로 올리지 않는다.
    """
    all_pages = (
        (
            await session.execute(
                select(MaterialPage.page_text)
                .where(MaterialPage.course_id == course_id, func.length(MaterialPage.page_text) > 0)
                .order_by(MaterialPage.material_id, MaterialPage.page_number)
            )
        )
        .scalars()
        .all()
    )
    if not all_pages:
        log.info("용어 사전 건너뜀: 과목 %s 에 자료 텍스트가 없다", course_id)
        return None

    pages = _spread_sample(list(all_pages), SAMPLE_PAGES)
    sample = "\n".join(" ".join(page.split())[:SAMPLE_CHARS_PER_PAGE] for page in pages)
    try:
        terms = complete(SYSTEM_PROMPT, sample)
    except Exception:  # noqa: BLE001 - 용어 사전 없이도 전사는 진행해야 한다
        log.exception("용어 사전 생성 실패 course_id=%s", course_id)
        return None

    prompt = _to_prompt(terms)
    log.info("용어 사전 course_id=%s: %s", course_id, prompt)
    return prompt


def _spread_sample(pages: list[str], limit: int) -> list[str]:
    """페이지를 앞에서 자르지 않고 전체에서 일정 간격으로 고른다."""
    if len(pages) <= limit:
        return pages
    step = len(pages) / limit
    return [pages[int(index * step)] for index in range(limit)]


def _to_prompt(terms: str) -> str | None:
    """LLM 이 준 용어 목록을 Whisper 프롬프트 문장으로 다듬는다.

    글자 수 한도를 넘으면 용어 단위로 잘라 낸다. 중간에서 잘린 용어를 남기면
    Whisper 가 엉뚱한 단어로 받아들일 수 있다.
    """
    unique: list[str] = []
    for term in terms.replace("\n", ",").split(","):
        cleaned = " ".join(term.split()).strip("·-*\"'")
        if cleaned and cleaned not in unique:
            unique.append(cleaned)
    if not unique:
        return None

    prefix = "이 강의에서 쓰는 용어: "
    kept: list[str] = []
    for term in unique:
        candidate = prefix + ", ".join([*kept, term])
        if len(candidate) > MAX_PROMPT_CHARS:
            break
        kept.append(term)
    return prefix + ", ".join(kept) if kept else None
