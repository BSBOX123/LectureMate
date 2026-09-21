"""환경 변수, DB 커넥션 풀, 모델 가중치 경로 관리 (SPEC §4.2, §5.1)."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

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
    # Webhook 공유 시크릿 (SPEC §2.2, X-Internal-Secret 헤더)
    internal_api_secret: str = "local-dev-internal-secret"

    # Models
    # STT 백엔드
    #   mlx: Apple GPU(Metal) 사용. macOS 전용이지만 CPU 대비 약 13배 빠르다 (실측 1분 음성: 208초 → 16초)
    #   faster-whisper: CPU. Linux/EC2 와 테스트 환경용
    stt_backend: Literal["mlx", "faster-whisper"] = "mlx"
    mlx_model_repo: str = "mlx-community/whisper-large-v3-mlx"
    # 배치 정밀 전사용 (SPEC §2.2-2). faster-whisper 백엔드에서 사용
    whisper_model_name: str = "large-v3"
    # 실시간 프리뷰 자막용. 작은 모델이어야 3~5초 청크를 실시간으로 따라갈 수 있다 (SPEC §4.2)
    whisper_realtime_model_name: str = "base"
    whisper_device: str = "cpu"
    whisper_compute_type: str = "int8"
    # None 이면 자동 감지. 매 청크마다 감지하면 느려서 기본은 한국어로 고정한다
    whisper_language: str | None = "ko"
    embedding_model_name: str = "BAAI/bge-m3"
    # 슬라이드-음성 정렬(§4.2)과 RAG 검색(§2.2-3)에 필요하다.
    # 끄면 임베딩과 정렬을 건너뛴다 (모델 약 2GB 다운로드를 피하고 싶을 때)
    embedding_enabled: bool = True
    # 모델 가중치 캐시 경로. None 이면 각 라이브러리(HuggingFace) 기본 캐시 경로 사용
    model_cache_dir: Path | None = None

    # 실시간 오디오 (SPEC §2.1-2: PCM 16kHz 모노 16bit LE, 3~5초 단위)
    audio_sample_rate: int = 16000
    realtime_chunk_seconds: float = 3.0
    # 배치 전사를 나눠 처리하는 단위(초). 통째로 넘기면 Whisper 가 반복 루프에 빠질 수 있다
    batch_window_seconds: float = 300.0

    # LLM 백엔드 선택
    #   claude-code: 로컬 Claude Code CLI (구독 사용량, 품질 높음) — 기본값
    #   ollama: OpenAI 호환 엔드포인트 (사용량 한도에 걸렸을 때의 대체 수단)
    llm_provider: Literal["claude-code", "ollama"] = "claude-code"
    claude_code_command: str = "claude"
    # 비우면 Claude Code 기본 모델을 쓴다
    claude_code_model: str | None = None

    # Ollama / vLLM (llm_provider=ollama 일 때)
    llm_backend_url: str = "http://localhost:11434/v1"
    llm_model_name: str = "qwen2.5:14b-instruct"
    # CPU 추론은 느리다. 슬라이드 한 장당 이 시간을 넘기면 포기한다.
    llm_timeout_seconds: float = 180.0


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
