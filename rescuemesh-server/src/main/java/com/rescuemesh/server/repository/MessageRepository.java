package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.EventType;
import com.rescuemesh.server.domain.Message;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByMessageId(String messageId);

    /** All stored envelopes of one type — the V3 case link scans found updates. */
    List<Message> findByEventType(EventType eventType);

    boolean existsByMessageId(String messageId);

    /** Newest first, capped — the /records/* read endpoints (§5). */
    List<Message> findTop200ByOrderByCreatedAtDesc();
}
