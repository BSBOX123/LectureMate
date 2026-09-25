"""PDF 자료 파싱 및 BBox 추출 라우터 (SPEC §2.2-1: POST /ai/v1/materials/parse)."""

from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_session
from services import pdf_parser
from services.embedding_service import embed_texts

router = APIRouter(tags=["materials"])


class MaterialParseRequest(BaseModel):
    material_id: int = Field(gt=0)
    # 검색은 과목 단위로 이뤄지므로 페이지 행에도 함께 저장한다
    course_id: int = Field(gt=0)
    pdf_path: str


class MaterialParseResponse(BaseModel):
    total_pages: int
    status: Literal["COMPLETED"]


@router.post("/materials/parse", response_model=MaterialParseResponse)
async def parse(
    request: MaterialParseRequest,
    session: Annotated[AsyncSession, Depends(get_session)],
) -> MaterialParseResponse:
    """PDF 에서 페이지별 텍스트와 단어 BBox 를 추출해 material_pages 에 적재한다."""
    if not Path(request.pdf_path).is_file():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"PDF 파일을 찾을 수 없습니다: {request.pdf_path}",
        )

    pages = pdf_parser.parse_pdf(request.pdf_path)
    embeddings = embed_texts([page.page_text for page in pages])
    total_pages = await pdf_parser.store_pages(
        session, request.material_id, request.course_id, pages, embeddings
    )
    return MaterialParseResponse(total_pages=total_pages, status="COMPLETED")
