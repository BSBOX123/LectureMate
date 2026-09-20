package com.lecturemate.api.controller;

import com.lecturemate.api.dto.LoginRequest;
import com.lecturemate.api.dto.SignupRequest;
import com.lecturemate.api.dto.TokenResponse;
import com.lecturemate.api.dto.UserResponse;
import com.lecturemate.config.AuthProperties;
import com.lecturemate.service.AuthService;
import com.lecturemate.service.AuthService.IssuedTokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회원가입, 로그인, 재발급, 로그아웃 (SPEC §2.1-6 ~ §2.1-9). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  static final String REFRESH_COOKIE = "refreshToken";
  private static final String COOKIE_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final AuthProperties authProperties;

  public AuthController(AuthService authService, AuthProperties authProperties) {
    this.authService = authService;
    this.authProperties = authProperties;
  }

  @PostMapping("/signup")
  public ResponseEntity<UserResponse> signup(@Valid @RequestBody SignupRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    IssuedTokens issued = authService.login(request.email(), request.password());
    return tokenResponse(issued);
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken) {
    if (refreshToken == null) {
      throw new BadCredentialsException("Refresh Token 쿠키가 없습니다.");
    }
    return tokenResponse(authService.refresh(refreshToken));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(value = REFRESH_COOKIE, required = false) String refreshToken) {
    authService.logout(refreshToken);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
        .build();
  }

  private ResponseEntity<TokenResponse> tokenResponse(IssuedTokens issued) {
    ResponseCookie cookie =
        refreshCookie(issued.refreshToken(), issued.refreshTokenTtl().toSeconds());
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(issued.token());
  }

  private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(REFRESH_COOKIE, value)
        .httpOnly(true)
        .secure(authProperties.refreshCookieSecure())
        .sameSite("Lax")
        .path(COOKIE_PATH)
        .maxAge(maxAgeSeconds)
        .build();
  }
}
