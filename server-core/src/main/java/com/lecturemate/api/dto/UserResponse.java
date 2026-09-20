package com.lecturemate.api.dto;

import com.lecturemate.domain.entity.User;

/** SPEC §2.1-6 회원가입 응답 및 §2.1-10 내 정보 조회 응답. */
public record UserResponse(Long userId, String email, String name) {

  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getEmail(), user.getName());
  }
}
