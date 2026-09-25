package com.lecturemate.api.controller;

import com.lecturemate.api.dto.MaterialResponse;
import com.lecturemate.service.MaterialService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 과목에 속한 PDF 자료 (SPEC §2.1-4 ~ §2.1-6). */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/materials")
@Validated
public class MaterialController {

  private final MaterialService materialService;

  public MaterialController(MaterialService materialService) {
    this.materialService = materialService;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public MaterialResponse upload(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @RequestParam @NotBlank @Size(max = 255) String title,
      @RequestParam("file") MultipartFile file) {
    return materialService.upload(userId(jwt), courseId, title, file);
  }

  @GetMapping
  public List<MaterialResponse> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long courseId) {
    return materialService.findAll(userId(jwt), courseId);
  }

  @DeleteMapping("/{materialId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @PathVariable Long materialId) {
    materialService.delete(userId(jwt), courseId, materialId);
  }

  @PostMapping("/{materialId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public MaterialResponse retry(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long courseId,
      @PathVariable Long materialId) {
    return materialService.retry(userId(jwt), courseId, materialId);
  }

  private static Long userId(Jwt jwt) {
    return Long.valueOf(jwt.getSubject());
  }
}
