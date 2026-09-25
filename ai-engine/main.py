"""LectureMate AI Engine 진입점.

실행: source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from core.database import engine
from routers import materials, rag, recordings

# uvicorn 은 자기 로거만 설정하므로, 이걸 하지 않으면 우리 모듈의 log.info 가 아무것도 찍지 않는다.
# 전사는 십여 분 걸리는 작업이라 진행 로그가 유일한 가시성이다.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    yield
    await engine.dispose()


app = FastAPI(title="LectureMate AI Engine", lifespan=lifespan)

API_PREFIX = "/ai/v1"
app.include_router(materials.router, prefix=API_PREFIX)
app.include_router(recordings.router, prefix=API_PREFIX)
app.include_router(rag.router, prefix=API_PREFIX)
