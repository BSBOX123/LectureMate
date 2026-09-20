package com.lecturemate.service;

import com.lecturemate.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 업로드 파일을 로컬 디스크에 저장한다 (SPEC §5.1 {@code STORAGE_LOCAL_PATH}). */
@Service
public class StorageService {

  private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46}; // %PDF

  private final Path root;

  public StorageService(StorageProperties properties) {
    this.root = properties.localPath();
  }

  /** {storage}/pdf/{lectureId}.pdf 로 저장하고 절대 경로를 돌려준다. */
  public Path storePdf(Long lectureId, MultipartFile file) {
    requirePdf(file);
    Path target = pdfPath(lectureId);
    try {
      Files.createDirectories(target.getParent());
      try (InputStream in = file.getInputStream()) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new StorageException("PDF 파일을 저장하지 못했습니다.", e);
    }
    return target;
  }

  public Path pdfPath(Long lectureId) {
    return root.resolve("pdf").resolve(lectureId + ".pdf").toAbsolutePath().normalize();
  }

  /** 확장자만으로는 믿을 수 없으므로 매직 넘버(%PDF)까지 확인한다. */
  private void requirePdf(MultipartFile file) {
    if (file.isEmpty()) {
      throw new InvalidPdfException("빈 파일입니다.");
    }
    byte[] header = new byte[PDF_MAGIC.length];
    try (InputStream in = file.getInputStream()) {
      if (in.readNBytes(header, 0, header.length) < header.length) {
        throw new InvalidPdfException("PDF 파일이 아닙니다.");
      }
    } catch (IOException e) {
      throw new StorageException("업로드 파일을 읽지 못했습니다.", e);
    }
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (header[i] != PDF_MAGIC[i]) {
        throw new InvalidPdfException("PDF 파일이 아닙니다.");
      }
    }
  }

  /** PDF 가 아닌 파일 업로드 (400). */
  public static class InvalidPdfException extends RuntimeException {
    public InvalidPdfException(String message) {
      super(message);
    }
  }

  /** 저장소 입출력 실패 (500). */
  public static class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
