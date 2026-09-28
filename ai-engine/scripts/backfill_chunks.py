"""이미 전사된 녹음에 검색용 덩어리를 만들어 넣는다 (1회성).

V7 이전에 전사한 녹음은 `recording_segments` 만 있고 `recording_chunks` 가 없다. 전사를 다시
돌리면 71분 녹음에 42분이 걸리므로, **DB 에 있는 세그먼트를 묶어 임베딩만 새로 만든다.**

    source .venv/bin/activate && python scripts/backfill_chunks.py [--dry-run]
"""

import asyncio
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import delete, func, select  # noqa: E402

from core.database import AsyncSessionLocal  # noqa: E402
from core.models import CourseRecording, RecordingChunk, RecordingSegment  # noqa: E402
from services.chunking import build_chunks  # noqa: E402
from services.embedding_service import embed_texts  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(message)s")
log = logging.getLogger("backfill")


async def main(dry_run: bool) -> None:
    async with AsyncSessionLocal() as session:
        recordings = (
            await session.execute(
                select(CourseRecording.id, CourseRecording.course_id, CourseRecording.title)
                .join(RecordingSegment, RecordingSegment.recording_id == CourseRecording.id)
                .group_by(CourseRecording.id, CourseRecording.course_id, CourseRecording.title)
                .order_by(CourseRecording.id)
            )
        ).all()

        for recording_id, course_id, title in recordings:
            existing = (
                await session.execute(
                    select(func.count()).where(RecordingChunk.recording_id == recording_id)
                )
            ).scalar_one()

            rows = (
                await session.execute(
                    select(
                        RecordingSegment.start_time_ms,
                        RecordingSegment.end_time_ms,
                        RecordingSegment.speaker_text,
                    )
                    .where(RecordingSegment.recording_id == recording_id)
                    .order_by(RecordingSegment.start_time_ms)
                )
            ).all()

            chunks = build_chunks([(a, b, c) for a, b, c in rows])
            log.info(
                "녹음 %s (%s): 세그먼트 %s개 → 덩어리 %s개 (기존 %s개)",
                recording_id,
                title,
                len(rows),
                len(chunks),
                existing,
            )
            if dry_run or not chunks:
                continue

            embeddings = embed_texts([chunk.text for chunk in chunks])
            await session.execute(
                delete(RecordingChunk).where(RecordingChunk.recording_id == recording_id)
            )
            session.add_all(
                RecordingChunk(
                    recording_id=recording_id,
                    course_id=course_id,
                    start_time_ms=chunk.start_time_ms,
                    end_time_ms=chunk.end_time_ms,
                    chunk_text=chunk.text,
                    embedding=embedding,
                )
                for chunk, embedding in zip(chunks, embeddings, strict=True)
            )
            await session.commit()
            log.info("  적재 완료")


if __name__ == "__main__":
    asyncio.run(main("--dry-run" in sys.argv))
