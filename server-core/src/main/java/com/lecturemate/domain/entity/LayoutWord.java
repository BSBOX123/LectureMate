package com.lecturemate.domain.entity;

import java.util.List;

/**
 * {@code lecture_slides.layout_data} JSONB 배열의 원소. PyMuPDF 가 추출한 단어와 좌표.
 *
 * @param word 단어
 * @param bbox [x1, y1, x2, y2] PDF 좌표
 */
public record LayoutWord(String word, List<Double> bbox) {}
