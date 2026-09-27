package com.myreading.core

import org.w3c.dom.Element

data class ReadingPosition(
    val chapterIndex: Int,
    val pageIndex: Int
)

fun Book.initialReadableChapterIndex(): Int {
    val linearChapter = chapters.indexOfFirst { it.linear && it.hasReadableText() }
    if (linearChapter >= 0) return linearChapter

    val anyChapter = chapters.indexOfFirst(Chapter::hasReadableText)
    return anyChapter.coerceAtLeast(0)
}

fun Chapter.hasReadableText(): Boolean {
    return runCatching {
        val document = XmlSupport.parse(content)
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element
        body?.textContent?.isNotBlank() == true
    }.getOrDefault(false)
}

fun ReadingPosition.next(
    chapterCount: Int,
    pageCountAt: (Int) -> Int
): ReadingPosition {
    val currentPageCount = pageCountAt(chapterIndex)
    if (pageIndex + 1 < currentPageCount) {
        return copy(pageIndex = pageIndex + 1)
    }

    for (index in chapterIndex + 1 until chapterCount) {
        if (pageCountAt(index) > 0) return ReadingPosition(index, 0)
    }
    return copy(pageIndex = (currentPageCount - 1).coerceAtLeast(0))
}

fun ReadingPosition.previous(
    pageCountAt: (Int) -> Int
): ReadingPosition {
    if (pageIndex > 0) return copy(pageIndex = pageIndex - 1)

    for (index in chapterIndex - 1 downTo 0) {
        val pageCount = pageCountAt(index)
        if (pageCount > 0) return ReadingPosition(index, pageCount - 1)
    }
    return copy(pageIndex = 0)
}
