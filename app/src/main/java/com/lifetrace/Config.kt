package com.lifetrace

import android.content.Context
import android.content.SharedPreferences

/**
 * Server URL + ingest token live ONLY in on-device SharedPreferences, entered once by the user
 * on first run. They are never compiled into the released APK (BuildConfig defaults are empty),
 * so nothing secret ships in the GitHub release. Survives app updates.
 */
object Config {
    private const val PREFS = "lifetrace_config"
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    val baseUrl: String
        get() = prefs.getString("base_url", BuildConfig.BASE_URL) ?: BuildConfig.BASE_URL

    val token: String
        get() = prefs.getString("token", BuildConfig.INGEST_TOKEN) ?: BuildConfig.INGEST_TOKEN

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && token.isNotBlank()

    fun save(url: String, tok: String) {
        prefs.edit().putString("base_url", url.trim()).putString("token", tok.trim()).apply()
    }
}
