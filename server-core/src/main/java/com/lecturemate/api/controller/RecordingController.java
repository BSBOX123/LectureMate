package com.lecturemate.api.controller;

import com.lecturemate.api.dto.RecordingResponse;
import com.lecturemate.service.RecordingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 과목에 속한 녹음 (SPEC §2.1-7 ~ §2.1-10). */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/recordings")
public class RecordingController {

  private final RecordingService recordingService;

  public RecordingController(RecordingService recordingService) {
    this.recordingService = recordingService;
  }

  /** SPEC §2.1-7 요청 본문. */
  public record RecordingRequest(@NotBlank @Size(max = 255) String title) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RecordingResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @Valid @RequestBody RecordingRequest request) {
    return recordingService.create(userId(jwt), courseId, request.title());
  }

  @GetMapping
  public List<RecordingResponse> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId) {
    return recordingService.findAll(userId(jwt), courseId);
  }

  @DeleteMapping("/{recordingId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @PathVariable Long recordingId) {
    recordingService.delete(userId(jwt), courseId, recordingId);
  }

  /**
   * 전사 시작 (SPEC §2.1-10). 실패한 녹음의 재시도도 같은 경로다.
   *
   * <p>녹음 종료 시 자동으로 시작하지 않는다. 전사는 음성 1분당 약 34초가 걸려 수업 직후 바로
   * 노트북을 덮는 상황에서는 끝까지 돌 수 없기 때문이다.
   */
  @PostMapping("/{recordingId}/transcribe")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RecordingResponse transcribe(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @PathVariable Long recordingId) {
    return recordingService.transcribe(userId(jwt), courseId, recordingId);
  }

  private static Long userId(Jwt jwt) {
    return Long.valueOf(jwt.getSubject());
  }
}
