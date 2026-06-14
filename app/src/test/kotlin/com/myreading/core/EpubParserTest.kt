package com.myreading.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EpubParserTest {
    private val epubFile: File by lazy {
        val dir = File("src/test/kotlin/com/myreading/core/epub")
        require(dir.isDirectory) { "Missing test epub directory: ${dir.absolutePath}" }
        dir.listFiles()?.firstOrNull { it.extension.equals("epub", ignoreCase = true) }
            ?: error("No test epub found under ${dir.absolutePath}")
    }

    @Test
    fun parseZizhiTongjianEpub() {
        val book = EpubParser().parse(epubFile)

        assertEquals("資治通鑑", book.metadata.title)
        assertTrue(book.chapters.size > 100)
        assertTrue(book.navigation.roots.isNotEmpty())
        assertTrue(book.issues.isEmpty())

        val firstVolume = book.chapters.first { it.href.endsWith("part0004.html") }
        assertEquals("資治通鑑 ◇ 卷第一", firstVolume.title)
        assertTrue(firstVolume.content.isNotEmpty())
        assertTrue(firstVolume.referencedResourceHrefs.any { it.contains("0002.css") })
    }

    @Test
    fun layoutFirstVolumeProducesVerticalPages() {
        val book = EpubParser().parse(epubFile)
        val chapter = book.chapters.first { it.href.endsWith("part0004.html") }
        val css = book.resourcesByHref.values
            .first { it.type == ResourceType.CSS }
            .readText()
        val stylesheet = CssParser.parse(css, chapter.href)
        val engine = VerticalLayoutEngine(LayoutCache(4))
        val settings = LayoutSettings(
            fontSize = 20f,
            lineHeight = 1.25f,
            columnGap = 18f,
            pageWidth = 320f,
            pageHeight = 480f,
            marginTop = 20f,
            marginRight = 20f,
            marginBottom = 20f,
            marginLeft = 20f
        )

        val pages = engine.layoutChapter(chapter, stylesheet, settings)
        val textFragments = pages.flatMap { it.columns }.flatMap { it.fragments }.filterIsInstance<LayoutFragment.Text>()

        assertTrue(pages.isNotEmpty())
        assertTrue(textFragments.size > 100)
        assertTrue(textFragments.any { it.displayText == "威" && it.sourceText == "威" })
        assertTrue(textFragments.any { it.displayText == "臣" && it.sourceText == "臣" })
    }

    @Test
    fun annotationTextUsesSmallerFontThanBody() {
        val book = EpubParser().parse(epubFile)
        val chapter = book.chapters.first { it.href.endsWith("part0004.html") }
        val css = book.resourcesByHref.values.first { it.type == ResourceType.CSS }.readText()
        val stylesheet = CssParser.parse(css, chapter.href)
        val engine = VerticalLayoutEngine(LayoutCache(4))
        val settings = LayoutSettings(fontSize = 20f, pageWidth = 320f, pageHeight = 480f)

        val textFragments = engine.layoutChapter(chapter, stylesheet, settings)
            .flatMap { it.columns }
            .flatMap { it.fragments }
            .filterIsInstance<LayoutFragment.Text>()

        val fontSizes = textFragments.map { it.fontSize }.distinct().sorted()
        val bodySize = fontSizes.maxOrNull() ?: settings.fontSize
        val annotationSize = fontSizes.minOrNull() ?: settings.fontSize

        assertTrue(bodySize > annotationSize)
        assertTrue(annotationSize <= bodySize * 0.75f)
    }
}
