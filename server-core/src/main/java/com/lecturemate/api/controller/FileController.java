package com.lecturemate.api.controller;

import com.lecturemate.service.LectureService;
import com.lecturemate.service.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 업로드된 PDF 내려받기 (SPEC §2.1-1 응답의 {@code pdfUrl}).
 *
 * <p>소유자만 접근할 수 있다. 다른 사용자의 강의면 404 를 반환한다.
 */
@RestController
public class FileController {

  private final LectureService lectureService;
  private final StorageService storageService;

  public FileController(LectureService lectureService, StorageService storageService) {
    this.lectureService = lectureService;
    this.storageService = storageService;
  }

  @GetMapping("/files/pdf/{lectureId}.pdf")
  public ResponseEntity<Resource> pdf(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long lectureId) {
    lectureService.findOwned(Long.valueOf(jwt.getSubject()), lectureId);

    Path path = storageService.pdfPath(lectureId);
    if (!Files.isReadable(path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .body(new FileSystemResource(path));
  }
}
