package com.lecturemate.service;

import com.lecturemate.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

  /** 강의에 딸린 파일(PDF, 오디오, 녹음 임시본)을 모두 지운다 (SPEC §2.1-13). */
  public void deleteLectureFiles(Long lectureId) {
    for (Path path : new Path[] {pdfPath(lectureId), audioPath(lectureId), pcmPath(lectureId)}) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException e) {
        // 파일이 남아도 강의 삭제 자체는 진행한다
        throw new StorageException("파일을 삭제하지 못했습니다: " + path, e);
      }
    }
  }

  /** 녹음 중 PCM 을 이어붙일 임시 파일 (16bit LE, 모노). */
  public OutputStream openPcmSink(Long lectureId) {
    Path target = pcmPath(lectureId);
    try {
      Files.createDirectories(target.getParent());
      return Files.newOutputStream(target);
    } catch (IOException e) {
      throw new StorageException("녹음 파일을 열지 못했습니다.", e);
    }
  }

  /**
   * 누적된 PCM 에 WAV 헤더를 붙여 {storage}/audio/{lectureId}.wav 로 만든다 (SPEC §2.2-2 의 audio_path).
   *
   * @return 만들어진 WAV 경로
   */
  public Path finalizeWav(Long lectureId, int sampleRate) {
    Path pcm = pcmPath(lectureId);
    Path wav = audioPath(lectureId);
    try {
      long dataSize = Files.size(pcm);
      try (OutputStream out = Files.newOutputStream(wav)) {
        out.write(wavHeader(dataSize, sampleRate));
        Files.copy(pcm, out);
      }
      Files.deleteIfExists(pcm);
      return wav;
    } catch (IOException e) {
      throw new StorageException("WAV 파일을 만들지 못했습니다.", e);
    }
  }

  private Path pcmPath(Long lectureId) {
    return root.resolve("audio").resolve(lectureId + ".pcm").toAbsolutePath().normalize();
  }

  public Path audioPath(Long lectureId) {
    return root.resolve("audio").resolve(lectureId + ".wav").toAbsolutePath().normalize();
  }

  /** 44바이트 표준 WAV(PCM 16bit 모노) 헤더. */
  private static byte[] wavHeader(long dataSize, int sampleRate) {
    int channels = 1;
    int bitsPerSample = 16;
    int byteRate = sampleRate * channels * bitsPerSample / 8;
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    header.putInt((int) (36 + dataSize));
    header.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    header.putInt(16); // fmt 청크 크기
    header.putShort((short) 1); // PCM
    header.putShort((short) channels);
    header.putInt(sampleRate);
    header.putInt(byteRate);
    header.putShort((short) (channels * bitsPerSample / 8)); // block align
    header.putShort((short) bitsPerSample);
    header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    header.putInt((int) dataSize);
    return header.array();
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
