package com.rescuemesh.ui

import com.rescuemesh.data.local.ProfileDao
import com.rescuemesh.data.local.ProfileEntity
import com.rescuemesh.data.repository.ProfileRepository
import com.rescuemesh.domain.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The onboarding boundary (user decision, 14 Nov): no role picker —
 * the profile is created with [OnboardingPolicy.DEFAULT_ROLE], which
 * must be CITIZEN, and the repository stores exactly that. The Compose
 * onboarding screen saves through this same pair of policy values.
 */
class OnboardingPolicyTest {

    @Test
    fun `the onboarding default role is CITIZEN`() {
        assertEquals(Role.CITIZEN, OnboardingPolicy.DEFAULT_ROLE)
    }

    @Test
    fun `blank names fall back to Anonymous, real names are kept`() {
        assertEquals("Anonymous", OnboardingPolicy.displayNameOrDefault(""))
        assertEquals("Anonymous", OnboardingPolicy.displayNameOrDefault("   "))
        assertEquals("Asha", OnboardingPolicy.displayNameOrDefault("Asha"))
    }

    @Test
    fun `a profile created the onboarding way is stored as CITIZEN`() = runBlocking {
        val dao = FakeProfileDao()
        val repository = ProfileRepository(dao) { "device-id-1" }
        val saved = repository.saveProfile(
            OnboardingPolicy.displayNameOrDefault("  "),
            OnboardingPolicy.DEFAULT_ROLE
        )
        assertEquals("CITIZEN", saved.role)
        assertEquals("Anonymous", saved.displayName)
        assertEquals("CITIZEN", dao.stored?.role)
    }

    /** Minimal in-memory [ProfileDao] for the boundary test. */
    private class FakeProfileDao : ProfileDao {
        var stored: ProfileEntity? = null
        private val flow = MutableStateFlow<ProfileEntity?>(null)

        override fun observeProfile(): Flow<ProfileEntity?> = flow

        override suspend fun getProfile(): ProfileEntity? = stored

        override suspend fun upsert(profile: ProfileEntity) {
            stored = profile
            flow.value = profile
        }

        override suspend fun updateRole(role: String) {
            stored = stored?.copy(role = role)
        }
    }
}
