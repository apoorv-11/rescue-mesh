package com.rescuemesh.net

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * The account JWT, encrypted at rest.
 *
 * Construction mirrors crypto/DeviceIdentity (Prompt 4): a Tink AEAD keyset
 * (AES-256-GCM) stored by AndroidKeysetManager in the app-private
 * SharedPreferences file [KEYSET_PREF_FILE_NAME], wrapped under an
 * AES-256-GCM KEK that lives in Android Keystore under alias [KEK_ALIAS]
 * (StrongBox when the phone has one; no user-authentication requirement —
 * the app must be able to refresh its session unattended). Only the
 * ciphertext (Base64) of the token ever lands in [TOKEN_PREF_FILE_NAME].
 * The keyset file is excluded from backup and device transfer (see the
 * rules files under res/xml): Keystore keys cannot move devices, so a
 * restored keyset would be undecryptable anyway.
 *
 * All operations touch Keystore/disk: construct and call from
 * Application.onCreate or a background dispatcher, like DeviceIdentity.
 */
class TokenStore(context: Context) {

    private val appContext = context.applicationContext
    private val aead: Aead
    private val prefs = appContext.getSharedPreferences(TOKEN_PREF_FILE_NAME, Context.MODE_PRIVATE)

    init {
        AeadConfig.register()
        ensureKeystoreKek()
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREF_FILE_NAME)
            // AES-256-GCM — used only when a brand-new keyset is created.
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        aead = manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** The stored JWT, or null when logged out — or when the ciphertext no longer decrypts. */
    fun token(): String? {
        val stored = prefs.getString(KEY_TOKEN_CIPHERTEXT, null) ?: return null
        return runCatching {
            val ciphertext = Base64.decode(stored, Base64.NO_WRAP)
            String(aead.decrypt(ciphertext, NO_ASSOCIATED_DATA), Charsets.UTF_8)
        }.getOrNull()
    }

    fun save(token: String) {
        val ciphertext = aead.encrypt(token.toByteArray(Charsets.UTF_8), NO_ASSOCIATED_DATA)
        prefs.edit()
            .putString(KEY_TOKEN_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN_CIPHERTEXT).apply()
    }

    /**
     * Creates this store's AES-256-GCM KEK in Android Keystore if absent.
     * Same StrongBox pattern as DeviceIdentity.ensureKeystoreKek.
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

    companion object {
        const val KEK_ALIAS = "rescuemesh_token_kek_v1"
        const val KEYSET_NAME = "rescuemesh_token_keyset"
        const val KEYSET_PREF_FILE_NAME = "rescuemesh_token_keyset"
        const val TOKEN_PREF_FILE_NAME = "rescuemesh_token_store"
        const val MASTER_KEY_URI = "android-keystore://$KEK_ALIAS"
        private const val KEY_TOKEN_CIPHERTEXT = "jwtCiphertext"
        private val NO_ASSOCIATED_DATA = ByteArray(0)
    }
}
