package com.rescuemesh.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.Ed25519PrivateKeyManager
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator

/**
 * This phone's mesh identity (Prompt 4; decisions in
 * prompt4-identity-decisions-2026-11-14.md):
 *
 * - One Ed25519 keyset (Tink, NO_PREFIX template — raw 64-byte signatures),
 *   generated on first launch and then loaded forever.
 * - The keyset is stored encrypted in the app-private SharedPreferences file
 *   [PREF_FILE_NAME] by [AndroidKeysetManager], wrapped under a key-encryption
 *   key (KEK) that lives in Android Keystore under alias [KEK_ALIAS]. The KEK
 *   is AES-256-GCM, StrongBox-backed when the phone has one, and does NOT
 *   require user authentication — the mesh must be able to sign an SOS
 *   unattended. KEK material never leaves the Keystore; the Ed25519 seed is
 *   plaintext only inside Tink while signing — never in Room, logs, or the
 *   envelope.
 * - [originDeviceId] is derived from the public key (see [DeviceIds]), so a
 *   fresh install / factory reset / new phone produces a NEW identity by
 *   design. The keyset file is excluded from backup and device transfer (see
 *   res/xml/data_extraction_rules.xml and res/xml/backup_rules.xml): Keystore
 *   keys cannot move devices, so a restored keyset would be undecryptable.
 *
 * Loading touches Keystore and disk: call it from Application.onCreate or a
 * background thread, not inside UI composition.
 */
class DeviceIdentity private constructor(
    val originDeviceId: String,
    val rawPublicKey: ByteArray,
    private val signer: PublicKeySign
) {
    /** The public key as Base64url-no-pad (for HELLO in Prompt 5 / QR trust exchange). */
    val publicKeyBase64Url: String = DeviceIds.base64UrlEncode(rawPublicKey)

    /** Signs [data] with this device's Ed25519 key; returns the raw 64-byte signature. */
    fun sign(data: ByteArray): ByteArray = signer.sign(data)

    /** An [EnvelopeSigner] bound to this identity. */
    fun envelopeSigner(): EnvelopeSigner = EnvelopeSigner(signer)

    /** A [HopSigner] bound to this identity (Prompt 5 hop entries). */
    fun hopSigner(): HopSigner = HopSigner(signer, originDeviceId, rawPublicKey)

    companion object {
        const val KEK_ALIAS = "rescuemesh_kek_v1"
        const val KEYSET_NAME = "rescuemesh_identity_keyset"
        const val PREF_FILE_NAME = "rescuemesh_identity_keyset"
        const val MASTER_KEY_URI = "android-keystore://$KEK_ALIAS"

        private val tinkRegistered = AtomicBoolean(false)

        /** Loads the existing identity, or creates it (KEK + keyset) on first launch. */
        fun loadOrCreate(context: Context): DeviceIdentity {
            registerTinkOnce()
            val appContext = context.applicationContext
            ensureKeystoreKek()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, PREF_FILE_NAME)
                // NO_PREFIX Ed25519 — used only when a brand-new keyset is created.
                .withKeyTemplate(Ed25519PrivateKeyManager.rawEd25519Template())
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            val handle: KeysetHandle = manager.keysetHandle
            val rawPublicKey = EnvelopeSigner.rawPublicKeyBytes(handle)
            return DeviceIdentity(
                originDeviceId = DeviceIds.originDeviceId(rawPublicKey),
                rawPublicKey = rawPublicKey,
                signer = handle.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
            )
        }

        private fun registerTinkOnce() {
            if (tinkRegistered.compareAndSet(false, true)) {
                TinkConfig.register()
            }
        }

        /**
         * Creates the AES-256-GCM KEK in Android Keystore if absent.
         * StrongBox is attempted first (API 28+) and silently given up on
         * phones without one — exactly the pattern the Keystore docs describe.
         */
        private fun ensureKeystoreKek() {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEK_ALIAS)) return
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generator.init(keySpec(strongBox = true))
                    generator.generateKey()
                    return
                } catch (e: StrongBoxUnavailableException) {
                    if (keyStore.containsAlias(KEK_ALIAS)) return
                    // No StrongBox on this phone: fall through to TEE/software.
                }
            }
            generator.init(keySpec(strongBox = false))
            generator.generateKey()
        }

        private fun keySpec(strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
    }
}
