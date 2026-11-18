package com.rescuemesh.mesh

import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.EnvelopeSigner
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.data.local.MessageEntity

/**
 * Deterministic validity of a STORED record for relay (legacy-record fix).
 *
 * The §4.5 receive path rejects a record whose origin can never check out:
 * no hop entry 0, an entry 0 that is not the ORIGIN entry of the envelope's
 * origin, an entry-0 key that does not hash to originDeviceId, or an origin
 * signature that does not verify. A stored copy is forwarded UNCHANGED
 * (§2), so those properties can never improve: a record failing them will
 * be rejected by every conforming peer, forever. Phase-1-era records are
 * exactly that shape (UUID originDeviceId, `signature = ""`, `hopTrace =
 * []` — created before signing and hop traces existed) and used to be
 * re-offered every session, looping endlessly.
 *
 * This object mirrors the receiver's origin checks so the SENDER can reach
 * the same verdict locally, at two strengths:
 *
 * - [originProblem] — the cheap structural half (trace shape, entry 0,
 *   key binding, non-empty signature; no signature verification). This is
 *   the RELAY ELIGIBILITY rule: records failing it are never advertised in
 *   a HELLO inventory and never sent as MSG. They stay in Room, fully
 *   visible in the phone's own lists — they are the user's records, they
 *   are simply not relayable.
 * - [deterministicInvalidReason] — [originProblem] plus the envelope
 *   signature verification under entry 0's key. When a peer nevertheless
 *   answers ACK REJECTED for a record that fails THIS check, the rejection
 *   is deterministic — no peer will ever accept the record — so the sender
 *   marks it REJECTED (terminal) instead of backing off and retrying.
 *
 * Reason strings deliberately mirror the receive path's Metrics details
 * ("missing-entry0", "bad-entry0", "key-mismatch", "bad-signature", …) so
 * both phones' debug screens describe the same failure the same way.
 */
object RelayRecordValidation {

    /**
     * Structural origin problem of [entity], or null when the record looks
     * relay-valid in principle. Checks, in the receive path's order:
     * trace parses → an entry 0 exists → entry 0 is the ORIGIN entry of
     * the envelope's origin → its pk decodes and hashes to originDeviceId
     * → the envelope signature is non-empty.
     */
    fun originProblem(entity: MessageEntity): String? {
        val trace = HopTrace.parseTrace(entity.hopTrace) ?: return "malformed-trace"
        val entry0 = trace.firstOrNull() ?: return "missing-entry0"
        if (entry0.role != HopTrace.ROLE_ORIGIN || entry0.deviceId != entity.originDeviceId) {
            return "bad-entry0"
        }
        val originPublicKey = try {
            DeviceIds.base64UrlDecode(entry0.pk)
        } catch (e: IllegalArgumentException) {
            return "bad-entry0"
        }
        if (!DeviceIds.isValidOriginDeviceId(entity.originDeviceId, originPublicKey)) {
            return "key-mismatch"
        }
        if (entity.signature.isEmpty()) return "bad-signature"
        return null
    }

    /** True when [entity] may be advertised in HELLO inventory and sent as MSG. */
    fun isRelayEligible(entity: MessageEntity): Boolean = originProblem(entity) == null

    /**
     * Full deterministic verdict: [originProblem], plus the envelope
     * signature verified under entry 0's key (the §1 signed set — the
     * fields a relay never mutates). Null means this phone cannot prove
     * the record invalid, so a peer's rejection must be treated as
     * receiver-specific (its clock, its hop budget), not terminal.
     */
    fun deterministicInvalidReason(entity: MessageEntity): String? {
        originProblem(entity)?.let { return it }
        val entry0 = HopTrace.parseTrace(entity.hopTrace)?.firstOrNull() ?: return "missing-entry0"
        val originPublicKey = try {
            DeviceIds.base64UrlDecode(entry0.pk)
        } catch (e: IllegalArgumentException) {
            return "bad-entry0"
        }
        return if (EnvelopeSigner.verifyEnvelope(entity, originPublicKey)) null else "bad-signature"
    }
}
