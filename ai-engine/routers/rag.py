"""복합 벡터 검색 및 LLM 질의응답 라우터 (SPEC §2.2-3: POST /ai/v1/rag/query)."""

from fastapi import APIRouter

router = APIRouter(tags=["rag"])
