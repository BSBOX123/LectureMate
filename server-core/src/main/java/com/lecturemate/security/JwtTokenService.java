package com.lecturemate.security;

import com.lecturemate.config.AuthProperties;
import com.lecturemate.domain.entity.User;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

/** Access Token(HS256 JWT) 발급. sub 에 userId 를 담는다 (SPEC §2.1 인증 규칙). */
@Service
public class JwtTokenService {

  private static final String ISSUER = "lecturemate";

  private final JwtEncoder jwtEncoder;
  private final AuthProperties authProperties;

  public JwtTokenService(JwtEncoder jwtEncoder, AuthProperties authProperties) {
    this.jwtEncoder = jwtEncoder;
    this.authProperties = authProperties;
  }

  public String issueAccessToken(User user, Instant issuedAt) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(String.valueOf(user.getId()))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(authProperties.accessTokenTtl()))
            .claim("email", user.getEmail())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  public long accessTokenExpiresInSeconds() {
    return authProperties.accessTokenTtl().toSeconds();
  }
}
