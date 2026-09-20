package com.lecturemate.security;

import com.lecturemate.config.InternalApiProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FastAPI -> Spring Boot Webhook({@code /internal/v1/**}) 인증 필터 (SPEC §2.2).
 *
 * <p>{@code X-Internal-Secret} 헤더를 공유 시크릿과 비교한다. 타이밍 공격을 피하려고 상수 시간 비교를 쓴다.
 */
public class InternalSecretFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Secret";

  private final byte[] expected;

  public InternalSecretFilter(InternalApiProperties properties) {
    this.expected = properties.secret().getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    if (provided == null
        || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
      response.sendError(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    filterChain.doFilter(request, response);
  }
}
