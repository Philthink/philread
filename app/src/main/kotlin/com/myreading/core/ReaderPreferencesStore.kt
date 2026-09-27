package com.myreading.core

import android.content.Context

enum class ReaderThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorage(value: String?): ReaderThemeMode = entries.firstOrNull {
            it.storageValue == value
        } ?: SYSTEM
    }
}

enum class ReaderFontChoice(
    val storageValue: String,
    val displayName: String,
    val layoutFamily: String
) {
    SYSTEM("system", "系统默认", "sans-serif"),
    SONG("song", "宋体", "serif");

    companion object {
        fun fromStorage(value: String?): ReaderFontChoice = entries.firstOrNull {
            it.storageValue == value
        } ?: SYSTEM
    }
}

data class ReaderPreferences(
    val themeMode: ReaderThemeMode = ReaderThemeMode.SYSTEM,
    val fontChoice: ReaderFontChoice = ReaderFontChoice.SYSTEM,
    val restReminderMinutes: Int = 0
)

class ReaderPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ReaderPreferences = ReaderPreferences(
        themeMode = ReaderThemeMode.fromStorage(preferences.getString(KEY_THEME, null)),
        fontChoice = ReaderFontChoice.fromStorage(preferences.getString(KEY_FONT, null)),
        restReminderMinutes = preferences.getInt(KEY_REST_REMINDER, 0).takeIf {
            it in SUPPORTED_REMINDER_MINUTES
        } ?: 0
    )

    fun setTheme(mode: ReaderThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.storageValue).apply()
    }

    fun setFont(choice: ReaderFontChoice) {
        preferences.edit().putString(KEY_FONT, choice.storageValue).apply()
    }

    fun setRestReminder(minutes: Int) {
        require(minutes in SUPPORTED_REMINDER_MINUTES) { "Unsupported reminder interval: $minutes" }
        preferences.edit().putInt(KEY_REST_REMINDER, minutes).apply()
    }

    companion object {
        val SUPPORTED_REMINDER_MINUTES = setOf(0, 15, 30, 45, 60)

        private const val PREFERENCES_NAME = "reader-preferences"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"
        private const val KEY_REST_REMINDER = "rest-reminder-minutes"
    }
}
