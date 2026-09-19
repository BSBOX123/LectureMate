package com.lecturemate.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 파일 스토리지 설정 (application.yml {@code lecturemate.storage}).
 *
 * @param localPath PDF/오디오 파일 저장 루트 (SPEC §5.1 {@code STORAGE_LOCAL_PATH})
 */
@ConfigurationProperties(prefix = "lecturemate.storage")
public record StorageProperties(Path localPath) {}
