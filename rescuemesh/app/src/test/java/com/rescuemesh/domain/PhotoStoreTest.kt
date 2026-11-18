package com.rescuemesh.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

/**
 * Photo-transfer design, storage half (14 Nov 2026): PhotoStore's
 * complete/parts lifecycle and verification, the PhotoRef payload shape
 * (factory-built + parsed back), and PhotoFull's pure ladder/cap rules.
 * The Bitmap encoders are Android-only and are not exercised here, the
 * same split PhotoThumbTest uses.
 */
class PhotoStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PhotoStore(tmp.newFolder("files"))

    private fun chunksOf(bytes: ByteArray, chunkBytes: Int): List<ByteArray> =
        (0 until (bytes.size + chunkBytes - 1) / chunkBytes).map { i ->
            bytes.copyOfRange(i * chunkBytes, minOf(bytes.size, (i + 1) * chunkBytes))
        }

    @Test
    fun `sha256Hex matches the known SHA-256 test vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PhotoStore.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun `saveComplete round-trips and hasComplete agrees`() {
        val store = store()
        val bytes = Random(1).nextBytes(10_000)
        assertFalse(store.hasComplete("id-1"))
        assertNull(store.readComplete("id-1"))
        store.saveComplete("id-1", bytes)
        assertTrue(store.hasComplete("id-1"))
        assertTrue(bytes.contentEquals(store.readComplete("id-1")!!))
    }

    @Test
    fun `parts assemble into a byte-identical verified photo and are cleaned up`() {
        val store = store()
        val bytes = Random(2).nextBytes(40_000)
        val chunks = chunksOf(bytes, 16_384)
        assertEquals(3, chunks.size)
        // Parts arrive out of order, as they may over the mesh.
        store.writePart("id-2", 2, chunks[2])
        assertEquals(listOf(0, 1), store.missingIndices("id-2", 3))
        store.writePart("id-2", 0, chunks[0])
        store.writePart("id-2", 1, chunks[1])
        assertTrue(store.missingIndices("id-2", 3).isEmpty())
        assertTrue(
            store.assembleIfComplete("id-2", 3, bytes.size, PhotoStore.sha256Hex(bytes))
        )
        assertTrue(bytes.contentEquals(store.readComplete("id-2")!!))
        assertTrue("parts are removed after promotion", store.partIndices("id-2").isEmpty())
    }

    @Test
    fun `assembly with a missing part fails and keeps the parts for a later round`() {
        val store = store()
        val bytes = Random(3).nextBytes(40_000)
        val chunks = chunksOf(bytes, 16_384)
        store.writePart("id-3", 0, chunks[0])
        store.writePart("id-3", 2, chunks[2])
        assertFalse(
            store.assembleIfComplete("id-3", 3, bytes.size, PhotoStore.sha256Hex(bytes))
        )
        assertFalse(store.hasComplete("id-3"))
        assertEquals(listOf(0, 2), store.partIndices("id-3"))
    }

    @Test
    fun `a corrupt part fails the hash check, is never promoted, and parts are discarded`() {
        val store = store()
        val bytes = Random(4).nextBytes(40_000)
        val chunks = chunksOf(bytes, 16_384).toMutableList()
        val corrupt = chunks[1].copyOf()
        corrupt[0] = (corrupt[0] + 1).toByte()
        chunks[1] = corrupt
        chunks.forEachIndexed { i, part -> store.writePart("id-4", i, part) }
        assertFalse(
            store.assembleIfComplete("id-4", 3, bytes.size, PhotoStore.sha256Hex(bytes))
        )
        assertFalse("corrupt bytes are never promoted", store.hasComplete("id-4"))
        assertNull(store.readComplete("id-4"))
        assertTrue(
            "corrupt parts are discarded — resend starts from scratch",
            store.partIndices("id-4").isEmpty()
        )
    }

    @Test
    fun `a truncated part fails the size check and is discarded`() {
        val store = store()
        val bytes = Random(5).nextBytes(40_000)
        val chunks = chunksOf(bytes, 16_384)
        store.writePart("id-5", 0, chunks[0])
        store.writePart("id-5", 1, chunks[1].copyOf(100)) // truncated
        store.writePart("id-5", 2, chunks[2])
        assertFalse(
            store.assembleIfComplete("id-5", 3, bytes.size, PhotoStore.sha256Hex(bytes))
        )
        assertFalse(store.hasComplete("id-5"))
        assertTrue(store.partIndices("id-5").isEmpty())
    }

    @Test
    fun `deleteFor removes the complete file and any in-flight parts`() {
        val store = store()
        store.saveComplete("id-6", Random(6).nextBytes(1_000))
        store.writePart("id-6", 0, Random(7).nextBytes(500))
        store.deleteFor("id-6")
        assertFalse(store.hasComplete("id-6"))
        assertTrue(store.partIndices("id-6").isEmpty())
        assertFalse(store.partsDir("id-6").exists())
    }

    @Test
    fun `a crafted messageId cannot escape the photos directory`() {
        val store = store()
        store.saveComplete("../evil", byteArrayOf(1, 2, 3))
        assertFalse(store.hasComplete("../evil"))
        assertNull(store.readComplete("../evil"))
        assertEquals(emptyList<Int>(), store.partIndices("../evil"))
    }

    // ---------------------------------------------------------- PhotoRef

    @Test
    fun `PhotoRef of and parse round-trip through a Missing payload`() {
        val jpeg = Random(8).nextBytes(123_456)
        val ref = PhotoRef.of("missing-1", jpeg)
        assertEquals("missing-1", ref.photoId)
        assertEquals(123_456, ref.sizeBytes)
        assertEquals(PhotoStore.sha256Hex(jpeg), ref.sha256)

        val factory = MessageFactory(nowMs = { 5_000 }, newId = { "missing-1" })
        val record = factory.createMissingPerson(
            originDeviceId = "origin",
            description = "Blue jacket",
            lastSeenArea = "River bridge",
            lastSeenAtMs = 4_000,
            photoRef = ref
        )
        val parsed = PhotoRef.parse(record.payload)
        assertNotNull(parsed)
        assertEquals(ref, parsed)
    }

    @Test
    fun `PhotoRef rides the Found payload under the found records own id`() {
        val jpeg = Random(9).nextBytes(50_000)
        val factory = MessageFactory(nowMs = { 5_000 }, newId = { "found-1" })
        val ref = PhotoRef.of("found-1", jpeg)
        val record = factory.createPersonFound(
            originDeviceId = "origin",
            caseId = "missing-1",
            note = "Found safe at the relief camp",
            photoRef = ref
        )
        assertEquals("found-1", record.messageId)
        val parsed = PhotoRef.parse(record.payload)
        assertEquals(ref, parsed)
        assertEquals("found-1", parsed!!.photoId)
    }

    @Test
    fun `records without a photo parse to no ref`() {
        val factory = MessageFactory(nowMs = { 5_000 }, newId = { "plain-1" })
        val record = factory.createMissingPerson(
            originDeviceId = "origin",
            description = "No photo",
            lastSeenArea = "Market",
            lastSeenAtMs = 4_000
        )
        assertNull(PhotoRef.parse(record.payload))
        assertNull(PhotoRef.parse("not json"))
        assertNull(PhotoRef.parse("{}"))
    }

    // ---------------------------------------------------------- PhotoFull

    @Test
    fun `plan starts at 1600 q80 and ends at the 800 q50 floor for a large source`() {
        val plan = PhotoFull.plan(4000, 3000)
        assertEquals(PhotoFull.Step(1600, 80), plan.first())
        assertEquals(PhotoFull.Step(800, 50), plan.last())
        assertTrue(plan.all { it.maxSidePx in 800..1600 })
        assertTrue(plan.all { it.quality in 50..80 })
        // Sides never grow along the ladder.
        assertTrue(plan.zipWithNext().all { (a, b) -> b.maxSidePx <= a.maxSidePx })
    }

    @Test
    fun `plan never upscales a small source`() {
        val plan = PhotoFull.plan(600, 400)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.maxSidePx == 600 })
        assertEquals(80, plan.first().quality)
        assertEquals(50, plan.last().quality)
    }

    @Test
    fun `fitsCap is exactly the 512000 byte boundary`() {
        assertTrue(PhotoFull.fitsCap(ByteArray(PhotoFull.MAX_BYTES)))
        assertFalse(PhotoFull.fitsCap(ByteArray(PhotoFull.MAX_BYTES + 1)))
        assertEquals(512_000, PhotoFull.MAX_BYTES)
    }
}
