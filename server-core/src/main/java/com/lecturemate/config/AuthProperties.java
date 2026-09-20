package com.lecturemate.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인증 설정 (application.yml {@code lecturemate.auth}).
 *
 * @param jwtSecret HS256 서명 키 (SPEC §5.1 {@code JWT_SECRET}, 최소 32바이트)
 * @param accessTokenTtl Access Token 유효기간 (SPEC §2.1 인증 규칙: 30분)
 * @param refreshTokenTtl Refresh Token 유효기간 (14일)
 * @param refreshCookieSecure Refresh Token 쿠키에 Secure 속성을 붙일지 (HTTPS 환경에서 true)
 */
@ConfigurationProperties(prefix = "lecturemate.auth")
public record AuthProperties(
    String jwtSecret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    boolean refreshCookieSecure) {}
