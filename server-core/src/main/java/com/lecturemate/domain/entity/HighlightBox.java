package com.lecturemate.domain.entity;

import java.util.List;

/**
 * {@code slide_annotations.highlight_bboxes} JSONB 배열의 원소. SPEC §2.1-4 응답의 {@code highlights}.
 *
 * @param word 강조 단어
 * @param bbox [x1, y1, x2, y2] PDF 좌표
 * @param color 하이라이트 색상 (예: {@code #FFEB3B})
 */
public record HighlightBox(String word, List<Double> bbox, String color) {}
