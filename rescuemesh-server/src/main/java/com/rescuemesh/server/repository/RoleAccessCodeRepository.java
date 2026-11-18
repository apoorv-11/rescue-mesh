package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.RoleAccessCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleAccessCodeRepository extends JpaRepository<RoleAccessCode, Long> {

    /** Redemption looks a code up by the SHA-256 hash of what the user typed. */
    Optional<RoleAccessCode> findByCodeHash(String codeHash);
}
