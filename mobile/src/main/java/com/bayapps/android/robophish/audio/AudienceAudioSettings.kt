package com.bayapps.android.robophish.audio

import android.content.Context

class AudienceAudioSettings private constructor(context: Context) {
    data class Settings(val enabled: Boolean, val amount: Float, val mode: AudienceDsp.Mode)
    private val prefs = context.applicationContext.getSharedPreferences("audience_audio", Context.MODE_PRIVATE)
    @Volatile var current: Settings = read()
        private set
    @Volatile var compareOriginal: Boolean = false

    private fun read() = Settings(prefs.getBoolean("enabled", false),
        prefs.getFloat("amount", 0.6f).coerceIn(0f, 1f),
        runCatching { AudienceDsp.Mode.valueOf(prefs.getString("mode", "RUMBLE")!!) }
            .getOrDefault(AudienceDsp.Mode.RUMBLE))

    fun update(enabled: Boolean = current.enabled, amount: Float = current.amount,
               mode: AudienceDsp.Mode = current.mode) {
        val safeAmount = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0.6f
        current = Settings(enabled, safeAmount, mode)
        prefs.edit().putBoolean("enabled", enabled).putFloat("amount", safeAmount)
            .putString("mode", mode.name).apply()
    }

    companion object {
        @Volatile private var instance: AudienceAudioSettings? = null
        fun get(context: Context): AudienceAudioSettings = instance ?: synchronized(this) {
            instance ?: AudienceAudioSettings(context).also { instance = it }
        }
    }
}
