package com.lecturemate.repository;

import com.lecturemate.domain.entity.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /** 로그아웃 시 해당 사용자의 살아있는 토큰을 모두 폐기한다. */
  @Modifying(clearAutomatically = true)
  @Query(
      "update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
  int revokeAllByUserId(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
