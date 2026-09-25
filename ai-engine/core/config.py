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
    # 정밀 전사용 (SPEC §2.2-2). faster-whisper 백엔드에서 사용
    whisper_model_name: str = "large-v3"
    whisper_device: str = "cpu"
    whisper_compute_type: str = "int8"
    # None 이면 자동 감지. 감지는 느리고 한국어 강의가 대상이라 기본은 고정한다
    whisper_language: str | None = "ko"
    embedding_model_name: str = "BAAI/bge-m3"
    # RAG 검색(§2.2-3)에 필요하다.
    # 끄면 임베딩을 건너뛴다 (모델 약 2GB 다운로드를 피하고 싶을 때)
    embedding_enabled: bool = True
    # 모델 가중치 캐시 경로. None 이면 각 라이브러리(HuggingFace) 기본 캐시 경로 사용
    model_cache_dir: Path | None = None

    # 녹음 오디오 (SPEC §2.1-2: PCM 16kHz 모노 16bit LE)
    audio_sample_rate: int = 16000
    # 전사를 나눠 처리하는 단위(초). 통째로 넘기면 Whisper 가 반복 루프에 빠질 수 있다.
    # 300초 → 120초로 낮췄다. 실측(4분 구간): 165초 → 112초로 빨라지고, Whisper 가 무의미한
    # 토큰을 뱉는 구간도 최장 118자 → 60자로 줄었다. 구간을 짧게 끊으면 피해가 그 구간에서 멈춘다.
    batch_window_seconds: float = 120.0
    # 과목 자료에서 뽑은 용어 사전을 Whisper 에 넘길지 (glossary_service).
    # **기본은 끔.** 실측에서 사전을 넣으면 오히려 핵심 용어를 틀렸다 (사전 없이 "외래키·주키",
    # 사전 있으면 "외의 key·주 key"). 측정 표는 docs/progress/step-21 참고.
    # 자료 성격이 다른 과목에서는 도움이 될 수 있어 코드는 남겨 두었다.
    stt_glossary_enabled: bool = False

    # LLM 백엔드 선택
    #   claude-code: 로컬 Claude Code CLI (구독 사용량, 품질 높음) — 기본값
    #   ollama: OpenAI 호환 엔드포인트 (사용량 한도에 걸렸을 때의 대체 수단)
    llm_provider: Literal["claude-code", "ollama"] = "claude-code"
    claude_code_command: str = "claude"
    # 실측(같은 RAG 질문 3회): opus 7.5~10.0초, haiku 6.6~7.0초, sonnet 4.8~5.1초.
    # 가장 작은 haiku 가 가장 빠르지는 않다. 복습 질의응답에는 sonnet 품질이면 충분하다.
    claude_code_model: str | None = "sonnet"

    # Ollama / vLLM (llm_provider=ollama 일 때)
    llm_backend_url: str = "http://localhost:11434/v1"
    llm_model_name: str = "qwen2.5:14b-instruct"
    # 답변 하나가 이 시간을 넘기면 포기한다.
    llm_timeout_seconds: float = 180.0


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
