"""슬라이드-음성 시간 단조성 제약 정렬 (SPEC §4.2).

강의는 슬라이드를 앞에서 뒤로 넘기며 진행한다. 따라서 시간 순으로 나열한 발화 세그먼트에
붙는 슬라이드 번호는 절대 뒤로 돌아가지 않는다(단조 증가). 이 제약을 DP 로 강제하면
한두 세그먼트가 엉뚱한 슬라이드와 유사해도 전체 흐름이 흐트러지지 않는다.
"""

import numpy as np

# 같은 슬라이드에 계속 머무르는 쪽을 살짝 선호한다. 값이 크면 슬라이드 전환이 잘 일어나지 않는다.
SWITCH_PENALTY = 0.05


def cosine_similarity_matrix(
    segment_embeddings: np.ndarray, slide_embeddings: np.ndarray
) -> np.ndarray:
    """(세그먼트 수 x 슬라이드 수) 코사인 유사도 행렬."""
    segments = _normalize(np.asarray(segment_embeddings, dtype=np.float32))
    slides = _normalize(np.asarray(slide_embeddings, dtype=np.float32))
    return segments @ slides.T


def _normalize(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return matrix / np.where(norms == 0, 1.0, norms)


def align_monotonic(similarity: np.ndarray, switch_penalty: float = SWITCH_PENALTY) -> list[int]:
    """시간 순 세그먼트에 슬라이드 인덱스(0-based)를 단조 증가하도록 배정한다.

    dp[i][p] = sim[i][p] + max(dp[i-1][p'] for p' <= p) - (슬라이드를 옮겼으면 penalty)

    Args:
        similarity: (세그먼트 수 x 슬라이드 수) 유사도 행렬. 세그먼트는 시간 순이어야 한다.

    Returns:
        세그먼트별 슬라이드 인덱스. 항상 단조 증가한다.
    """
    segment_count, slide_count = similarity.shape
    if segment_count == 0 or slide_count == 0:
        return []

    scores = np.full((segment_count, slide_count), -np.inf, dtype=np.float32)
    backtrack = np.zeros((segment_count, slide_count), dtype=np.int32)

    scores[0] = similarity[0]
    for i in range(1, segment_count):
        best_previous = -np.inf
        best_index = 0
        for p in range(slide_count):
            # p' <= p 중 최댓값을 prefix max 로 유지한다
            if scores[i - 1][p] > best_previous:
                best_previous = scores[i - 1][p]
                best_index = p
            stayed = best_index == p
            scores[i][p] = (
                similarity[i][p] + best_previous - (0.0 if stayed else switch_penalty)
            )
            backtrack[i][p] = best_index

    path = [0] * segment_count
    path[-1] = int(np.argmax(scores[-1]))
    for i in range(segment_count - 1, 0, -1):
        path[i - 1] = int(backtrack[i][path[i]])
    return path


def align_segments_to_pages(
    segment_embeddings: np.ndarray, slide_embeddings: np.ndarray, page_numbers: list[int]
) -> list[int]:
    """정렬 결과를 실제 슬라이드 페이지 번호로 돌려준다.

    Args:
        page_numbers: slide_embeddings 각 행에 대응하는 페이지 번호 (오름차순).
    """
    similarity = cosine_similarity_matrix(segment_embeddings, slide_embeddings)
    return [page_numbers[index] for index in align_monotonic(similarity)]
