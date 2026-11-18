package com.rescuemesh.crypto

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import java.security.GeneralSecurityException
import java.util.Locale

/**
 * Hop entries (batch contracts §2, Prompt 5). One entry per device that has
 * handled a record, stored in order in the envelope's `hopTrace` array:
 *
 * ```
 * {"i":0,"role":"ORIGIN","deviceId":"<64hex>","pk":"<b64url raw pubkey>",
 *  "at":1700000000000,"prevSig":"","sig":"<b64url 64B>"}
 * ```
 *
 * - `pk` is NOT in the entry's signed set; it is bound instead by the check
 *   `deviceId == hex(SHA-256(raw(pk)))`, which every validator runs.
 * - The signed set for `sig` is the canonical JSON of {messageId} plus the
 *   entry's own fields among {i, role, deviceId, at, prevSig} and `loc`/`net`
 *   when present.
 * - Optional `loc` is a STRING "<lat>,<lon>" with exactly 3 decimals each
 *   (canonical JSON has no floats); optional `net` ("WIFI"|"CELLULAR") is
 *   for GATEWAY entries only.
 * - An invalid entry is flagged, never fatal: the record is still stored,
 *   the entry is just ignored for path/count purposes.
 *
 * Pure JVM logic (Tink signing aside) — fully unit-testable.
 */
data class HopEntry(
    val i: Int,
    val role: String,
    val deviceId: String,
    /** Raw Ed25519 public key of [deviceId]'s holder, Base64url-no-pad. */
    val pk: String,
    /** Epoch ms when this device handled the record. */
    val at: Long,
    /** Previous entry's `sig`; entry 0 uses "". */
    val prevSig: String,
    /** Base64url-no-pad 64-byte Ed25519 signature over the §2 signed set. */
    val sig: String,
    val loc: String? = null,
    val net: String? = null
)

object HopTrace {
    const val ROLE_ORIGIN = "ORIGIN"
    const val ROLE_RELAY = "RELAY"
    const val ROLE_GATEWAY = "GATEWAY"

    const val NET_WIFI = "WIFI"
    const val NET_CELLULAR = "CELLULAR"

    /** "<lat>,<lon>", exactly 3 decimals, dot separator — a string, never numbers. */
    fun formatLoc(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.3f,%.3f", latitude, longitude)

    /** The wire/stored map for one entry; `loc`/`net` appear only when present. */
    fun entryToMap(entry: HopEntry): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "i" to entry.i,
            "role" to entry.role,
            "deviceId" to entry.deviceId,
            "pk" to entry.pk,
            "at" to entry.at,
            "prevSig" to entry.prevSig,
            "sig" to entry.sig
        )
        entry.loc?.let { map["loc"] = it }
        entry.net?.let { map["net"] = it }
        return map
    }

    /** Parses one entry from a decoded JSON value; null when structurally wrong. */
    fun entryFromValue(value: Any?): HopEntry? {
        val map = value as? Map<*, *> ?: return null
        val i = (map["i"] as? Number)?.toInt() ?: return null
        val role = map["role"] as? String ?: return null
        val deviceId = map["deviceId"] as? String ?: return null
        val pk = map["pk"] as? String ?: return null
        val at = (map["at"] as? Number)?.toLong() ?: return null
        val prevSig = map["prevSig"] as? String ?: return null
        val sig = map["sig"] as? String ?: return null
        return HopEntry(
            i = i,
            role = role,
            deviceId = deviceId,
            pk = pk,
            at = at,
            prevSig = prevSig,
            sig = sig,
            loc = map["loc"] as? String,
            net = map["net"] as? String
        )
    }

    /** Parses a whole trace from decoded JSON; null when any entry is malformed. */
    fun traceFromValue(value: Any?): List<HopEntry>? {
        val list = value as? List<*> ?: return null
        val entries = ArrayList<HopEntry>(list.size)
        for (item in list) entries += entryFromValue(item) ?: return null
        return entries
    }

    /** Parses the stored JSON text of a trace; null when malformed. */
    fun parseTrace(jsonText: String): List<HopEntry>? = try {
        traceFromValue(JsonParser.parse(jsonText))
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Canonical JSON text for a trace — the form stored in Room and put on the wire. */
    fun traceToJson(entries: List<HopEntry>): String =
        CanonicalJson.canonicalize(entries.map { entryToMap(it) })

    /**
     * The §2 signed set: {messageId} + the entry's own fields among
     * {i, role, deviceId, at, prevSig} + `loc`/`net` when present.
     * `pk` and `sig` are never in it.
     */
    fun signedFields(messageId: String, entry: HopEntry): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "messageId" to messageId,
            "i" to entry.i,
            "role" to entry.role,
            "deviceId" to entry.deviceId,
            "at" to entry.at,
            "prevSig" to entry.prevSig
        )
        entry.loc?.let { map["loc"] = it }
        entry.net?.let { map["net"] = it }
        return map
    }

    /** The exact bytes an entry's `sig` signs / verifies over. */
    fun canonicalSignedBytes(messageId: String, entry: HopEntry): ByteArray =
        CanonicalJson.canonicalBytes(signedFields(messageId, entry))

    /**
     * §2 validity of ONE entry, without chain context: the id binding
     * (`deviceId == hex(SHA-256(raw(pk)))`) and the signature under `pk`
     * over the signed set. Malformation is false, never an exception.
     */
    fun verifyEntry(entry: HopEntry, messageId: String): Boolean {
        val rawPublicKey = try {
            DeviceIds.base64UrlDecode(entry.pk)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (!DeviceIds.isValidOriginDeviceId(entry.deviceId, rawPublicKey)) return false
        val signature = try {
            DeviceIds.base64UrlDecode(entry.sig)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (signature.size != EnvelopeSigner.SIGNATURE_BYTES) return false
        return try {
            EnvelopeSigner.verifierFor(rawPublicKey)
                .verify(signature, canonicalSignedBytes(messageId, entry))
            true
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Per-entry validity for a whole trace, WITH chain context: entry `n`
     * is valid when [verifyEntry] passes, its `i` equals its position, its
     * `prevSig` equals the previous entry's `sig` (entry 0 must carry ""),
     * and — for entry 0 — it is the ORIGIN entry of [originDeviceId].
     * Validity is per-entry and never contagious: a broken entry does not
     * invalidate its neighbours (the contracts: flagged, never fatal).
     */
    fun validateTrace(trace: List<HopEntry>, messageId: String, originDeviceId: String): List<Boolean> =
        trace.mapIndexed { index, entry ->
            val chainOk = if (index == 0) entry.prevSig.isEmpty() else entry.prevSig == trace[index - 1].sig
            val originOk = index != 0 || (entry.role == ROLE_ORIGIN && entry.deviceId == originDeviceId)
            entry.i == index && chainOk && originOk && verifyEntry(entry, messageId)
        }
}

/**
 * Signs hop entries for THIS device. Built from a raw Tink [PublicKeySign]
 * exactly the way [EnvelopeSigner] is (see RescueMeshApp / DeviceIdentity);
 * [deviceId] must be the originDeviceId derived from [rawPublicKey] — the
 * §2 id binding every receiver will check.
 */
class HopSigner(
    private val signer: PublicKeySign,
    val deviceId: String,
    val rawPublicKey: ByteArray
) {
    val publicKeyBase64Url: String = DeviceIds.base64UrlEncode(rawPublicKey)

    init {
        require(DeviceIds.isValidOriginDeviceId(deviceId, rawPublicKey)) {
            "HopSigner deviceId must be hex(SHA-256(rawPublicKey))"
        }
    }

    /** Builds and signs one entry for this device. */
    fun signEntry(
        messageId: String,
        i: Int,
        role: String,
        at: Long,
        prevSig: String,
        loc: String? = null,
        net: String? = null
    ): HopEntry {
        val unsigned = HopEntry(
            i = i,
            role = role,
            deviceId = deviceId,
            pk = publicKeyBase64Url,
            at = at,
            prevSig = prevSig,
            sig = "",
            loc = loc,
            net = net
        )
        val signature = signer.sign(HopTrace.canonicalSignedBytes(messageId, unsigned))
        check(signature.size == EnvelopeSigner.SIGNATURE_BYTES) {
            "Ed25519 signature must be exactly ${EnvelopeSigner.SIGNATURE_BYTES} bytes, got ${signature.size}"
        }
        return unsigned.copy(sig = DeviceIds.base64UrlEncode(signature))
    }

    /** Entry 0, written by the origin at creation time (`at` = the record's createdAt). */
    fun originEntry(messageId: String, at: Long, loc: String? = null): HopEntry =
        signEntry(messageId, i = 0, role = HopTrace.ROLE_ORIGIN, at = at, prevSig = "", loc = loc)

    /**
     * The entry this device appends to [trace] when it stores a received
     * record (role RELAY) or uploads one (role GATEWAY, with `net`).
     */
    fun appendEntry(
        messageId: String,
        trace: List<HopEntry>,
        role: String,
        at: Long,
        loc: String? = null,
        net: String? = null
    ): HopEntry = signEntry(
        messageId = messageId,
        i = trace.size,
        role = role,
        at = at,
        prevSig = trace.lastOrNull()?.sig ?: "",
        loc = loc,
        net = net
    )

    companion object {
        /** An [HopSigner] backed by a Tink private keyset handle (tests / DI). */
        fun fromKeysetHandle(privateKeysetHandle: KeysetHandle): HopSigner {
            val rawPublicKey = EnvelopeSigner.rawPublicKeyBytes(privateKeysetHandle)
            return HopSigner(
                signer = privateKeysetHandle.getPrimitive(
                    RegistryConfiguration.get(),
                    PublicKeySign::class.java
                ),
                deviceId = DeviceIds.originDeviceId(rawPublicKey),
                rawPublicKey = rawPublicKey
            )
        }
    }
}
