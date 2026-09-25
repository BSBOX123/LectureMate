package com.lecturemate.api.controller;

import com.lecturemate.api.dto.CourseResponse;
import com.lecturemate.service.CourseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 과목(폴더) 생성·조회·삭제 (SPEC §2.1-1 ~ §2.1-3). */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

  private final CourseService courseService;

  public CourseController(CourseService courseService) {
    this.courseService = courseService;
  }

  /** SPEC §2.1-1 요청 본문. */
  public record CourseRequest(@NotBlank @Size(max = 255) String title) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CourseResponse create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CourseRequest request) {
    return courseService.create(userId(jwt), request.title());
  }

  @GetMapping
  public List<CourseResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return courseService.findAllOwned(userId(jwt));
  }

  @GetMapping("/{courseId}")
  public CourseResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId) {
    return courseService.findOwned(userId(jwt), courseId);
  }

  @PatchMapping("/{courseId}")
  public CourseResponse rename(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @Valid @RequestBody CourseRequest request) {
    return courseService.rename(userId(jwt), courseId, request.title());
  }

  @DeleteMapping("/{courseId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId) {
    courseService.delete(userId(jwt), courseId);
  }

  private static Long userId(Jwt jwt) {
    return Long.valueOf(jwt.getSubject());
  }
}
