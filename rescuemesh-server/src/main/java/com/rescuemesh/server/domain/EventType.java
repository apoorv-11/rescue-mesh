package com.rescuemesh.server.domain;

/**
 * Closed list of envelope event types (fixed contract from the build plan —
 * never rename or extend without changing the shared contract).
 */
public enum EventType {
    SOS,
    SAFETY_CHECKIN,
    MISSING_PERSON,
    PERSON_FOUND_UPDATE,
    ANNOUNCEMENT,
    RESOURCE_UPDATE
}
