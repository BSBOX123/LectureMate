"""PDF 파싱 및 BBox 추출 라우터 (SPEC §2.2-1: POST /ai/v1/pdf/parse)."""

from fastapi import APIRouter

router = APIRouter(tags=["pdf"])
