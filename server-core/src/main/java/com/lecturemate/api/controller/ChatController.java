package com.lecturemate.api.controller;

import com.lecturemate.client.FastApiClient;
import com.lecturemate.service.CourseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 과목 단위 RAG 질의응답 (SPEC §2.1-11).
 *
 * <p>한 과목의 모든 자료와 녹음을 함께 검색한다. FastAPI 가 보내는 SSE 를 그대로 클라이언트로 중계한다.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class ChatController {

  private static final int TOP_K = 5;

  private final CourseService courseService;
  private final FastApiClient fastApiClient;

  public ChatController(CourseService courseService, FastApiClient fastApiClient) {
    this.courseService = courseService;
    this.fastApiClient = fastApiClient;
  }

  /** SPEC §2.1-11 요청 본문. */
  public record ChatRequest(@NotBlank @Size(max = 2000) String question) {}

  // SSE 기본 인코딩은 UTF-8 이지만, 한국어가 깨지지 않도록 charset 을 명시한다
  @PostMapping(
      value = "/{courseId}/chat",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
  public StreamingResponseBody chat(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @Valid @RequestBody ChatRequest request) {
    // 스트리밍을 시작하기 전에 소유자를 확인한다 (아니면 404)
    courseService.requireOwned(Long.valueOf(jwt.getSubject()), courseId);
    return out -> fastApiClient.streamRagQuery(courseId, request.question(), TOP_K, out);
  }
}
