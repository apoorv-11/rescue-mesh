package com.rescuemesh.server.repository;

import com.rescuemesh.server.domain.ApplicationStatus;
import com.rescuemesh.server.domain.RoleApplication;
import com.rescuemesh.server.domain.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleApplicationRepository extends JpaRepository<RoleApplication, Long> {

    List<RoleApplication> findByUserOrderByCreatedAtDesc(User user);

    boolean existsByUserAndStatus(User user, ApplicationStatus status);

    /** Admin review queue, oldest first. */
    List<RoleApplication> findByStatusOrderByCreatedAtAsc(ApplicationStatus status);
}
