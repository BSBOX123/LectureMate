"""POST /ai/v1/materials/parse 통합 테스트.

로컬 postgres 컨테이너가 떠 있어야 한다 (docker-compose up -d postgres).
비동기 DB 세션과 앱을 같은 이벤트 루프에서 쓰기 위해 async 테스트로 작성한다.
"""

import pymupdf
import pytest

from tests.conftest import execute


@pytest.fixture
def pdf_path(tmp_path):
    path = tmp_path / "material.pdf"
    document = pymupdf.open()
    for line in ("Dijkstra algorithm", "negative weight warning"):
        page = document.new_page()
        page.insert_text((72, 120), line, fontsize=14)
    document.save(path)
    document.close()
    return path


async def test_parse_stores_pages_and_is_idempotent(client, course_id, material_id, pdf_path):
    response = await client.post(
        "/ai/v1/materials/parse",
        json={"material_id": material_id, "course_id": course_id, "pdf_path": str(pdf_path)},
    )
    assert response.status_code == 200
    assert response.json() == {"total_pages": 2, "status": "COMPLETED"}

    result = await execute(
        "SELECT page_number, page_text, layout_data, course_id, embedding IS NULL AS no_embedding"
        " FROM material_pages WHERE material_id = :id ORDER BY page_number",
        id=material_id,
    )
    rows = result.all()
    assert len(rows) == 2
    assert "Dijkstra" in rows[0].page_text
    assert rows[0].layout_data[0]["word"] == "Dijkstra"
    assert len(rows[0].layout_data[0]["bbox"]) == 4
    # 과목 단위 검색을 위해 페이지 행에도 course_id 가 들어가야 한다
    assert rows[0].course_id == course_id
    # EMBEDDING_ENABLED 기본값(false)에서는 embedding 이 비어 있다
    assert rows[0].no_embedding is True

    # 같은 자료를 다시 파싱해도 행이 중복되지 않는다
    await client.post(
        "/ai/v1/materials/parse",
        json={"material_id": material_id, "course_id": course_id, "pdf_path": str(pdf_path)},
    )
    result = await execute(
        "SELECT count(*) FROM material_pages WHERE material_id = :id", id=material_id
    )
    assert result.scalar_one() == 2


async def test_missing_file_returns_404(client, course_id, material_id):
    response = await client.post(
        "/ai/v1/materials/parse",
        json={
            "material_id": material_id,
            "course_id": course_id,
            "pdf_path": "/tmp/does-not-exist.pdf",
        },
    )
    assert response.status_code == 404


async def test_rejects_invalid_ids(client):
    response = await client.post(
        "/ai/v1/materials/parse",
        json={"material_id": 0, "course_id": 1, "pdf_path": "x.pdf"},
    )
    assert response.status_code == 422
