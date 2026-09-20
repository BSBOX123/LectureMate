"""stt_service 단위 테스트 (모델 로딩 없이 변환 로직만)."""

import numpy as np

from services.stt_service import pcm16_to_float32


def test_pcm16_to_float32_scales_to_unit_range():
    pcm = np.array([0, 32767, -32768, 16384], dtype="<i2").tobytes()

    audio = pcm16_to_float32(pcm)

    assert audio.dtype == np.float32
    assert audio[0] == 0.0
    assert audio[1] == np.float32(32767 / 32768)
    assert audio[2] == -1.0
    assert abs(audio[3] - 0.5) < 1e-6


def test_empty_pcm_returns_empty_array():
    assert pcm16_to_float32(b"").size == 0
