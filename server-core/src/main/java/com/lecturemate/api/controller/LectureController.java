package com.lecturemate.api.controller;

import com.lecturemate.api.dto.LectureResponse;
import com.lecturemate.api.dto.RecordingFinishResponse;
import com.lecturemate.service.LectureService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 강의 생성, PDF 업로드, 메타데이터 조회 (SPEC §2.1-1, §4.1). */
@RestController
@RequestMapping("/api/v1/lectures")
@Validated
public class LectureController {

  private final LectureService lectureService;

  public LectureController(LectureService lectureService) {
    this.lectureService = lectureService;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public LectureResponse create(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam @NotBlank @Size(max = 255) String title,
      @RequestParam("file") MultipartFile file) {
    return lectureService.create(userId(jwt), title, file);
  }

  /** 녹음 종료 및 정밀 분석 트리거 (SPEC §2.1-3). */
  @PostMapping("/{lectureId}/recording/finish")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public RecordingFinishResponse finishRecording(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long lectureId) {
    LectureResponse lecture = lectureService.finishRecording(userId(jwt), lectureId);
    return new RecordingFinishResponse(
        lecture.lectureId(),
        lecture.status(),
        "강의 종료 후 정밀 전사 및 슬라이드 필기 매칭 분석이 시작되었습니다.");
  }

  @GetMapping("/{lectureId}")
  public LectureResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long lectureId) {
    return lectureService.findOwned(userId(jwt), lectureId);
  }

  @GetMapping
  public List<LectureResponse> list(@AuthenticationPrincipal Jwt jwt) {
    return lectureService.findAllOwned(userId(jwt));
  }

  private static Long userId(Jwt jwt) {
    return Long.valueOf(jwt.getSubject());
  }
}
