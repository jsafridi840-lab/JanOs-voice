package com.janos.voice

import android.content.Context
import android.content.SharedPreferences

/** All user settings live here (stored only on the phone). */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("janos", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) = sp.edit().putString("geminiKey", v.trim()).apply()

    var geminiModel: String
        get() = sp.getString("geminiModel", "gemini-flash-latest") ?: "gemini-flash-latest"
        set(v) = sp.edit().putString("geminiModel", v.trim().ifEmpty { "gemini-flash-latest" }).apply()

    var elevenKey: String
        get() = sp.getString("elevenKey", "") ?: ""
        set(v) = sp.edit().putString("elevenKey", v.trim()).apply()

    var elevenVoiceId: String
        get() = sp.getString("elevenVoiceId", "") ?: ""
        set(v) = sp.edit().putString("elevenVoiceId", v.trim()).apply()

    var elevenModel: String
        get() = sp.getString("elevenModel", "eleven_v3") ?: "eleven_v3"
        set(v) = sp.edit().putString("elevenModel", v.trim().ifEmpty { "eleven_v3" }).apply()

    var autoRead: Boolean
        get() = sp.getBoolean("autoRead", true)
        set(v) = sp.edit().putBoolean("autoRead", v).apply()

    var enabledApps: Set<String>
        get() = sp.getStringSet("enabledApps", DEFAULT_APPS.keys) ?: DEFAULT_APPS.keys
        set(v) = sp.edit().putStringSet("enabledApps", v).apply()

    /** Learned corrections: "wrong=>right" lines the user typed in Settings. */
    var corrections: String
        get() = sp.getString("corrections", "") ?: ""
        set(v) = sp.edit().putString("corrections", v).apply()

    companion object {
        val DEFAULT_APPS = linkedMapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.facebook.orca" to "Messenger",
            "com.instagram.android" to "Instagram",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.gm" to "Gmail",
            "com.google.android.apps.messaging" to "SMS (Messages)",
            "com.android.mms" to "SMS",
            "com.facebook.katana" to "Facebook",
            "com.linkedin.android" to "LinkedIn",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.upwork.android.apps.main" to "Upwork",
            "com.fiverr.fiverr" to "Fiverr"
        )
    }
}
