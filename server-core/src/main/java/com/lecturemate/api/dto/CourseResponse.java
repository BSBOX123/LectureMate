package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.Course;

/** 과목 메타데이터 응답 (SPEC §2.1-1, §2.1-2). */
public record CourseResponse(Long courseId, String title) {

  public static CourseResponse from(Course course) {
    return new CourseResponse(course.getId(), course.getTitle());
  }
}
