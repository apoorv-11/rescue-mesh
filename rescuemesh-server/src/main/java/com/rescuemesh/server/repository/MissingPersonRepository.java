package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.MissingPerson;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissingPersonRepository extends JpaRepository<MissingPerson, Long> {

    /** Newest first, capped — the /records/* read endpoints (§5). */
    List<MissingPerson> findTop200ByOrderByCreatedAtDesc();

    /** The case key: the MISSING_PERSON envelope's messageId (V3 case link). */
    Optional<MissingPerson> findByMessageId(String messageId);
}
