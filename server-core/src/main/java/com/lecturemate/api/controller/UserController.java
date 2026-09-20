package com.lecturemate.api.controller;

import com.lecturemate.api.dto.UserResponse;
import com.lecturemate.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 내 정보 조회 (SPEC §2.1-10). */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final AuthService authService;

  public UserController(AuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/me")
  public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
    return authService.findById(Long.valueOf(jwt.getSubject()));
  }
}
