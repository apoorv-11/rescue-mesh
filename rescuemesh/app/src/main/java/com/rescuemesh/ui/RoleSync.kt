package com.rescuemesh.ui

import com.rescuemesh.domain.Role
import com.rescuemesh.net.RoleApplicationView

/**
 * Pure helpers behind the role-display fix — no Android, no Compose, so
 * they are JVM-unit-testable (RoleSyncTest).
 *
 * The problem they solve: the app shows the user's role in two places —
 * Home (the local Room profile, set at onboarding) and the Account screen
 * (the server session from GET /me). After a role code was redeemed, only
 * the server side changed and Home kept showing the onboarding role. The
 * fix mirrors every server-confirmed role into the local profile; these
 * functions are the mapping pieces of that mirror.
 */

/**
 * Strict parse of a role string reported by the server (/me). Returns null
 * — which the caller treats as "do not touch the local role" — for null or
 * anything this build does not know.
 *
 * Deliberately NOT [Role.fromName]: that one falls back to CITIZEN for
 * unknown values, which here would silently rewrite (downgrade) the local
 * profile role whenever the server said something unexpected. An unknown
 * server value must be a no-op, never a guess.
 */
fun roleFromServerName(name: String?): Role? =
    name?.let { candidate -> Role.entries.firstOrNull { it.name == candidate } }

/**
 * One-line explanation shown under a role application, by server status.
 *
 * The APPROVED wording is the important one: approval grants the role but
 * does NOT activate it. Activation happens only when the user redeems the
 * one-time access code, so the text says exactly that instead of letting
 * "APPROVED" look like the role is already live.
 */
fun applicationStatusExplanation(application: RoleApplicationView): String =
    when (application.status) {
        "PENDING" ->
            "Waiting for an admin to review — your role has not changed yet."
        "APPROVED" ->
            "Approved — but NOT active yet. Enter the access code from the admin " +
                "(by email, or in the server log in dev mode) in the Access code " +
                "section below to activate ${application.requestedRole}."
        "REJECTED" -> {
            val note = application.reviewNote?.takeIf { it.isNotBlank() }
            if (note != null) "Rejected by an admin. Note: $note" else "Rejected by an admin."
        }
        else -> "Status: ${application.status}"
    }

/**
 * True when [application] is APPROVED but the session's current role has
 * not caught up with it — the user still has to redeem the access code.
 * Drives the "you have an approved application" hint above the Access code
 * section.
 */
fun isApprovedAwaitingActivation(
    application: RoleApplicationView,
    currentRole: String?
): Boolean =
    application.status == "APPROVED" && application.requestedRole != currentRole
