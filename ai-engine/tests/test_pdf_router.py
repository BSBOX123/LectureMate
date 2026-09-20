"""POST /ai/v1/pdf/parse 통합 테스트.

로컬 postgres 컨테이너가 떠 있어야 한다 (docker compose up -d postgres).
비동기 DB 세션과 앱을 같은 이벤트 루프에서 쓰기 위해 async 테스트로 작성한다.
"""

import pymupdf
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text

import main
from core.database import AsyncSessionLocal

TEST_EMAIL = "pdf-router-test@example.com"


async def _execute(statement: str, **params):
    async with AsyncSessionLocal() as session:
        result = await session.execute(text(statement), params)
        await session.commit()
        return result


@pytest_asyncio.fixture
async def lecture_id():
    """테스트용 user/lecture 행을 만들고, 끝나면 지운다 (FK 때문에 필요)."""
    result = await _execute(
        """
        WITH new_user AS (
            INSERT INTO users (email, password_hash, name)
            VALUES (:email, 'x', '테스트')
            RETURNING id
        )
        INSERT INTO lectures (user_id, title, status)
        SELECT id, '알고리즘 5강', 'PROCESSING' FROM new_user
        RETURNING id
        """,
        email=TEST_EMAIL,
    )
    created = result.scalar_one()
    yield created
    await _execute("DELETE FROM users WHERE email = :email", email=TEST_EMAIL)


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=main.app), base_url="http://test"
    ) as async_client:
        yield async_client


@pytest.fixture
def pdf_path(tmp_path):
    path = tmp_path / "lecture.pdf"
    document = pymupdf.open()
    for line in ("Dijkstra algorithm", "negative weight warning"):
        page = document.new_page()
        page.insert_text((72, 120), line, fontsize=14)
    document.save(path)
    document.close()
    return path


async def test_parse_stores_slides_and_is_idempotent(client, lecture_id, pdf_path):
    response = await client.post(
        "/ai/v1/pdf/parse", json={"lecture_id": lecture_id, "pdf_path": str(pdf_path)}
    )
    assert response.status_code == 200
    assert response.json() == {"total_pages": 2, "status": "COMPLETED"}

    result = await _execute(
        "SELECT page_number, slide_text, layout_data, embedding IS NULL AS no_embedding"
        " FROM lecture_slides WHERE lecture_id = :id ORDER BY page_number",
        id=lecture_id,
    )
    rows = result.all()
    assert len(rows) == 2
    assert "Dijkstra" in rows[0].slide_text
    assert rows[0].layout_data[0]["word"] == "Dijkstra"
    assert len(rows[0].layout_data[0]["bbox"]) == 4
    # EMBEDDING_ENABLED 기본값(false)에서는 embedding 이 비어 있다
    assert rows[0].no_embedding is True

    # 같은 강의를 다시 파싱해도 행이 중복되지 않는다
    await client.post(
        "/ai/v1/pdf/parse", json={"lecture_id": lecture_id, "pdf_path": str(pdf_path)}
    )
    result = await _execute(
        "SELECT count(*) FROM lecture_slides WHERE lecture_id = :id", id=lecture_id
    )
    assert result.scalar_one() == 2


async def test_missing_file_returns_404(client, lecture_id):
    response = await client.post(
        "/ai/v1/pdf/parse",
        json={"lecture_id": lecture_id, "pdf_path": "/tmp/does-not-exist.pdf"},
    )
    assert response.status_code == 404


async def test_rejects_invalid_lecture_id(client):
    response = await client.post("/ai/v1/pdf/parse", json={"lecture_id": 0, "pdf_path": "x.pdf"})
    assert response.status_code == 422
