package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.Announcement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Newest first, capped — the /records/* read endpoints (§5). */
    List<Announcement> findTop200ByOrderByCreatedAtDesc();
}
