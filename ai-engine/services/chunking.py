"""전사 세그먼트를 검색용 덩어리로 묶는다.

Whisper 가 끊어 준 단위를 그대로 한 행으로 쓰면 검색이 망가진다. 실측(9월 28일 수업 71분):

    전체 1,373개 중 10자 미만이 501개(36.5%), 20자 미만이 786개(57%)
    "박준오" "유호찬" "이다은" ... 출석 부르는 소리가 각각 한 행

20자짜리 조각에는 임베딩할 의미가 거의 없다. 그래서 "교수님이 강조한 부분" 같은 질문에
"수요일 강의가 인정이 됩니다" 같은 무관한 조각이 걸렸다. 검색이 나쁜 게 아니라 **검색 대상이
잘게 부서져 있었다.**

이웃 세그먼트를 묶어 자료 페이지와 비슷한 크기(평균 310자)로 만든다. 원본 세그먼트는 그대로
남긴다 — 요약이 정밀한 타임스탬프를 쓰고, 다시 묶으려면 원본이 있어야 한다.
"""

from dataclasses import dataclass

# 한 덩어리의 목표 크기(글자). 자료 페이지 평균이 310자라 비슷하게 맞춘다.
TARGET_CHARS = 300
# 개념이 덩어리 경계에 걸쳐 잘리는 것을 줄이려고 앞 덩어리의 끝을 조금 물고 시작한다.
OVERLAP_CHARS = 60


@dataclass(frozen=True)
class Chunk:
    """검색 단위. 타임스탬프는 덩어리가 시작·끝나는 실제 시각이다."""

    start_time_ms: int
    end_time_ms: int
    text: str


def build_chunks(
    segments: list[tuple[int, int, str]],
    target_chars: int = TARGET_CHARS,
    overlap_chars: int = OVERLAP_CHARS,
) -> list[Chunk]:
    """`(start_ms, end_ms, text)` 목록을 목표 크기의 덩어리로 묶는다.

    한 세그먼트가 이미 목표보다 크면 그대로 한 덩어리가 된다 (문장 중간을 자르지 않는다).
    """
    kept = [(s, e, t.strip()) for s, e, t in segments if t and t.strip()]
    if not kept:
        return []

    chunks: list[Chunk] = []
    current: list[tuple[int, int, str]] = []
    length = 0

    for segment in kept:
        current.append(segment)
        length += len(segment[2]) + 1  # 띄어쓰기 한 칸
        if length < target_chars:
            continue
        chunks.append(_merge(current))
        current = _overlap_tail(current, overlap_chars)
        length = sum(len(t) + 1 for _, _, t in current)

    # 남은 것이 겹침 부분뿐이면 이미 앞 덩어리에 들어가 있다
    if current and not _is_only_overlap(current, chunks):
        chunks.append(_merge(current))
    return chunks


def _merge(segments: list[tuple[int, int, str]]) -> Chunk:
    return Chunk(
        start_time_ms=segments[0][0],
        end_time_ms=segments[-1][1],
        text=" ".join(text for _, _, text in segments),
    )


def _overlap_tail(
    segments: list[tuple[int, int, str]], overlap_chars: int
) -> list[tuple[int, int, str]]:
    """다음 덩어리가 물고 갈 꼬리. 글자 수가 찰 때까지 뒤에서부터 담는다."""
    if overlap_chars <= 0:
        return []
    tail: list[tuple[int, int, str]] = []
    total = 0
    for segment in reversed(segments):
        tail.insert(0, segment)
        total += len(segment[2])
        if total >= overlap_chars:
            break
    # 전부를 물고 가면 같은 덩어리가 반복된다
    return tail if len(tail) < len(segments) else tail[1:]


def _is_only_overlap(current: list[tuple[int, int, str]], chunks: list[Chunk]) -> bool:
    if not chunks:
        return False
    return _merge(current).end_time_ms <= chunks[-1].end_time_ms
