package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.SOSRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SOSRecordRepository extends JpaRepository<SOSRecord, Long> {

    /** Newest first, capped — the /records/* read endpoints (§5). */
    List<SOSRecord> findTop200ByOrderByCreatedAtDesc();

    /** The SOS projected from one envelope, for the dashboard resolve action. */
    Optional<SOSRecord> findByMessageId(String messageId);
}
