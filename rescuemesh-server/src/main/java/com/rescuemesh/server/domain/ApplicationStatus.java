package com.rescuemesh.server.domain;

/** Lifecycle of a {@link RoleApplication}: PENDING → APPROVED or REJECTED. */
public enum ApplicationStatus {
    PENDING,
    APPROVED,
    REJECTED
}
