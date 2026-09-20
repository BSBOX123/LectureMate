"""pdf_parser 단위 테스트 (DB 불필요)."""

import pymupdf
import pytest

from services.pdf_parser import parse_pdf


@pytest.fixture
def sample_pdf(tmp_path):
    """2페이지짜리 PDF 를 만들어 경로를 돌려준다."""
    path = tmp_path / "sample.pdf"
    document = pymupdf.open()
    for text in ("Dijkstra shortest path", "Bellman-Ford negative weight"):
        page = document.new_page()
        page.insert_text((100, 150), text, fontsize=12)
    document.save(path)
    document.close()
    return path


def test_parse_pdf_extracts_text_and_word_bboxes(sample_pdf):
    pages = parse_pdf(sample_pdf)

    assert [page.page_number for page in pages] == [1, 2]
    assert "Dijkstra" in pages[0].slide_text
    assert "Bellman-Ford" in pages[1].slide_text

    words = [entry["word"] for entry in pages[0].layout_data]
    assert words == ["Dijkstra", "shortest", "path"]

    bbox = pages[0].layout_data[0]["bbox"]
    assert len(bbox) == 4
    x1, y1, x2, y2 = bbox
    assert x1 < x2 and y1 < y2
    # insert_text 의 기준점(100, 150) 근처에 위치한다
    assert 95 < x1 < 105


def test_parse_pdf_skips_empty_pages(tmp_path):
    path = tmp_path / "blank.pdf"
    document = pymupdf.open()
    document.new_page()
    document.save(path)
    document.close()

    pages = parse_pdf(path)
    assert len(pages) == 1
    assert pages[0].slide_text == ""
    assert pages[0].layout_data == []
