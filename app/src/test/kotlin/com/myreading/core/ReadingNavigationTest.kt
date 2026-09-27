package com.myreading.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadingNavigationTest {
    @Test
    fun skipsSvgCoverAndStartsAtReadableChapter() {
        val fixtureDirectory = File("src/test/kotlin/com/myreading/core/epub")
        val epubFile = requireNotNull(
            fixtureDirectory.listFiles()?.firstOrNull { it.extension.equals("epub", ignoreCase = true) }
        ) { "EPUB fixture not found in ${fixtureDirectory.absolutePath}" }

        val book = EpubParser().parse(epubFile)
        val initialIndex = book.initialReadableChapterIndex()
        val initialChapter = book.chapters[initialIndex]
        val pages = VerticalLayoutEngine(LayoutCache(4)).layoutChapter(
            chapter = initialChapter,
            stylesheet = Stylesheet(emptyList(), emptySet()),
            settings = LayoutSettings(pageWidth = 360f, pageHeight = 640f)
        )

        assertFalse(book.chapters.first().hasReadableText())
        assertTrue(initialIndex > 0)
        assertTrue(initialChapter.hasReadableText())
        assertTrue(pages.isNotEmpty())
    }
}
