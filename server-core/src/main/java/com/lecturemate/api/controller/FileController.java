package com.lecturemate.api.controller;

import com.lecturemate.service.MaterialService;
import com.lecturemate.service.RecordingService;
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
 * 업로드된 PDF 와 녹음 파일 내려받기 (SPEC §2.1-6, §2.1-11).
 *
 * <p>소유자만 접근할 수 있다. 남의 자료·녹음이면 404 를 반환한다.
 */
@RestController
public class FileController {

  private final MaterialService materialService;
  private final RecordingService recordingService;
  private final StorageService storageService;

  public FileController(
      MaterialService materialService,
      RecordingService recordingService,
      StorageService storageService) {
    this.materialService = materialService;
    this.recordingService = recordingService;
    this.storageService = storageService;
  }

  @GetMapping("/files/pdf/{materialId}.pdf")
  public ResponseEntity<Resource> pdf(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long materialId) {
    materialService.findOwnedById(Long.valueOf(jwt.getSubject()), materialId);
    return serve(storageService.pdfPath(materialId), MediaType.APPLICATION_PDF);
  }

  @GetMapping("/files/audio/{recordingId}.wav")
  public ResponseEntity<Resource> audio(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long recordingId) {
    recordingService.requireOwnedById(Long.valueOf(jwt.getSubject()), recordingId);
    return serve(storageService.audioPath(recordingId), MediaType.parseMediaType("audio/wav"));
  }

  private static ResponseEntity<Resource> serve(Path path, MediaType contentType) {
    if (!Files.isReadable(path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return ResponseEntity.ok().contentType(contentType).body(new FileSystemResource(path));
  }
}
