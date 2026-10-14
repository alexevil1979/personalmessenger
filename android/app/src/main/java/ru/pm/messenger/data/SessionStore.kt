package ru.pm.messenger.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): Account? {
        val access = preferences.getString(ACCESS, null) ?: return null
        val refresh = preferences.getString(REFRESH, null) ?: return null
        val userId = preferences.getString(USER_ID, null) ?: return null
        val username = preferences.getString(USERNAME, null) ?: return null
        val baseUrl = preferences.getString(BASE_URL, null) ?: return null
        return Account(
            userId = userId,
            username = username,
            displayName = preferences.getString(DISPLAY_NAME, username).orEmpty(),
            role = preferences.getString(ROLE, null),
            accessToken = access,
            refreshToken = refresh,
            sessionId = preferences.getString(SESSION_ID, "").orEmpty(),
            baseUrl = baseUrl,
        )
    }

    fun write(account: Account) {
        preferences.edit()
            .putString(ACCESS, account.accessToken)
            .putString(REFRESH, account.refreshToken)
            .putString(USER_ID, account.userId)
            .putString(USERNAME, account.username)
            .putString(DISPLAY_NAME, account.displayName)
            .putString(ROLE, account.role)
            .putString(SESSION_ID, account.sessionId)
            .putString(BASE_URL, account.baseUrl)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE = "session"
        const val ACCESS = "access"
        const val REFRESH = "refresh"
        const val USER_ID = "user_id"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "display_name"
        const val ROLE = "role"
        const val SESSION_ID = "session_id"
        const val BASE_URL = "base_url"
    }
}
