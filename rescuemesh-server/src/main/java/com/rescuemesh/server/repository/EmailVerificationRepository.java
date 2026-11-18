package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.EmailVerification;
import com.rescuemesh.server.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    /** The newest still-usable challenge for a user (the one a resend leaves valid). */
    Optional<EmailVerification> findFirstByUserAndConsumedFalseOrderByCreatedAtDesc(User user);

    /** All outstanding challenges for a user, so a resend can invalidate them. */
    List<EmailVerification> findByUserAndConsumedFalse(User user);
}
