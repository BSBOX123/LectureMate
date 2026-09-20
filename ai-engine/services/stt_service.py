"""Faster-Whisper 모델 관리 (SPEC §4.2).

실시간 프리뷰는 작은 모델(base), 배치 정밀 전사는 large-v3 를 쓴다.
모델은 첫 호출 때 내려받아 캐시하며, 이후에는 메모리에 유지한다.
"""

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

from core.config import settings

if TYPE_CHECKING:
    from faster_whisper import WhisperModel


@lru_cache(maxsize=2)
def load_model(model_name: str) -> "WhisperModel":
    from faster_whisper import WhisperModel

    return WhisperModel(
        model_name,
        device=settings.whisper_device,
        compute_type=settings.whisper_compute_type,
        download_root=str(settings.model_cache_dir) if settings.model_cache_dir else None,
    )


def pcm16_to_float32(pcm: bytes) -> np.ndarray:
    """16bit LE PCM 바이트를 Whisper 입력용 [-1, 1] float32 배열로 바꾼다."""
    return np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0


def transcribe_pcm(pcm: bytes, model_name: str | None = None) -> str:
    """PCM 청크를 전사한다. 말이 없으면 빈 문자열."""
    audio = pcm16_to_float32(pcm)
    if audio.size == 0:
        return ""
    model = load_model(model_name or settings.whisper_realtime_model_name)
    segments, _ = model.transcribe(
        audio,
        language=settings.whisper_language,
        beam_size=1,
        vad_filter=True,
    )
    return " ".join(segment.text.strip() for segment in segments).strip()


@dataclass(frozen=True)
class TranscribedSegment:
    """전사 세그먼트 하나 (SPEC §3 lecture_transcripts)."""

    start_time_ms: int
    end_time_ms: int
    text: str


def transcribe_file(audio_path: str | Path, model_name: str | None = None) -> list[TranscribedSegment]:
    """WAV 파일 전체를 정밀 전사한다 (SPEC §2.2-2). 기본 모델은 large-v3."""
    model = load_model(model_name or settings.whisper_model_name)
    segments, _ = model.transcribe(
        str(audio_path),
        language=settings.whisper_language,
        vad_filter=True,
    )
    return [
        TranscribedSegment(
            start_time_ms=int(segment.start * 1000),
            end_time_ms=int(segment.end * 1000),
            text=segment.text.strip(),
        )
        for segment in segments
        if segment.text.strip()
    ]
