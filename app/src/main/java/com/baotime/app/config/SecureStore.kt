package com.baotime.app.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Password reads retain the difference between absence and lost/corrupt credentials. */
sealed interface PasswordRead {
    data object Missing : PasswordRead
    data class Available(val value: String) : PasswordRead
    data class Unavailable(val reason: PasswordFailure) : PasswordRead
}

enum class PasswordFailure {
    UnsupportedVersion, InvalidEncoding, MissingKey, AuthenticationFailed, KeystoreUnavailable
}

/** Android Keystore AES/GCM storage. Reads never create keys or change stored bytes. */
object SecureStore {
    private const val KEY_ALIAS = "baotime_server_password"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ENVELOPE_PREFIX = "v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val keyInitializationLock = Any()
    private val base64Pattern = Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")

    private fun loadExistingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = store.getKey(KEY_ALIAS, null)
        return if (key is SecretKey) key else null
    }

    private fun encryptionKey(): SecretKey {
        loadExistingKey()?.let { return it }
        return synchronized(keyInitializationLock) {
            loadExistingKey() ?: generateEncryptionKey()
        }
    }

    private fun generateEncryptionKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ENVELOPE_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /** Unversioned data is accepted only when the original AES/GCM tag authenticates. */
    fun decrypt(encoded: String): PasswordRead {
        if (encoded.isEmpty()) return PasswordRead.Missing
        val payload = when {
            encoded.startsWith(ENVELOPE_PREFIX) -> encoded.removePrefix(ENVELOPE_PREFIX)
            encoded.contains(':') -> return PasswordRead.Unavailable(PasswordFailure.UnsupportedVersion)
            else -> encoded
        }
        if (payload.isEmpty() || !base64Pattern.matches(payload)) {
            return PasswordRead.Unavailable(PasswordFailure.InvalidEncoding)
        }
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES + TAG_BITS / 8) {
            return PasswordRead.Unavailable(PasswordFailure.InvalidEncoding)
        }
        return runCatching { loadExistingKey() }.fold(
            onSuccess = { key ->
                if (key == null) PasswordRead.Unavailable(PasswordFailure.MissingKey)
                else decryptPayload(bytes, key)
            },
            onFailure = { failure ->
                if (failure is GeneralSecurityException || failure is IOException) {
                    PasswordRead.Unavailable(PasswordFailure.KeystoreUnavailable)
                } else throw failure
            }
        )
    }

    private fun decryptPayload(bytes: ByteArray, key: SecretKey): PasswordRead = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }.fold(
        onSuccess = { PasswordRead.Available(it) },
        onFailure = { failure ->
            if (failure is GeneralSecurityException) PasswordRead.Unavailable(PasswordFailure.AuthenticationFailed)
            else throw failure
        }
    )
}
