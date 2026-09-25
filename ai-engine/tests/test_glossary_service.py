"""glossary_service 테스트. LLM 호출은 대체한다 (conftest 가 실제 호출을 막는다)."""

import pytest

import services.glossary_service as glossary_service
from services.glossary_service import MAX_PROMPT_CHARS, _spread_sample, _to_prompt, build_glossary
from tests.conftest import execute


@pytest.fixture
def fake_llm(monkeypatch):
    """LLM 에 넘어간 자료 샘플을 확인할 수 있도록 기록한다."""
    calls: list[str] = []

    def fake_complete(system_prompt, user_prompt):
        calls.append(user_prompt)
        return "외래 키, 기본 키, 릴레이션"

    monkeypatch.setattr(glossary_service, "complete", fake_complete)
    return calls


def test_spread_sample_covers_whole_course():
    """앞에서 자르면 뒷부분 용어를 놓친다. 전체에서 고르게 뽑아야 한다."""
    pages = [f"p{i}" for i in range(100)]

    picked = _spread_sample(pages, 10)

    assert len(picked) == 10
    assert picked[0] == "p0"
    # 마지막 근처까지 포함한다
    assert picked[-1] == "p90"
    # 개수가 한도 이하면 그대로 쓴다
    assert _spread_sample(pages[:5], 10) == pages[:5]


def test_to_prompt_dedupes_and_trims():
    prompt = _to_prompt("릴레이션, 외래 키,릴레이션 ,  기본 키\n튜플")

    assert prompt == "이 강의에서 쓰는 용어: 릴레이션, 외래 키, 기본 키, 튜플"


def test_to_prompt_cuts_at_term_boundary():
    """Whisper 의 프롬프트 한도를 넘으면 용어 단위로 자른다.

    중간에서 잘린 용어를 남기면 Whisper 가 엉뚱한 단어로 받아들일 수 있다.
    """
    prompt = _to_prompt(", ".join(f"용어{i:03d}" for i in range(200)))

    assert prompt is not None
    assert len(prompt) <= MAX_PROMPT_CHARS
    # 잘린 조각이 남지 않는다
    assert all(len(term) == len("용어000") for term in prompt.split(": ")[1].split(", "))


def test_to_prompt_returns_none_for_empty():
    assert _to_prompt("") is None
    assert _to_prompt("  ,  , ") is None


async def test_build_glossary_uses_course_materials(session, course_id, fake_llm):
    material_id = (
        await execute(
            "INSERT INTO course_materials (course_id, title, status)"
            " VALUES (:c, '2장', 'READY') RETURNING id",
            c=course_id,
        )
    ).scalar_one()
    await execute(
        """
        INSERT INTO material_pages (material_id, course_id, page_number, page_text, layout_data)
        VALUES (:m, :c, 1, 'Foreign Key 는  다른   릴레이션의', '[]'),
               (:m, :c, 2, 'Primary Key 설명', '[]')
        """,
        m=material_id,
        c=course_id,
    )

    prompt = await build_glossary(session, course_id)

    assert prompt == "이 강의에서 쓰는 용어: 외래 키, 기본 키, 릴레이션"
    # 자료 텍스트가 LLM 에 넘어가고, 공백은 하나로 정리된다
    assert "Foreign Key 는 다른 릴레이션의" in fake_llm[0]
    assert "Primary Key 설명" in fake_llm[0]


async def test_build_glossary_returns_none_without_materials(session, course_id, fake_llm):
    """자료가 없으면 LLM 을 부르지 않고 None (전사는 사전 없이 진행된다)."""
    assert await build_glossary(session, course_id) is None
    assert fake_llm == []


async def test_build_glossary_survives_llm_failure(session, course_id, monkeypatch):
    """LLM 이 실패해도 전사를 막지 않는다."""
    material_id = (
        await execute(
            "INSERT INTO course_materials (course_id, title, status)"
            " VALUES (:c, '2장', 'READY') RETURNING id",
            c=course_id,
        )
    ).scalar_one()
    await execute(
        "INSERT INTO material_pages (material_id, course_id, page_number, page_text, layout_data)"
        " VALUES (:m, :c, 1, '내용', '[]')",
        m=material_id,
        c=course_id,
    )

    def boom(system_prompt, user_prompt):
        raise RuntimeError("claude 실행 실패")

    monkeypatch.setattr(glossary_service, "complete", boom)

    assert await build_glossary(session, course_id) is None
