package com.lecturemate.api.dto;

/** SPEC §2.1-7, §2.1-8 로그인 / 재발급 응답. Refresh Token 은 본문이 아니라 httpOnly 쿠키로 나간다. */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

  public static TokenResponse bearer(String accessToken, long expiresIn) {
    return new TokenResponse(accessToken, "Bearer", expiresIn);
  }
}
