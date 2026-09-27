package com.myreading.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPreferencesStoreTest {
    @Test
    fun unknownThemeFallsBackToSystem() {
        assertEquals(ReaderThemeMode.SYSTEM, ReaderThemeMode.fromStorage("unknown"))
    }

    @Test
    fun storedFontRestoresSongTypeface() {
        assertEquals(ReaderFontChoice.SONG, ReaderFontChoice.fromStorage("song"))
        assertEquals("serif", ReaderFontChoice.SONG.layoutFamily)
    }

    @Test
    fun reminderIntervalsContainOffAndHourlyOptions() {
        assertTrue(0 in ReaderPreferencesStore.SUPPORTED_REMINDER_MINUTES)
        assertTrue(60 in ReaderPreferencesStore.SUPPORTED_REMINDER_MINUTES)
    }
}
