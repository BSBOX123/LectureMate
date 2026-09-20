package com.lecturemate.config;

import com.lecturemate.security.InternalSecretFilter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 인증/인가 설정 (SPEC §2.1 인증 규칙, §2.2 내부 API 인증).
 *
 * <ul>
 *   <li>{@code /internal/v1/**}: 공유 시크릿 헤더로만 인증 (JWT 불필요)
 *   <li>{@code /api/v1/auth/**}: 비로그인 허용
 *   <li>그 외: Access Token 필요
 * </ul>
 */
@Configuration
public class SecurityConfig {

  private final AuthProperties authProperties;
  private final String webClientOrigin;

  public SecurityConfig(
      AuthProperties authProperties,
      @org.springframework.beans.factory.annotation.Value("${lecturemate.web-client-origin}")
          String webClientOrigin) {
    this.authProperties = authProperties;
    this.webClientOrigin = webClientOrigin;
  }

  private SecretKey secretKey() {
    return new SecretKeySpec(
        authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
  }

  @Bean
  JwtDecoder jwtDecoder() {
    return NimbusJwtDecoder.withSecretKey(secretKey()).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(webClientOrigin));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    // Refresh Token 쿠키를 주고받기 위해 필요
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    // PDF 다운로드(/files/pdf/{id}.pdf)도 Authorization 헤더를 쓰므로 preflight 가 발생한다
    source.registerCorsConfiguration("/files/**", configuration);
    return source;
  }

  /** FastAPI Webhook 전용 체인. JWT 를 적용하지 않고 공유 시크릿만 검사한다. */
  @Bean
  @org.springframework.core.annotation.Order(1)
  SecurityFilterChain internalFilterChain(
      HttpSecurity http, InternalApiProperties internalApiProperties) throws Exception {
    return http.securityMatcher("/internal/**")
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(
            new InternalSecretFilter(internalApiProperties),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .build();
  }

  @Bean
  SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    // 404 등 오류 응답은 /error 로 forward 된다. 막으면 모든 오류가 401 로 바뀐다.
                    .requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .build();
  }
}
