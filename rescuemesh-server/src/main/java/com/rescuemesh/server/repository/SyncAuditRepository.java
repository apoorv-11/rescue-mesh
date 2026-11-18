package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.SyncAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncAuditRepository extends JpaRepository<SyncAudit, Long> {

    /** Newest first, capped — the /records/* read endpoints (§5). */
    List<SyncAudit> findTop200ByOrderByCreatedAtDesc();
}
