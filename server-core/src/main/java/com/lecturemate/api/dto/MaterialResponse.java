package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.CourseMaterial;
import com.lecturemate.domain.entity.MaterialStatus;

/** PDF 자료 응답 (SPEC §2.1-4, §2.1-5). */
public record MaterialResponse(
    Long materialId, String title, MaterialStatus status, String pdfUrl, Integer totalPages) {

  public static MaterialResponse from(CourseMaterial material) {
    return new MaterialResponse(
        material.getId(),
        material.getTitle(),
        material.getStatus(),
        material.getPdfUrl(),
        material.getTotalPages());
  }
}
