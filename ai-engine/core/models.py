"""SQLAlchemy ORM 모델.

스키마는 Flyway(`server-core/src/main/resources/db/migration/`)가 소유하며 여기서는 매핑만 한다.

과목(courses) 하나가 자료(course_materials) N개와 녹음(course_recordings) N개를 가진다.
RAG 검색은 과목 단위로 이뤄지므로 하위 테이블에도 `course_id` 가 있다.
`course_materials` / `course_recordings` 는 Spring Boot 가 쓰고 FastAPI 는 읽기만 한다
(출처 표시에 자료·녹음 이름이 필요하다).
"""

from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import BigInteger, DateTime, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from core.database import Base


class CourseMaterial(Base):
    """과목에 속한 PDF 자료 (course_materials). Spring Boot 가 소유한다."""

    __tablename__ = "course_materials"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    course_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    pdf_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    status: Mapped[str] = mapped_column(String(50), nullable=False)
    total_pages: Mapped[int | None] = mapped_column(Integer, nullable=True)


class CourseRecording(Base):
    """과목에 속한 녹음 (course_recordings). Spring Boot 가 소유한다."""

    __tablename__ = "course_recordings"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    course_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    audio_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
    status: Mapped[str] = mapped_column(String(50), nullable=False)
    duration_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)


class MaterialPage(Base):
    """자료의 페이지별 텍스트 + 임베딩 (material_pages). FastAPI 가 적재한다."""

    __tablename__ = "material_pages"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    # FK/CASCADE 는 DB 가 관리한다
    material_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    course_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    page_number: Mapped[int] = mapped_column(Integer, nullable=False)
    page_text: Mapped[str] = mapped_column(Text, nullable=False)
    # 단어별 bbox. 현재 화면에서는 쓰지 않지만 자동 필기(추후 과제)를 되살릴 때 필요하다
    layout_data: Mapped[list[dict]] = mapped_column(JSONB, nullable=False)
    embedding: Mapped[list[float] | None] = mapped_column(Vector(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class RecordingSegment(Base):
    """녹음 전사 세그먼트 + 임베딩 (recording_segments). FastAPI 가 적재한다."""

    __tablename__ = "recording_segments"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    recording_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    course_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    start_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    end_time_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    speaker_text: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[list[float] | None] = mapped_column(Vector(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
