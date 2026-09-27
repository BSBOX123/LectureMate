"""LectureMate AI Engine 진입점.

실행: source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

import asyncio
import logging
import time
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
log = logging.getLogger(__name__)


async def _warm_embedding_model() -> None:
    """임베딩 모델(bge-m3, 약 2GB)을 미리 불러 둔다.

    그냥 두면 서비스 시작 후 **첫 질문**이 모델 로딩 때문에 16초쯤 더 걸린다(실측).
    기동을 막지 않도록 백그라운드에서 받는다. 로딩 중에 질문이 오면 지금과 똑같이 기다린다.
    """
    from starlette.concurrency import run_in_threadpool

    from core.config import settings
    from services.embedding_service import embed_texts

    if not settings.embedding_enabled:
        return
    try:
        started = time.monotonic()
        await run_in_threadpool(embed_texts, ["워밍업"])
        log.info("임베딩 모델 준비 완료 (%.1f초)", time.monotonic() - started)
    except Exception:  # noqa: BLE001 - 워밍업 실패가 서비스를 막으면 안 된다
        log.exception("임베딩 모델 워밍업 실패 (첫 질문이 느려질 수 있다)")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    warmup = asyncio.create_task(_warm_embedding_model())
    yield
    warmup.cancel()
    await engine.dispose()


app = FastAPI(title="LectureMate AI Engine", lifespan=lifespan)

API_PREFIX = "/ai/v1"
app.include_router(materials.router, prefix=API_PREFIX)
app.include_router(recordings.router, prefix=API_PREFIX)
app.include_router(rag.router, prefix=API_PREFIX)
