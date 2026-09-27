package com.myreading.core

import org.w3c.dom.Element

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
