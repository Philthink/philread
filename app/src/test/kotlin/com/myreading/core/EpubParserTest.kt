package com.myreading.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EpubParserTest {

    @Test
    fun parseRealVerticalBook() {
        val epubFile = File("/Users/hubosen/Downloads/workspace/myReading/app/src/test/kotlin/com/myreading/core/epub/資治通鑑 -- [宋] 司馬光 編著 _ [元] 胡三省 音注 [[宋] 司馬光 編著 _ [元] 胡三省 音注] -- 1956 -- 中华书局 -- dd88d28022f890692250d7c12063cabe -- Anna's Archive.epub")

        if (!epubFile.exists()) {
            println("EPUB file not found, skipping test")
            return
        }

        val book = EpubParser().parse(epubFile)

        // Verify basic parsing
        assertNotNull("Book should not be null", book)
        assertNotNull("Metadata should not be null", book.metadata)
        assertTrue("Should have chapters", book.chapters.isNotEmpty())

        // Verify first chapter content is loaded
        val firstChapter = book.chapters[0]
        assertTrue("Chapter content should not be empty", firstChapter.content.isNotEmpty())

        // Verify layout engine can process the content
        val engine = VerticalLayoutEngine(LayoutCache(4))
        val settings = LayoutSettings(
            fontSize = 18f,
            lineHeight = 1.3f,
            pageWidth = 360f,
            pageHeight = 640f
        )

        val stylesheet = Stylesheet(emptyList(), emptySet())
        val pages = engine.layoutChapter(firstChapter, stylesheet, settings)
        assertTrue("Should produce pages", pages.isNotEmpty())

        // Verify pagination works
        val fingerprint = engine.paginate(firstChapter, stylesheet, settings).fingerprint
        assertNotNull("Fingerprint should be generated", fingerprint)
    }
}
