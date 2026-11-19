package com.rescuemesh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prepare hub (Prompt 10): the bundled pack is exactly 9 guides on
 * one template, every guide sourced, search / hazard / Saved filtering
 * is pure, and record states translate to plain words. No Android, no
 * network — the gate is "airplane mode opens all 9", which this pins
 * at the content level (the list is bundled, never fetched).
 */
class PrepareGuidesTest {

    @Test
    fun `exactly nine guides with unique ids`() {
        assertEquals(9, PrepareGuides.ALL.size)
        assertEquals(9, PrepareGuides.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun `every guide follows the six-section template with a source`() {
        val template = listOf(
            "What it is", "Before", "During", "After", "Do NOT", "When to send SOS"
        )
        for (guide in PrepareGuides.ALL) {
            assertEquals(guide.id, template, guide.sections.map { it.title })
            assertTrue(guide.id, guide.summary.length <= 300)
            assertTrue(guide.id, guide.source.isNotBlank())
            assertTrue(guide.id, guide.sections.all { it.bullets.isNotEmpty() })
        }
    }

    @Test
    fun `no guide is labelled simulated`() {
        for (guide in PrepareGuides.ALL) {
            val text = (listOf(guide.title, guide.summary, guide.source) +
                    guide.sections.flatMap { listOf(it.title) + it.bullets })
                .joinToString(" ").lowercase()
            assertFalse(guide.id, "simulated" in text)
        }
    }

    @Test
    fun `first aid ends with the training disclaimer and 112`() {
        val guide = PrepareGuides.byId("first-aid")!!
        assertTrue(guide.source.contains("IFRC"))
        assertFalse(guide.source.contains("Indian Manual of First Aid"))
        val text = guide.sections.flatMap { it.bullets }.joinToString(" ")
        assertTrue(text.contains("This is not a substitute for first-aid training"))
        assertTrue(text.contains("112"))
        // The verified burn rule from the IFRC 2020 text.
        assertTrue(text.contains("at least 10 minutes, ideally 20 minutes"))
    }

    @Test
    fun `go-bag water quantity is labelled American Red Cross, never NDMA`() {
        val guide = PrepareGuides.byId("go-bag")!!
        val text = guide.sections.flatMap { it.bullets }.joinToString(" ")
        assertTrue(text.contains("American Red Cross"))
        assertTrue(text.contains("3.8 litres"))
        assertTrue(text.contains("NDMA lists sealed water as an item but gives NO quantity"))
    }

    @Test
    fun `search finds by word in title, summary or body`() {
        assertEquals(listOf("first-aid"), PrepareGuides.filter(query = "burns").map { it.id })
        // "go-bag" is named in the go-bag guide and in the family plan
        // (keep a copy in your go-bag) — and cross-referenced in the
        // flood, earthquake and fire guides ("keep your go-bag ready",
        // "keep your go-bag ... in the dark", "your go-bag near the
        // exit"). Full-text search matches all of them, in list order.
        // (FIX7: the old expectation listed only go-bag + family-plan
        // and missed those cross-references — that was the 1 failure
        // in the 19 Nov test run.)
        assertEquals(
            listOf("flood", "earthquake", "fire", "go-bag", "family-plan"),
            PrepareGuides.filter(query = "go-bag").map { it.id }
        )
        // Same story for "meeting point": it is in the family plan,
        // but also in the fire guide ("agree a meeting point outside")
        // and in the SOS guide ("know your meeting point ...").
        assertEquals(
            listOf("fire", "family-plan", "sos-not-sending"),
            PrepareGuides.filter(query = "meeting point").map { it.id }
        )
        assertTrue(PrepareGuides.filter(query = "WATER").map { it.id }.contains("go-bag"))
        assertEquals(9, PrepareGuides.filter(query = "").size)
        assertTrue(PrepareGuides.filter(query = "zzzz-no-such-word").isEmpty())
    }

    @Test
    fun `hazard chips narrow to one hazard`() {
        assertEquals(listOf("flood"), PrepareGuides.filter(hazard = "flood").map { it.id })
        assertEquals(
            setOf("go-bag", "family-plan"),
            PrepareGuides.filter(hazard = "family-plan").map { it.id }.toSet()
        )
        assertEquals(9, PrepareGuides.filter(hazard = "all").size)
        // The SOS guide has no hazard: it shows under All, never under a chip.
        assertFalse(PrepareGuides.filter(hazard = "fire").map { it.id }.contains("sos-not-sending"))
        assertTrue(PrepareGuides.filter(hazard = "all").map { it.id }.contains("sos-not-sending"))
    }

    @Test
    fun `saved-only keeps only saved guides`() {
        val saved = setOf("flood", "first-aid")
        assertEquals(
            saved,
            PrepareGuides.filter(savedIds = saved, savedOnly = true).map { it.id }.toSet()
        )
        assertTrue(PrepareGuides.filter(savedIds = emptySet(), savedOnly = true).isEmpty())
        // Search and Saved combine.
        assertEquals(
            listOf("first-aid"),
            PrepareGuides.filter(query = "burns", savedIds = saved, savedOnly = true).map { it.id }
        )
    }

    @Test
    fun `status lines translate every record state to plain words`() {
        assertEquals("Sending to the mesh…", PrepareGuides.statusLineFor("CREATED"))
        assertEquals("Sending to the mesh…", PrepareGuides.statusLineFor("QUEUED"))
        assertEquals("Sending to a nearby phone…", PrepareGuides.statusLineFor("SENDING"))
        assertEquals(
            "Reached phone-to-phone — waiting for a gateway",
            PrepareGuides.statusLineFor("PEER_ACKED")
        )
        assertEquals("Reached the server ✓", PrepareGuides.statusLineFor("SERVER_SYNCED"))
        assertEquals("Did not reach — will try again", PrepareGuides.statusLineFor("FAILED_RETRYABLE"))
        assertEquals("Did not reach — time expired", PrepareGuides.statusLineFor("EXPIRED"))
        assertEquals("Rejected by the server", PrepareGuides.statusLineFor("REJECTED"))
    }
}
