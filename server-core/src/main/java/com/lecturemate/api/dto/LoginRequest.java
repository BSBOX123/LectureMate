package com.lecturemate.api.dto;

import jakarta.validation.constraints.NotBlank;

/** SPEC §2.1-7 로그인 요청. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
