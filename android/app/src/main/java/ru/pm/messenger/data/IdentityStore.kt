package ru.pm.messenger.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "identity",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun ensure(userId: String): DirectCipher.Identity {
        val privateKey = preferences.getString("priv:$userId", null)
        val publicKey = preferences.getString("pub:$userId", null)
        if (privateKey != null && publicKey != null) {
            return DirectCipher.Identity(decode(privateKey), decode(publicKey))
        }
        val created = DirectCipher.generate()
        preferences.edit()
            .putString("priv:$userId", encode(created.privateKey))
            .putString("pub:$userId", encode(created.publicKey))
            .apply()
        return created
    }

    fun fingerprint(userId: String): String = fingerprintOf(ensure(userId).publicKey)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

fun fingerprintOf(publicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16)
    return digest.joinToString(" ") { "%02X".format(it) }
}

fun decodeKey(value: String): ByteArray = Base64.decode(value.filter { !it.isWhitespace() }, Base64.NO_WRAP)

fun encodeKey(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
