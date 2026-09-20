"""PyMuPDF(fitz)로 페이지별 텍스트 및 Bounding Box 추출 (SPEC §4.2, §2.2-1)."""

from dataclasses import dataclass
from pathlib import Path

import pymupdf
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from core.models import LectureSlide


@dataclass(frozen=True)
class ParsedPage:
    """한 페이지의 파싱 결과."""

    page_number: int  # 1-based
    slide_text: str
    layout_data: list[dict]  # [{"word": "Dijkstra", "bbox": [x1, y1, x2, y2]}, ...]


def parse_pdf(pdf_path: str | Path) -> list[ParsedPage]:
    """PDF 파일에서 페이지별 텍스트와 단어 단위 BBox 를 추출한다.

    좌표는 PDF 포인트 단위이며 `[x1, y1, x2, y2]` 순서다 (SPEC §3 layout_data).
    """
    pages: list[ParsedPage] = []
    with pymupdf.open(pdf_path) as document:
        for index, page in enumerate(document, start=1):
            words = [
                {"word": word[4], "bbox": [round(coord, 2) for coord in word[:4]]}
                for word in page.get_text("words")
                if word[4].strip()
            ]
            pages.append(
                ParsedPage(
                    page_number=index,
                    slide_text=page.get_text("text").strip(),
                    layout_data=words,
                )
            )
    return pages


async def store_pages(
    session: AsyncSession,
    lecture_id: int,
    pages: list[ParsedPage],
    embeddings: list[list[float] | None] | None = None,
) -> int:
    """파싱 결과를 lecture_slides 에 저장한다. 같은 강의를 다시 파싱하면 기존 행을 교체한다."""
    await session.execute(delete(LectureSlide).where(LectureSlide.lecture_id == lecture_id))
    session.add_all(
        LectureSlide(
            lecture_id=lecture_id,
            page_number=page.page_number,
            slide_text=page.slide_text,
            layout_data=page.layout_data,
            embedding=(embeddings[index] if embeddings else None),
        )
        for index, page in enumerate(pages)
    )
    await session.commit()
    return len(pages)
