package com.myreading.core

import android.content.Context
import java.io.File

data class StoredBook(
    val id: String,
    val filePath: String,
    val title: String,
    val creator: String,
    val lastChapterIndex: Int,
    val lastPageIndex: Int,
    val lastOpenedAt: Long,
    val inShelf: Boolean,
    val inHistory: Boolean
)

class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader-library", Context.MODE_PRIVATE)

    fun books(): List<StoredBook> {
        return preferences.getStringSet(KEY_BOOK_IDS, emptySet()).orEmpty()
            .mapNotNull(::book)
            .sortedByDescending(StoredBook::lastOpenedAt)
    }

    fun book(id: String): StoredBook? {
        val path = preferences.getString(key(id, "path"), "").orEmpty()
        if (path.isBlank()) return null
        return StoredBook(
            id = id,
            filePath = path,
            title = preferences.getString(key(id, "title"), "").orEmpty(),
            creator = preferences.getString(key(id, "creator"), "").orEmpty(),
            lastChapterIndex = preferences.getInt(key(id, "chapter"), 0),
            lastPageIndex = preferences.getInt(key(id, "page"), 0),
            lastOpenedAt = preferences.getLong(key(id, "opened"), 0L),
            inShelf = preferences.getBoolean(key(id, "shelf"), false),
            inHistory = preferences.getBoolean(key(id, "history"), false)
        )
    }

    fun recordOpened(
        id: String,
        filePath: String,
        title: String,
        creator: String,
        defaultChapterIndex: Int
    ): StoredBook {
        val existing = book(id)
        val ids = preferences.getStringSet(KEY_BOOK_IDS, emptySet()).orEmpty().toMutableSet().apply { add(id) }
        preferences.edit()
            .putStringSet(KEY_BOOK_IDS, ids)
            .putString(key(id, "path"), filePath)
            .putString(key(id, "title"), title)
            .putString(key(id, "creator"), creator)
            .putInt(key(id, "chapter"), existing?.lastChapterIndex ?: defaultChapterIndex)
            .putInt(key(id, "page"), existing?.lastPageIndex ?: 0)
            .putLong(key(id, "opened"), System.currentTimeMillis())
            .putBoolean(key(id, "shelf"), existing?.inShelf ?: false)
            .putBoolean(key(id, "history"), true)
            .apply()
        return requireNotNull(book(id))
    }

    fun updateProgress(id: String, chapterIndex: Int, pageIndex: Int) {
        if (book(id) == null) return
        preferences.edit()
            .putInt(key(id, "chapter"), chapterIndex)
            .putInt(key(id, "page"), pageIndex)
            .putLong(key(id, "opened"), System.currentTimeMillis())
            .putBoolean(key(id, "history"), true)
            .apply()
    }

    fun setShelf(id: String, inShelf: Boolean) {
        if (book(id) == null) return
        preferences.edit().putBoolean(key(id, "shelf"), inShelf).apply()
        removeUnusedRecord(id)
    }

    fun removeHistory(id: String) {
        if (book(id) == null) return
        preferences.edit().putBoolean(key(id, "history"), false).apply()
        removeUnusedRecord(id)
    }

    private fun removeUnusedRecord(id: String) {
        val record = book(id) ?: return
        if (record.inShelf || record.inHistory) return
        File(record.filePath).delete()
        val ids = preferences.getStringSet(KEY_BOOK_IDS, emptySet()).orEmpty().toMutableSet().apply { remove(id) }
        val editor = preferences.edit().putStringSet(KEY_BOOK_IDS, ids)
        for (suffix in FIELD_SUFFIXES) editor.remove(key(id, suffix))
        editor.apply()
    }

    private fun key(id: String, suffix: String): String = "book-$id-$suffix"

    private companion object {
        const val KEY_BOOK_IDS = "book-ids"
        val FIELD_SUFFIXES = listOf("path", "title", "creator", "chapter", "page", "opened", "shelf", "history")
    }
}
