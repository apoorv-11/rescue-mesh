package com.rescuemesh.ui

import com.rescuemesh.domain.Role

/**
 * What onboarding fixes at the profile-creation boundary (user
 * decision, 14 Nov): there is NO role picker any more — every profile
 * is created as a CITIZEN, and higher roles arrive later through the
 * Account tab (application → admin review → access code). The server
 * enforces the same rule for accounts, and the server-role mirror can
 * still raise the local role afterwards; onboarding simply never asks.
 *
 * Pure on purpose (RoleSync precedent) so the boundary is
 * JVM-unit-tested: the Compose screen saves exactly
 * `saveProfile(displayNameOrDefault(name), DEFAULT_ROLE)`.
 */
object OnboardingPolicy {

    /** The role every onboarding profile is saved with. Always CITIZEN. */
    val DEFAULT_ROLE: Role = Role.CITIZEN

    /** The one info line shown under the name field, saying where roles come from. */
    const val ROLE_INFO_LINE: String =
        "Everyone starts as a CITIZEN. Volunteer, Coordinator and Admin roles are " +
            "granted later from the Account tab, after an admin reviews your application."

    /** The saved name when the field is left blank (unchanged from the first UI). */
    fun displayNameOrDefault(input: String): String = input.ifBlank { "Anonymous" }
}
