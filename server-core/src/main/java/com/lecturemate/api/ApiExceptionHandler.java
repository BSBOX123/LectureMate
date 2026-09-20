package com.lecturemate.api;

import com.lecturemate.service.AuthService.DuplicateEmailException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** API 오류 응답 (RFC 9457 ProblemDetail). */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(DuplicateEmailException.class)
  ProblemDetail handleDuplicateEmail(DuplicateEmailException e) {
    return problem(HttpStatus.CONFLICT, e.getMessage(), "duplicate-email");
  }

  @ExceptionHandler(BadCredentialsException.class)
  ProblemDetail handleBadCredentials(BadCredentialsException e) {
    return problem(HttpStatus.UNAUTHORIZED, e.getMessage(), "invalid-credentials");
  }

  private static ProblemDetail problem(HttpStatus status, String detail, String type) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setType(URI.create("https://lecturemate.com/problems/" + type));
    return problemDetail;
  }
}
