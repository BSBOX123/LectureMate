package com.lecturemate.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FastAPI AI 엔진 통신 설정 (application.yml {@code lecturemate.fastapi}).
 *
 * @param baseUrl FastAPI 기본 URL (SPEC §5.1 {@code FASTAPI_ENGINE_URL}). WebSocket 주소는 스킴만 바꿔 사용
 * @param connectTimeout HTTP 연결 타임아웃
 * @param readTimeout HTTP 응답 대기 타임아웃 (PDF 파싱/임베딩 동기 호출 고려)
 */
@ConfigurationProperties(prefix = "lecturemate.fastapi")
public record FastApiProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {}
