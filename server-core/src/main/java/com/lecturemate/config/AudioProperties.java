package com.lecturemate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실시간 오디오 설정 (application.yml {@code lecturemate.audio}).
 *
 * @param sampleRate 클라이언트가 보내는 PCM 샘플레이트 (SPEC §2.1-2: 16kHz 모노 16bit LE)
 */
@ConfigurationProperties(prefix = "lecturemate.audio")
public record AudioProperties(int sampleRate) {}
