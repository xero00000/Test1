package com.gamextra4u.fexdroid.steam

import android.content.Context
import android.util.Base64
import androidx.core.content.edit

data class SteamSession(
    val accountName: String,
    val rememberSession: Boolean,
    val lastLoginAt: Long,
    val encodedToken: String?
)

class SteamSessionStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restore(): SteamSession? {
        val accountName = preferences.getString(KEY_ACCOUNT_NAME, null) ?: return null
        val remember = preferences.getBoolean(KEY_REMEMBER, false)
        val lastLogin = preferences.getLong(KEY_LAST_LOGIN, 0L)
        val token = preferences.getString(KEY_TOKEN, null)
        return SteamSession(accountName, remember, lastLogin, token)
    }

    fun persist(accountName: String, remember: Boolean, password: String?) {
        if (accountName.isBlank()) {
            return
        }
        val encodedToken = password?.takeIf { it.isNotBlank() }?.let {
            Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
        }
        preferences.edit {
            putString(KEY_ACCOUNT_NAME, accountName.trim())
            putBoolean(KEY_REMEMBER, remember)
            putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
            if (encodedToken != null) {
                putString(KEY_TOKEN, encodedToken)
            } else {
                remove(KEY_TOKEN)
            }
        }
    }

    fun markLaunched() {
        if (preferences.contains(KEY_ACCOUNT_NAME)) {
            preferences.edit {
                putLong(KEY_LAST_LOGIN, System.currentTimeMillis())
            }
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFS_NAME = "steam_session_store"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_REMEMBER = "remember_session"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val KEY_TOKEN = "session_token"
    }
}
