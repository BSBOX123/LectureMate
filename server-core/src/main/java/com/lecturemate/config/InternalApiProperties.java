package com.lecturemate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 API 설정 (application.yml {@code lecturemate.internal}).
 *
 * @param secret FastAPI Webhook 공유 시크릿 (SPEC §2.2, {@code X-Internal-Secret} 헤더)
 */
@ConfigurationProperties(prefix = "lecturemate.internal")
public record InternalApiProperties(String secret) {}
