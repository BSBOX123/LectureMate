package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.HighlightBox;
import com.lecturemate.domain.entity.SlideAnnotation;
import java.util.List;

/** SPEC §2.1-4 슬라이드 자동 필기 조회 응답. */
public record PageAnnotationResponse(
    int pageNumber,
    String professorSummary,
    String examHints,
    double confidenceScore,
    List<HighlightBox> highlights) {

  public static PageAnnotationResponse from(SlideAnnotation annotation) {
    return new PageAnnotationResponse(
        annotation.getPageNumber(),
        annotation.getProfessorSummary(),
        annotation.getExamHints(),
        annotation.getConfidenceScore(),
        annotation.getHighlightBboxes());
  }
}
