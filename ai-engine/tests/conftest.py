"""테스트는 전용 DB(lecturemate_test)를 사용한다.

core.config 가 import 시점에 환경 변수를 읽으므로 그 전에 설정해야 한다.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://postgres:postgres@localhost:5432/lecturemate_test",
)

# 테스트는 bge-m3 모델(약 2GB)을 내려받지 않는다.
# 임베딩이 필요한 테스트는 embed_texts 를 직접 대체한다.
os.environ.setdefault("EMBEDDING_ENABLED", "false")

# 테스트가 실제 LLM(Claude Code 구독 사용량)을 쓰지 않도록 실행 불가능한 명령을 지정한다.
# LLM 이 필요한 테스트는 stream_answer 를 직접 대체한다.
os.environ.setdefault("CLAUDE_CODE_COMMAND", "/nonexistent/claude-for-tests")

# mlx 는 Apple Silicon 전용이라 CI(Linux)에서 설치되지 않는다. 테스트는 CPU 백엔드로 고정한다.
os.environ.setdefault("STT_BACKEND", "faster-whisper")

import pytest_asyncio  # noqa: E402 - 위 환경 변수 설정 이후에 import 해야 한다
from httpx import ASGITransport, AsyncClient  # noqa: E402
from sqlalchemy import text  # noqa: E402

import main  # noqa: E402
from core.database import AsyncSessionLocal  # noqa: E402

# 이 테스트들이 만든 행만 골라 지우기 위한 표식. 실제 사용자 데이터는 건드리지 않는다.
TEST_EMAIL = "ai-engine-test@example.com"


async def execute(statement: str, **params):
    """테스트용 raw SQL 실행 헬퍼."""
    async with AsyncSessionLocal() as session:
        result = await session.execute(text(statement), params)
        await session.commit()
        return result


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=main.app), base_url="http://test"
    ) as async_client:
        yield async_client


@pytest_asyncio.fixture
async def course_id():
    """테스트용 user/course 행을 만들고 끝나면 지운다 (하위 행은 FK CASCADE 로 함께 지워진다)."""
    await execute("DELETE FROM users WHERE email = :email", email=TEST_EMAIL)
    result = await execute(
        """
        WITH new_user AS (
            INSERT INTO users (email, password_hash, name)
            VALUES (:email, 'x', '테스트')
            RETURNING id
        )
        INSERT INTO courses (user_id, title)
        SELECT id, '알고리즘' FROM new_user
        RETURNING id
        """,
        email=TEST_EMAIL,
    )
    created = result.scalar_one()
    yield created
    await execute("DELETE FROM users WHERE email = :email", email=TEST_EMAIL)


@pytest_asyncio.fixture
async def material_id(course_id):
    result = await execute(
        "INSERT INTO course_materials (course_id, title, status)"
        " VALUES (:course_id, '알고리즘 5강', 'PROCESSING') RETURNING id",
        course_id=course_id,
    )
    return result.scalar_one()


@pytest_asyncio.fixture
async def recording_id(course_id):
    result = await execute(
        "INSERT INTO course_recordings (course_id, title, status)"
        " VALUES (:course_id, '10월 2일 수업', 'UPLOADED') RETURNING id",
        course_id=course_id,
    )
    return result.scalar_one()
