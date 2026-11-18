package com.rescuemesh.domain

import com.rescuemesh.crypto.JsonParser
import java.io.File
import java.security.MessageDigest

/**
 * The signed pointer to a record's full photo (photo-transfer design,
 * 14 Nov 2026). It lives INSIDE the record's signed payload, so the photo
 * is authenticated by the origin's signature: change one byte of the JPEG
 * and its SHA-256 no longer matches what the origin signed.
 *
 * [photoId] IS the owning record's own messageId — one full photo per
 * record. The photo bytes themselves never travel in the envelope; they
 * move over the mesh in PHOTO_CHUNK packets (see mesh/Packets.kt) and are
 * verified against [sha256] + [sizeBytes] before a node keeps or forwards
 * them.
 */
data class PhotoRef(
    val photoId: String,
    val sha256: String,
    val sizeBytes: Int,
    val mime: String = MIME_JPEG
) {
    companion object {
        const val MIME_JPEG = "image/jpeg"

        /** Builds the ref for [jpegBytes] owned by the record [messageId]. */
        fun of(messageId: String, jpegBytes: ByteArray): PhotoRef =
            PhotoRef(
                photoId = messageId,
                sha256 = PhotoStore.sha256Hex(jpegBytes),
                sizeBytes = jpegBytes.size
            )

        /**
         * Reads the `photoRef` object out of a stored record payload;
         * null when the record carries no full photo (the common case) or
         * the value is malformed. This is the relay engine's lookup — the
         * payload it reads is the origin-signed canonical payload, so the
         * ref it yields is the authenticated one.
         */
        fun parse(payloadJson: String): PhotoRef? {
            val payload = runCatching { JsonParser.parse(payloadJson) as? Map<*, *> }
                .getOrNull() ?: return null
            val ref = payload["photoRef"] as? Map<*, *> ?: return null
            val photoId = ref["photoId"] as? String ?: return null
            val sha256 = ref["sha256"] as? String ?: return null
            val sizeBytes = (ref["sizeBytes"] as? Number)?.toInt() ?: return null
            if (photoId.isBlank() || sha256.length != 64 || sizeBytes <= 0) return null
            val mime = ref["mime"] as? String ?: MIME_JPEG
            return PhotoRef(photoId, sha256, sizeBytes, mime)
        }
    }
}

/**
 * On-disk store for full photos (photo-transfer design §2): under
 * `<root>/photos/`, a complete photo is `<messageId>.jpg` and in-flight
 * chunks live in `<messageId>.parts/<index>` until they assemble and
 * verify. No Room BLOBs — Room only holds the signed [PhotoRef].
 *
 * Pure file + hash logic (java.io / MessageDigest only, no Android
 * types): [rootDir] is the app's filesDir in production (injected by
 * RescueMeshApp) and a temp dir in JVM tests.
 *
 * A node only ever holds a COMPLETE photo it has verified byte-for-byte
 * against the signed ref — that is the store-and-forward guarantee:
 * parts are forwarded never, complete verified photos always.
 */
class PhotoStore(private val rootDir: File) {

    private fun photosDir(): File = File(rootDir, PHOTOS_DIR_NAME)

    fun completeFile(messageId: String): File = File(photosDir(), "$messageId.jpg")

    fun partsDir(messageId: String): File = File(photosDir(), "$messageId.parts")

    private fun partFile(messageId: String, index: Int): File =
        File(partsDir(messageId), index.toString())

    /** Stores a complete photo (creation on this phone, or a verified assembly promotion). */
    fun saveComplete(messageId: String, bytes: ByteArray) {
        if (!isSafeId(messageId)) return
        photosDir().mkdirs()
        val target = completeFile(messageId)
        val tmp = File(photosDir(), "$messageId.jpg.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            // Same-filesystem rename can still fail on some OEM ROMs; fall
            // back to a direct write so creation never loses the photo.
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    fun hasComplete(messageId: String): Boolean =
        isSafeId(messageId) && completeFile(messageId).isFile

    fun readComplete(messageId: String): ByteArray? {
        if (!isSafeId(messageId)) return null
        val file = completeFile(messageId)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    /** Stores one received chunk. Out-of-range indices are refused by the caller (engine). */
    fun writePart(messageId: String, index: Int, bytes: ByteArray) {
        if (!isSafeId(messageId) || index < 0) return
        partsDir(messageId).mkdirs()
        partFile(messageId, index).writeBytes(bytes)
    }

    /** Indices of the parts currently on disk for [messageId], ascending. */
    fun partIndices(messageId: String): List<Int> {
        if (!isSafeId(messageId)) return emptyList()
        val dir = partsDir(messageId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.sorted() ?: emptyList()
    }

    /** Which of the [total] chunks are NOT on disk yet — the PHOTO_ACK MISSING list. */
    fun missingIndices(messageId: String, total: Int): List<Int> {
        val present = partIndices(messageId).toSet()
        return (0 until total).filter { it !in present }
    }

    /**
     * Tries to promote the parts of [messageId] to the complete file.
     * Returns false (keeping the parts) while any chunk is missing.
     * When all [total] chunks are present the bytes are concatenated in
     * index order and checked against [expectedSize] + [expectedSha256]:
     * a mismatch means corruption — the parts are DISCARDED (they can
     * never verify) and false is returned, so the peer reports the photo
     * missing from scratch. On success the parts dir is removed.
     */
    fun assembleIfComplete(
        messageId: String,
        total: Int,
        expectedSize: Int,
        expectedSha256: String
    ): Boolean {
        if (!isSafeId(messageId) || total <= 0) return false
        if (missingIndices(messageId, total).isNotEmpty()) return false
        val assembled = ByteArray(expectedSize)
        var offset = 0
        for (index in 0 until total) {
            val part = runCatching { partFile(messageId, index).readBytes() }.getOrNull()
            if (part == null || offset + part.size > expectedSize) {
                deleteParts(messageId)
                return false
            }
            part.copyInto(assembled, offset)
            offset += part.size
        }
        if (offset != expectedSize || sha256Hex(assembled) != expectedSha256) {
            deleteParts(messageId)
            return false
        }
        saveComplete(messageId, assembled)
        deleteParts(messageId)
        return true
    }

    /** Deletes the complete file AND any in-flight parts (TTL purge path). */
    fun deleteFor(messageId: String) {
        if (!isSafeId(messageId)) return
        runCatching { completeFile(messageId).delete() }
        deleteParts(messageId)
    }

    private fun deleteParts(messageId: String) {
        runCatching { partsDir(messageId).deleteRecursively() }
    }

    companion object {
        const val PHOTOS_DIR_NAME = "photos"

        /** Lowercase hex SHA-256 of [bytes] — the same digest form the payload carries. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v shr 4])
                sb.append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"

        /**
         * Message ids become file names, and ids also arrive from peers:
         * only the id alphabet this system generates (UUID / hex / labels)
         * is accepted, so a crafted id can never escape the photos dir.
         */
        private fun isSafeId(messageId: String): Boolean =
            messageId.isNotBlank() && !messageId.contains("..") &&
                messageId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
