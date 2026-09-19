"""LectureMate AI Engine 진입점.

실행: source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from core.database import engine
from routers import audio, pdf, rag


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    yield
    await engine.dispose()


app = FastAPI(title="LectureMate AI Engine", lifespan=lifespan)

API_PREFIX = "/ai/v1"
app.include_router(pdf.router, prefix=API_PREFIX)
app.include_router(audio.router, prefix=API_PREFIX)
app.include_router(rag.router, prefix=API_PREFIX)
