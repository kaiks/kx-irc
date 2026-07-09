package com.kx.irc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("kx_irc_prefs", Context.MODE_PRIVATE)
    private val passwordCipher = PasswordCipher()

    fun load(): IrcConfig {
        return IrcConfig(
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 6697),
            useTls = prefs.getBoolean("useTls", true),
            nick = prefs.getString("nick", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            realName = prefs.getString("realName", "").orEmpty(),
            channels = prefs.getString("channels", "").orEmpty(),
            serverPassword = loadPassword()
        )
    }

    /** Returns an error only when a non-empty password could not be protected. */
    fun save(config: IrcConfig): String? {
        val encryptedPassword = if (config.serverPassword.isBlank()) {
            null
        } else {
            passwordCipher.encrypt(config.serverPassword)
                ?: return "Could not securely save the IRC password"
        }

        prefs.edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putBoolean("useTls", config.useTls)
            .putString("nick", config.nick)
            .putString("username", config.username)
            .putString("realName", config.realName)
            .putString("channels", config.channels)
            .apply {
                if (encryptedPassword == null) remove(ENCRYPTED_PASSWORD_KEY)
                else putString(ENCRYPTED_PASSWORD_KEY, encryptedPassword)
            }
            .remove(LEGACY_PASSWORD_KEY)
            .apply()
        return null
    }

    private fun loadPassword(): String {
        val encrypted = prefs.getString(ENCRYPTED_PASSWORD_KEY, null)
        if (encrypted != null) return passwordCipher.decrypt(encrypted).orEmpty()

        // Migrate existing installs without retaining the plaintext value on disk.
        val legacy = prefs.getString(LEGACY_PASSWORD_KEY, null) ?: return ""
        val replacement = passwordCipher.encrypt(legacy)
        if (replacement != null) {
            prefs.edit()
                .putString(ENCRYPTED_PASSWORD_KEY, replacement)
                .remove(LEGACY_PASSWORD_KEY)
                .apply()
        }
        return legacy
    }

    private class PasswordCipher {
        fun encrypt(value: String): String? = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        }.getOrNull()

        fun decrypt(value: String): String? = runCatching {
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()

        private fun secretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
        }
    }

    private companion object {
        const val LEGACY_PASSWORD_KEY = "serverPassword"
        const val ENCRYPTED_PASSWORD_KEY = "serverPasswordEncrypted"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kx_irc_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
