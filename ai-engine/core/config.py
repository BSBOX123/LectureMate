"""환경 변수, DB 커넥션 풀, 모델 가중치 경로 관리 (SPEC §4.2, §5.1)."""

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """`.env` 또는 OS 환경 변수에서 값을 읽는다. 기본값은 SPEC §5.1 로컬 개발 값."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Database
    database_url: str = (
        "postgresql+asyncpg://postgres:postgres@localhost:5432/lecturemate"
    )
    db_pool_size: int = 10
    db_max_overflow: int = 20
    db_echo: bool = False

    # Spring Boot 배치 완료 Webhook (SPEC §2.2-4)
    spring_boot_webhook_url: str = "http://localhost:8080/internal/v1"

    # Models
    whisper_model_name: str = "large-v3"
    embedding_model_name: str = "BAAI/bge-m3"
    # 모델 가중치 캐시 경로. None 이면 각 라이브러리(HuggingFace) 기본 캐시 경로 사용
    model_cache_dir: Path | None = None

    # LLM (Ollama / vLLM OpenAI 호환 엔드포인트)
    llm_backend_url: str = "http://localhost:11434/v1"
    llm_model_name: str = "qwen2.5:14b-instruct"


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
