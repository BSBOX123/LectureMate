"""음성 인식 모델 관리 (SPEC §4.2).

백엔드가 두 가지다 (`STT_BACKEND`).

- `mlx`: Apple GPU(Metal)를 쓰는 mlx-whisper. macOS 전용이고 CPU 대비 약 13배 빠르다.
  실측(1분 한국어 강의): faster-whisper CPU 208초 → mlx 16초.
- `faster-whisper`: CPU. Linux/EC2 와 테스트 환경에서 쓴다.

전사는 large-v3 를 쓴다. 녹음 중에는 모델을 돌리지 않고(실시간 자막 없음),
녹음이 끝난 뒤 저장된 WAV 를 한 번에 처리한다.
"""

import logging
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING

from core.config import settings

log = logging.getLogger(__name__)

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


@dataclass(frozen=True)
class TranscribedSegment:
    """전사 세그먼트 하나 (SPEC §3 recording_segments)."""

    start_time_ms: int
    end_time_ms: int
    text: str


def transcribe_file(
    audio_path: str | Path,
    model_name: str | None = None,
    initial_prompt: str | None = None,
) -> list[TranscribedSegment]:
    """WAV 파일 전체를 정밀 전사한다 (SPEC §2.2-2).

    `initial_prompt` 은 과목 자료에서 뽑은 용어 사전이다 (glossary_service). 전문 용어를 미리
    알려 주면 같은 발음을 그 용어로 옮길 가능성이 올라간다.
    """
    if settings.stt_backend == "mlx":
        return _transcribe_file_mlx(audio_path, initial_prompt)
    return _transcribe_file_faster_whisper(audio_path, model_name, initial_prompt)


def _transcribe_file_mlx(
    audio_path: str | Path, initial_prompt: str | None = None
) -> list[TranscribedSegment]:
    """구간을 나눠 전사한다.

    통째로 넘기면 Whisper 가 반복 루프에 빠져 끝나지 않을 수 있다(36분 강의에서 4.5시간 경과 후에도
    미완료). `condition_on_previous_text=False` 로 앞 구간 텍스트를 물고 늘어지지 않게 하고,
    구간을 나눠 한 구간이 망가져도 전체가 멈추지 않게 한다.

    용어 사전(`initial_prompt`)은 구간마다 다시 넘긴다. Whisper 는 `condition_on_previous_text`
    가 꺼져 있으면 첫 30초 뒤에 프롬프트를 버리므로(`prompt_reset_since`), 구간을 나눠 호출하는
    지금 구조가 오히려 사전을 여러 번 먹이는 셈이 된다.
    """
    import mlx_whisper
    import numpy as np

    audio = _load_wav_float32(audio_path)
    window = int(settings.audio_sample_rate * settings.batch_window_seconds)
    segments: list[TranscribedSegment] = []

    for offset in range(0, len(audio), window):
        chunk = audio[offset : offset + window]
        if chunk.size == 0:
            continue
        result = mlx_whisper.transcribe(
            chunk,
            path_or_hf_repo=settings.mlx_model_repo,
            language=settings.whisper_language,
            condition_on_previous_text=False,
            initial_prompt=initial_prompt,
        )
        base_ms = int(offset / settings.audio_sample_rate * 1000)
        segments.extend(
            TranscribedSegment(
                start_time_ms=base_ms + int(segment["start"] * 1000),
                end_time_ms=base_ms + int(segment["end"] * 1000),
                text=segment["text"].strip(),
            )
            for segment in result.get("segments", [])
            if segment.get("text", "").strip()
        )
        log.info(
            "전사 진행 %s/%s초", min(offset + window, len(audio)) // settings.audio_sample_rate,
            len(audio) // settings.audio_sample_rate,
        )
    return segments


def _load_wav_float32(audio_path: str | Path):
    """16bit PCM WAV 를 [-1, 1] float32 배열로 읽는다 (우리가 저장하는 형식)."""
    import wave

    import numpy as np

    with wave.open(str(audio_path), "rb") as wav:
        frames = wav.readframes(wav.getnframes())
    return np.frombuffer(frames, dtype="<i2").astype(np.float32) / 32768.0


def _transcribe_file_faster_whisper(
    audio_path: str | Path, model_name: str | None, initial_prompt: str | None = None
) -> list[TranscribedSegment]:
    model = load_model(model_name or settings.whisper_model_name)
    segments, _ = model.transcribe(
        str(audio_path),
        language=settings.whisper_language,
        vad_filter=True,
        initial_prompt=initial_prompt,
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
