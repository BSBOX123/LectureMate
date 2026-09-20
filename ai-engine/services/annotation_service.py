"""매핑된 발화 기반 자동 필기 생성 (SPEC §4.2, §2.1-4).

슬라이드 하나에 붙은 발화들을 LLM 에 넣어 핵심 요약, 시험 힌트, 강조할 단어를 받고,
강조 단어는 PDF 파싱 때 저장한 layout_data 의 BBox 와 매칭해 화면 좌표로 만든다.
"""

import json
import logging
import re
from dataclasses import dataclass

from core.config import settings
from services.llm_client import complete

log = logging.getLogger(__name__)

# SPEC §2.1-4 예시와 같은 색. 앞쪽 단어일수록 진한 색을 쓴다.
HIGHLIGHT_COLORS = ["#FFEB3B", "#FFC107"]

SYSTEM_PROMPT = """너는 대학 강의 필기 도우미다.
교수님이 특정 슬라이드를 설명하며 한 말을 읽고, 학생이 나중에 복습할 수 있는 필기를 만든다.
반드시 아래 JSON 형식으로만 답한다. 다른 말은 쓰지 않는다.

{
  "professor_summary": "교수님이 강조한 핵심 내용 한두 문장 (한국어)",
  "exam_hints": "시험에 나올 만하다고 언급한 내용. 없으면 null",
  "highlight_words": ["슬라이드에 실제로 적혀 있는 강조할 단어", "최대 5개"]
}

highlight_words 는 반드시 슬라이드 텍스트에 그대로 등장하는 단어여야 한다."""

USER_PROMPT = """[슬라이드 {page_number}쪽 내용]
{slide_text}

[교수님 발화]
{speech}"""


@dataclass(frozen=True)
class Annotation:
    """한 슬라이드의 자동 필기 결과."""

    page_number: int
    professor_summary: str
    exam_hints: str | None
    highlight_bboxes: list[dict]
    confidence_score: float


def parse_llm_json(content: str) -> dict:
    """LLM 응답에서 JSON 객체를 뽑는다. 코드 블록으로 감싸는 경우가 많다."""
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", content, re.DOTALL)
    raw = fenced.group(1) if fenced else content
    start, end = raw.find("{"), raw.rfind("}")
    if start == -1 or end == -1:
        raise ValueError(f"JSON 을 찾을 수 없습니다: {content[:200]}")
    return json.loads(raw[start : end + 1])


def match_highlights(words: list[str], layout_data: list[dict]) -> list[dict]:
    """강조 단어를 layout_data 의 BBox 와 매칭한다 (SPEC §2.1-4 highlights).

    PDF 는 단어 단위로 쪼개져 있어 "음수 가중치" 같은 구절은 여러 항목에 걸친다.
    **같은 줄에 연속으로 놓인 단어들만** 하나로 합친다. 같은 단어가 제목과 본문에 모두
    나오는 경우 전부 합치면 페이지를 덮는 거대한 상자가 되기 때문이다.
    이미 칠한 영역과 대부분 겹치는 하이라이트는 버린다.
    """
    highlights: list[dict] = []
    for phrase in words:
        boxes = _find_phrase_boxes(phrase, layout_data)
        if not boxes:
            continue
        bbox = _merge_boxes(boxes)
        if any(_overlap_ratio(bbox, existing["bbox"]) > 0.5 for existing in highlights):
            continue
        highlights.append(
            {
                "word": phrase,
                "bbox": bbox,
                "color": HIGHLIGHT_COLORS[min(len(highlights), len(HIGHLIGHT_COLORS) - 1)],
            }
        )
    return highlights


# 같은 줄로 볼 y 좌표 허용 오차(PDF 포인트)
SAME_LINE_TOLERANCE = 2.0


def _matches(token: str, word: str) -> bool:
    """조사가 붙은 경우까지 고려한 느슨한 일치 ("가중치" vs "가중치가")."""
    return token in word or word in token


def _find_phrase_boxes(phrase: str, layout_data: list[dict]) -> list[list[float]]:
    """구절과 일치하는 첫 번째 연속 단어 구간의 BBox 목록."""
    tokens = [token for token in phrase.split() if token]
    if not tokens:
        return []

    for start in range(len(layout_data)):
        if not _matches(tokens[0], layout_data[start]["word"]):
            continue
        boxes = [layout_data[start]["bbox"]]
        matched = 1
        for offset in range(1, len(tokens)):
            index = start + offset
            if index >= len(layout_data):
                break
            candidate = layout_data[index]
            same_line = abs(candidate["bbox"][1] - layout_data[start]["bbox"][1]) <= SAME_LINE_TOLERANCE
            if not (same_line and _matches(tokens[offset], candidate["word"])):
                break
            boxes.append(candidate["bbox"])
            matched += 1
        if matched == len(tokens):
            return boxes
    return []


def _merge_boxes(boxes: list[list[float]]) -> list[float]:
    """여러 단어 상자를 감싸는 하나의 상자."""
    return [
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    ]


def _overlap_ratio(first: list[float], second: list[float]) -> float:
    """두 상자의 겹치는 넓이 / 더 작은 상자의 넓이."""
    width = min(first[2], second[2]) - max(first[0], second[0])
    height = min(first[3], second[3]) - max(first[1], second[1])
    if width <= 0 or height <= 0:
        return 0.0
    areas = [
        max(0.0, (box[2] - box[0]) * (box[3] - box[1])) for box in (first, second)
    ]
    smallest = min(areas)
    return (width * height) / smallest if smallest else 0.0


def confidence_score(speech_segments: int, highlights: int, requested_words: int) -> float:
    """필기 신뢰도. 발화가 적거나 강조 단어를 슬라이드에서 못 찾으면 낮아진다."""
    speech_factor = min(1.0, speech_segments / 3)
    match_factor = (highlights / requested_words) if requested_words else 0.5
    return round(0.4 + 0.3 * speech_factor + 0.3 * match_factor, 2)


def generate_annotation(
    page_number: int, slide_text: str, layout_data: list[dict], speech_segments: list[str]
) -> Annotation | None:
    """슬라이드 하나의 자동 필기를 만든다. 발화가 없으면 None."""
    if not speech_segments:
        return None

    content = complete(
        SYSTEM_PROMPT,
        USER_PROMPT.format(
            page_number=page_number,
            slide_text=slide_text.strip()[:2000],
            speech="\n".join(speech_segments)[:4000],
        ),
    )
    parsed = parse_llm_json(content)

    requested = [word for word in parsed.get("highlight_words", []) if isinstance(word, str)][:5]
    highlights = match_highlights(requested, layout_data)
    exam_hints = parsed.get("exam_hints")
    return Annotation(
        page_number=page_number,
        professor_summary=str(parsed.get("professor_summary", "")).strip(),
        exam_hints=str(exam_hints).strip() if exam_hints else None,
        highlight_bboxes=highlights,
        confidence_score=confidence_score(len(speech_segments), len(highlights), len(requested)),
    )
