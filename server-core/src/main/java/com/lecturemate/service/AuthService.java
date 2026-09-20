package com.lecturemate.service;

import com.lecturemate.api.dto.SignupRequest;
import com.lecturemate.api.dto.TokenResponse;
import com.lecturemate.api.dto.UserResponse;
import com.lecturemate.config.AuthProperties;
import com.lecturemate.domain.entity.RefreshToken;
import com.lecturemate.domain.entity.User;
import com.lecturemate.repository.RefreshTokenRepository;
import com.lecturemate.repository.UserRepository;
import com.lecturemate.security.JwtTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입, 로그인, Access Token 재발급, 로그아웃 (SPEC §2.1-6 ~ §2.1-9). */
@Service
public class AuthService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;
  private final AuthProperties authProperties;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      AuthProperties authProperties) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
    this.authProperties = authProperties;
  }

  /** 발급된 Access Token 과, 쿠키로 내려보낼 Refresh Token 원문. */
  public record IssuedTokens(TokenResponse token, String refreshToken, Duration refreshTokenTtl) {}

  @Transactional
  public UserResponse signup(SignupRequest request) {
    if (userRepository.existsByEmail(request.email())) {
      throw new DuplicateEmailException(request.email());
    }
    User user =
        userRepository.save(
            new User(
                request.email(), passwordEncoder.encode(request.password()), request.name()));
    return UserResponse.from(user);
  }

  @Transactional
  public IssuedTokens login(String email, String rawPassword) {
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다."));
    if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
      throw new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
    return issueTokens(user);
  }

  /** Refresh Token 회전: 기존 토큰을 폐기하고 새 Access/Refresh 를 발급한다. */
  @Transactional
  public IssuedTokens refresh(String rawRefreshToken) {
    OffsetDateTime now = OffsetDateTime.now();
    RefreshToken stored =
        refreshTokenRepository
            .findByTokenHash(hash(rawRefreshToken))
            .orElseThrow(() -> new BadCredentialsException("유효하지 않은 Refresh Token 입니다."));
    if (!stored.isUsable(now)) {
      throw new BadCredentialsException("만료되었거나 이미 사용할 수 없는 Refresh Token 입니다.");
    }
    stored.revoke(now);
    return issueTokens(stored.getUser());
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null) {
      return;
    }
    refreshTokenRepository
        .findByTokenHash(hash(rawRefreshToken))
        .ifPresent(
            token ->
                refreshTokenRepository.revokeAllByUserId(
                    token.getUser().getId(), OffsetDateTime.now()));
  }

  @Transactional(readOnly = true)
  public UserResponse findById(Long userId) {
    return userRepository
        .findById(userId)
        .map(UserResponse::from)
        .orElseThrow(() -> new BadCredentialsException("존재하지 않는 사용자입니다."));
  }

  private IssuedTokens issueTokens(User user) {
    Instant now = Instant.now();
    String accessToken = jwtTokenService.issueAccessToken(user, now);
    String refreshToken = randomToken();
    Duration ttl = authProperties.refreshTokenTtl();
    refreshTokenRepository.save(
        new RefreshToken(user, hash(refreshToken), OffsetDateTime.now().plus(ttl)));
    return new IssuedTokens(
        TokenResponse.bearer(accessToken, jwtTokenService.accessTokenExpiresInSeconds()),
        refreshToken,
        ttl);
  }

  private static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** DB 에는 원문 대신 해시를 저장한다. */
  private static String hash(String rawToken) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
    }
  }

  /** 이미 가입된 이메일 (SPEC §2.1-6: 409 Conflict). */
  public static class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
      super("이미 사용 중인 이메일입니다: " + email);
    }
  }
}
