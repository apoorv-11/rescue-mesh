package com.rescuemesh.server.web;

import com.rescuemesh.server.domain.RoleApplication;
import java.time.Instant;

/** JSON view of a {@link RoleApplication}, shared by the user and admin endpoints. */
public record RoleApplicationView(
        Long id,
        String username,
        String email,
        String requestedRole,
        String organization,
        String reason,
        String status,
        String reviewNote,
        String reviewedBy,
        Instant createdAt,
        Instant reviewedAt) {

    public static RoleApplicationView from(RoleApplication application) {
        return new RoleApplicationView(
                application.getId(),
                application.getUser().getUsername(),
                application.getUser().getEmail(),
                application.getRequestedRole().name(),
                application.getOrganization(),
                application.getReason(),
                application.getStatus().name(),
                application.getReviewNote(),
                application.getReviewedBy(),
                application.getCreatedAt(),
                application.getReviewedAt());
    }
}
