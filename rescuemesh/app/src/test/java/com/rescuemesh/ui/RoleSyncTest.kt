package com.rescuemesh.ui

import com.rescuemesh.domain.Role
import com.rescuemesh.net.RoleApplicationView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The role-display fix's pure logic: the strict server-role parse behind
 * the local-profile mirror, and the per-status explanation under each role
 * application (APPROVED must say the role is not active until redemption).
 */
class RoleSyncTest {

    private fun application(
        status: String,
        requestedRole: String = "VOLUNTEER",
        reviewNote: String? = null
    ) = RoleApplicationView(
        id = 1,
        username = "apoorv",
        requestedRole = requestedRole,
        status = status,
        reviewNote = reviewNote
    )

    // ---- roleFromServerName (the mirror's mapping) ----

    @Test
    fun `every known server role name maps to the matching local role`() {
        assertEquals(Role.CITIZEN, roleFromServerName("CITIZEN"))
        assertEquals(Role.VOLUNTEER, roleFromServerName("VOLUNTEER"))
        assertEquals(Role.COORDINATOR, roleFromServerName("COORDINATOR"))
        assertEquals(Role.ADMIN, roleFromServerName("ADMIN"))
    }

    @Test
    fun `unknown or missing server role is a no-op, never a citizen fallback`() {
        // Role.fromName would turn these into CITIZEN and silently downgrade
        // the local profile; the mirror's parse must refuse them instead.
        assertNull(roleFromServerName(null))
        assertNull(roleFromServerName(""))
        assertNull(roleFromServerName("SUPERUSER"))
        assertNull(roleFromServerName("citizen"))
        assertNull(roleFromServerName(" VOLUNTEER"))
    }

    // ---- applicationStatusExplanation ----

    @Test
    fun `pending explains the role has not changed yet`() {
        assertEquals(
            "Waiting for an admin to review — your role has not changed yet.",
            applicationStatusExplanation(application("PENDING"))
        )
    }

    @Test
    fun `approved says not active yet and names the role to activate`() {
        val text = applicationStatusExplanation(
            application("APPROVED", requestedRole = "COORDINATOR")
        )
        assertTrue(text.startsWith("Approved — but NOT active yet."))
        assertTrue("the requested role must be named", text.contains("activate COORDINATOR"))
        assertTrue(text.contains("Access code"))
    }

    @Test
    fun `rejected is plain without a note and carries the note when present`() {
        assertEquals(
            "Rejected by an admin.",
            applicationStatusExplanation(application("REJECTED"))
        )
        assertEquals(
            "Rejected by an admin.",
            applicationStatusExplanation(application("REJECTED", reviewNote = "  "))
        )
        assertEquals(
            "Rejected by an admin. Note: Not in the district team.",
            applicationStatusExplanation(
                application("REJECTED", reviewNote = "Not in the district team.")
            )
        )
    }

    @Test
    fun `unknown status falls back to the raw status text`() {
        assertEquals(
            "Status: ARCHIVED",
            applicationStatusExplanation(application("ARCHIVED"))
        )
    }

    // ---- isApprovedAwaitingActivation (the hint above Access code) ----

    @Test
    fun `approved with a different current role awaits activation`() {
        assertTrue(isApprovedAwaitingActivation(application("APPROVED"), "CITIZEN"))
        assertTrue(isApprovedAwaitingActivation(application("APPROVED"), null))
    }

    @Test
    fun `approved with the role already active does not await activation`() {
        assertFalse(isApprovedAwaitingActivation(application("APPROVED"), "VOLUNTEER"))
    }

    @Test
    fun `non-approved applications never await activation`() {
        assertFalse(isApprovedAwaitingActivation(application("PENDING"), "CITIZEN"))
        assertFalse(isApprovedAwaitingActivation(application("REJECTED"), "CITIZEN"))
    }
}
