"""정렬 알고리즘 단위 테스트 (SPEC §4.2 단조성 제약)."""

import numpy as np

from services.alignment_service import (
    align_monotonic,
    align_segments_to_pages,
    cosine_similarity_matrix,
)


def one_hot(index: int, size: int = 4) -> list[float]:
    vector = [0.0] * size
    vector[index] = 1.0
    return vector


def test_assigns_each_segment_to_its_matching_slide():
    # 세그먼트가 슬라이드 0, 0, 1, 2 와 각각 똑같은 내용인 경우
    segments = np.array([one_hot(0), one_hot(0), one_hot(1), one_hot(2)])
    slides = np.array([one_hot(0), one_hot(1), one_hot(2)])

    assert align_segments_to_pages(segments, slides, [1, 2, 3]) == [1, 1, 2, 3]


def test_never_goes_backwards_even_if_similarity_says_so():
    # 세 번째 세그먼트가 첫 슬라이드와 가장 비슷해도 뒤로 돌아가면 안 된다
    segments = np.array([one_hot(0), one_hot(1), one_hot(0), one_hot(2)])
    slides = np.array([one_hot(0), one_hot(1), one_hot(2)])

    pages = align_segments_to_pages(segments, slides, [1, 2, 3])

    assert pages == sorted(pages), f"단조 증가해야 한다: {pages}"
    assert pages[0] == 1 and pages[-1] == 3


def test_single_slide_absorbs_everything():
    segments = np.array([one_hot(0), one_hot(1), one_hot(2)])
    slides = np.array([one_hot(0)])

    assert align_segments_to_pages(segments, slides, [7]) == [7, 7, 7]


def test_empty_inputs():
    assert align_monotonic(np.zeros((0, 3))) == []
    assert align_monotonic(np.zeros((3, 0))) == []


def test_cosine_similarity_handles_zero_vectors():
    similarity = cosine_similarity_matrix(
        np.array([[0.0, 0.0], [1.0, 0.0]]), np.array([[1.0, 0.0]])
    )

    assert similarity.shape == (2, 1)
    assert similarity[0][0] == 0.0  # 0 벡터는 0 유사도
    assert abs(similarity[1][0] - 1.0) < 1e-6


def test_switch_penalty_prefers_staying_on_a_slide():
    # 두 슬라이드 유사도가 거의 같으면 이전 슬라이드에 머무른다
    similarity = np.array([[1.0, 0.0], [0.5, 0.52]])

    assert align_monotonic(similarity, switch_penalty=0.1) == [0, 0]
    assert align_monotonic(similarity, switch_penalty=0.0) == [0, 1]
