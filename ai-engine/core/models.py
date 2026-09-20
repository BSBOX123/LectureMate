"""SQLAlchemy ORM 모델. 스키마 정의는 db/init.sql 이 소유하며 여기서는 매핑만 한다."""

from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import BigInteger, DateTime, Integer, Text, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base


class LectureTranscript(Base):
    """오디오 전사 세그먼트 (lecture_transcripts). FastAPI 가 적재한다."""

    __tablename__ = "lecture_transcripts"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    lecture_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    start_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    end_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    speaker_text: Mapped[str] = mapped_column(Text, nullable=False)
    # 슬라이드 정렬(alignment_service)은 다음 단계에서 채운다
    matched_slide_page: Mapped[int | None] = mapped_column(Integer, nullable=True)
    embedding: Mapped[list[float] | None] = mapped_column(Vector(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class LectureSlide(Base):
    """PDF 슬라이드 페이지 (lecture_slides). FastAPI 가 적재하고 Spring Boot 는 조회만 한다."""

    __tablename__ = "lecture_slides"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    # FK/CASCADE 는 DB(db/init.sql)가 관리한다. lectures 를 ORM 에 매핑하지 않으므로 선언하지 않는다.
    lecture_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    page_number: Mapped[int] = mapped_column(Integer, nullable=False)
    slide_text: Mapped[str] = mapped_column(Text, nullable=False)
    layout_data: Mapped[list[dict]] = mapped_column(JSONB, nullable=False)
    embedding: Mapped[list[float] | None] = mapped_column(Vector(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
