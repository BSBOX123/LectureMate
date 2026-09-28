"""chunking 단위 테스트 (DB·모델 불필요)."""

from services.chunking import build_chunks


def seg(start_s: int, end_s: int, text: str) -> tuple[int, int, str]:
    return (start_s * 1000, end_s * 1000, text)


def test_merges_short_segments_until_target():
    """Whisper 가 끊어 준 짧은 조각들을 목표 크기까지 묶는다."""
    segments = [seg(i, i + 1, "가" * 30) for i in range(10)]

    chunks = build_chunks(segments, target_chars=100, overlap_chars=0)

    assert all(len(c.text) >= 100 for c in chunks[:-1])
    # 원본보다 확실히 적어진다
    assert len(chunks) < len(segments)


def test_timestamps_span_the_merged_segments():
    """덩어리의 시각은 첫 세그먼트 시작 ~ 마지막 세그먼트 끝이다."""
    segments = [seg(0, 5, "가" * 60), seg(5, 10, "나" * 60), seg(10, 20, "다" * 60)]

    chunks = build_chunks(segments, target_chars=150, overlap_chars=0)

    assert chunks[0].start_time_ms == 0
    assert chunks[0].end_time_ms == 20_000
    assert chunks[0].text == "가" * 60 + " " + "나" * 60 + " " + "다" * 60


def test_overlap_carries_tail_into_next_chunk():
    """개념이 경계에 걸려 잘리지 않도록 앞 덩어리의 끝을 물고 시작한다."""
    segments = [seg(i, i + 1, f"문장{i}" + "가" * 50) for i in range(8)]

    chunks = build_chunks(segments, target_chars=120, overlap_chars=40)

    assert len(chunks) >= 2
    # 두 번째 덩어리는 첫 덩어리가 끝나기 전에 시작한다 (시간이 겹친다)
    assert chunks[1].start_time_ms < chunks[0].end_time_ms


def test_long_segment_is_not_split():
    """이미 목표보다 긴 세그먼트는 문장 중간을 자르지 않고 그대로 둔다."""
    segments = [seg(0, 60, "가" * 900)]

    chunks = build_chunks(segments, target_chars=300)

    assert len(chunks) == 1
    assert len(chunks[0].text) == 900


def test_skips_empty_segments():
    """무음 구간에서 빈 텍스트가 나올 수 있다."""
    segments = [seg(0, 1, ""), seg(1, 2, "   "), seg(2, 3, "실제 내용")]

    chunks = build_chunks(segments, target_chars=300)

    assert len(chunks) == 1
    assert chunks[0].text == "실제 내용"
    assert chunks[0].start_time_ms == 2000


def test_no_segments_gives_no_chunks():
    assert build_chunks([]) == []
    assert build_chunks([seg(0, 1, "  ")]) == []


def test_tail_is_not_emitted_twice():
    """마지막에 남은 것이 겹침 부분뿐이면 같은 내용을 또 넣지 않는다."""
    segments = [seg(i, i + 1, "가" * 50) for i in range(6)]

    chunks = build_chunks(segments, target_chars=100, overlap_chars=40)

    ends = [c.end_time_ms for c in chunks]
    assert ends == sorted(ends)
    # 마지막 덩어리가 앞 덩어리 안에 완전히 포함되지 않는다
    assert chunks[-1].end_time_ms > chunks[-2].end_time_ms


def test_realistic_lecture_shape():
    """실제 분포(10자 미만이 36%)를 흉내낸 입력에서 덩어리 크기가 고르게 나온다."""
    segments = []
    for i in range(300):
        text = "이름" if i % 3 == 0 else "그래서 이 부분이 중요한 내용입니다" * 2
        segments.append(seg(i * 4, i * 4 + 4, text))

    chunks = build_chunks(segments)

    lengths = [len(c.text) for c in chunks]
    assert len(chunks) < len(segments) / 4
    assert min(lengths) >= 100  # 지나치게 짧은 덩어리가 남지 않는다
