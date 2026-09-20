"""annotation_service 단위 테스트 (LLM 호출 없이 파싱·매칭 로직만)."""

import pytest

from services.annotation_service import (
    confidence_score,
    generate_annotation,
    match_highlights,
    parse_llm_json,
)


def test_parses_plain_json():
    assert parse_llm_json('{"professor_summary": "요약"}') == {"professor_summary": "요약"}


def test_parses_json_inside_code_fence():
    content = '설명입니다\n```json\n{"exam_hints": "중간고사"}\n```\n끝'
    assert parse_llm_json(content) == {"exam_hints": "중간고사"}


def test_raises_when_no_json():
    with pytest.raises(ValueError):
        parse_llm_json("JSON 이 없습니다")


def test_matches_single_word_to_its_bbox():
    layout = [
        {"word": "다익스트라", "bbox": [10, 20, 60, 30]},
        {"word": "알고리즘", "bbox": [70, 20, 110, 30]},
    ]

    highlights = match_highlights(["다익스트라"], layout)

    assert highlights == [{"word": "다익스트라", "bbox": [10, 20, 60, 30], "color": "#FFEB3B"}]


def test_merges_boxes_for_multi_word_phrase():
    layout = [
        {"word": "음수", "bbox": [10, 20, 40, 30]},
        {"word": "가중치가", "bbox": [45, 20, 90, 32]},
        {"word": "무관", "bbox": [200, 90, 240, 100]},
    ]

    highlights = match_highlights(["음수 가중치"], layout)

    # 같은 줄의 연속 단어를 감싸는 하나의 상자로 합쳐진다 (조사가 붙어도 매칭)
    assert highlights[0]["bbox"] == [10, 20, 90, 32]


def test_does_not_merge_across_lines():
    """같은 단어가 제목과 본문에 모두 있으면, 둘을 합쳐 거대한 상자를 만들면 안 된다."""
    layout = [
        {"word": "최단", "bbox": [204, 98, 248, 124]},
        {"word": "경로", "bbox": [270, 98, 314, 124]},  # 제목 줄
        {"word": "경로를", "bbox": [150, 186, 200, 202]},  # 본문 줄
    ]

    highlights = match_highlights(["최단 경로"], layout)

    assert highlights[0]["bbox"] == [204, 98, 314, 124]  # 제목 줄만


def test_drops_highlight_overlapping_an_earlier_one():
    layout = [
        {"word": "다익스트라", "bbox": [10, 10, 100, 30]},
        {"word": "알고리즘", "bbox": [110, 10, 180, 30]},
    ]

    # 두 번째 구절은 첫 번째가 이미 덮은 영역과 같다
    highlights = match_highlights(["다익스트라 알고리즘", "다익스트라"], layout)

    assert [h["word"] for h in highlights] == ["다익스트라 알고리즘"]


def test_uses_only_first_occurrence_of_a_word():
    layout = [
        {"word": "경로", "bbox": [10, 10, 50, 26]},
        {"word": "경로", "bbox": [10, 100, 50, 116]},
    ]

    assert match_highlights(["경로"], layout)[0]["bbox"] == [10, 10, 50, 26]


def test_skips_words_not_on_the_slide():
    layout = [{"word": "다익스트라", "bbox": [1, 2, 3, 4]}]

    assert match_highlights(["벨만포드"], layout) == []


def test_assigns_different_colors_by_order():
    layout = [
        {"word": "가", "bbox": [0, 0, 1, 1]},
        {"word": "나", "bbox": [2, 0, 3, 1]},
    ]

    colors = [h["color"] for h in match_highlights(["가", "나"], layout)]

    assert colors == ["#FFEB3B", "#FFC107"]


def test_confidence_reflects_speech_and_matches():
    full = confidence_score(speech_segments=5, highlights=3, requested_words=3)
    partial = confidence_score(speech_segments=1, highlights=1, requested_words=3)

    assert full == 1.0
    assert partial < full


def test_no_speech_returns_none():
    assert generate_annotation(1, "슬라이드", [], []) is None
