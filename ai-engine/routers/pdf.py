"""PDF 파싱 및 BBox 추출 라우터 (SPEC §2.2-1: POST /ai/v1/pdf/parse)."""

from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_session
from services import pdf_parser
from services.embedding_service import embed_texts

router = APIRouter(tags=["pdf"])


class PdfParseRequest(BaseModel):
    lecture_id: int = Field(gt=0)
    pdf_path: str


class PdfParseResponse(BaseModel):
    total_pages: int
    status: Literal["COMPLETED"]


@router.post("/pdf/parse", response_model=PdfParseResponse)
async def parse(
    request: PdfParseRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> PdfParseResponse:
    """PDF 에서 페이지별 텍스트와 단어 BBox 를 추출해 lecture_slides 에 적재한다."""
    if not Path(request.pdf_path).is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PDF 파일을 찾을 수 없습니다: {request.pdf_path}",
        )

    pages = pdf_parser.parse_pdf(request.pdf_path)
    embeddings = embed_texts([page.slide_text for page in pages])
    total_pages = await pdf_parser.store_pages(session, request.lecture_id, pages, embeddings)
    return PdfParseResponse(total_pages=total_pages, status="COMPLETED")
