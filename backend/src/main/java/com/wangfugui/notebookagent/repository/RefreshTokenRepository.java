package com.wangfugui.notebookagent.repository;

import com.wangfugui.notebookagent.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Transactional
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.fingerprint = :fingerprint and r.revokedAt is null")
    Optional<RefreshToken> lockActiveByFingerprint(@Param("fingerprint") String fingerprint);
    Optional<RefreshToken> findByFingerprintAndRevokedAtIsNull(String fingerprint);
}
