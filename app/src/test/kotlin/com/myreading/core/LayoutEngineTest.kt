package com.myreading.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {
    private val engine = VerticalLayoutEngine(LayoutCache(4))
    private val stylesheet = Stylesheet(emptyList(), emptySet())
    private val settings = LayoutSettings(
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

    @Test
    fun remAndMalformedFontSizesDoNotCrashPagination() {
        val fragments = layoutTextFragments(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p style="font-size:2em"><span style="font-size:1.5rem">甲</span><span style="font-size:bad%">乙</span><span style="font-size:badem">丙</span></p></body></html>"""
        )
        assertEquals(30f, fragments.first { it.sourceText == "甲" }.fontSize, 0.01f)
        assertEquals(40f, fragments.first { it.sourceText == "乙" }.fontSize, 0.01f)
        assertEquals(40f, fragments.first { it.sourceText == "丙" }.fontSize, 0.01f)
    }

    @Test
    fun largeHeadingsReserveTheirActualHeightAndWidth() {
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1 style="font-size:3em">甲乙丙丁戊己庚辛壬癸</h1><h2 style="font-size:2em">副題</h2><p>正文</p></body></html>"""
        for (orientation in ReadingOrientation.entries) {
            val pages = layoutPages(html, orientation = orientation)
            assertTrue(pages.isNotEmpty())
            pages.forEach { page ->
                page.columns.zipWithNext().forEach { (a, b) ->
                    if (orientation == ReadingOrientation.HORIZONTAL) {
                        assertTrue(b.y >= a.y + a.height)
                    } else {
                        assertTrue(b.x + b.width <= a.x)
                    }
                }
            }
        }
    }

    @Test
    fun horizontalParagraphIndentAppliesOnlyToFirstLine() {
        val pages = layoutPages(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>甲${"乙".repeat(30)}</p><p>丙丁</p></body></html>""",
            orientation = ReadingOrientation.HORIZONTAL
        )
        val lines = pages.flatMap { it.columns }
        val fragments = lines.flatMap { it.fragments }.filterIsInstance<LayoutFragment.Text>()
        assertEquals(settings.marginLeft + 40f, fragments.first { it.sourceText == "甲" }.x, 0.01f)
        assertEquals(settings.marginLeft + 40f, fragments.first { it.sourceText == "丙" }.x, 0.01f)
        assertEquals(settings.marginLeft, (lines[1].fragments.first() as LayoutFragment.Text).x, 0.01f)
        assertTrue(fragments.first { it.sourceText == "丙" }.y > fragments.first().y)
    }

    @Test
    fun rubyPositionRightPlacesAnnotationBesideBase() {
        val fragments = layoutFragments(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p><ruby>漢字<rt>kanji</rt></ruby></p>
              </body>
            </html>
            """.trimIndent()
        )

        val ruby = fragments.filterIsInstance<LayoutFragment.Ruby>().first()

        assertEquals(RubyPosition.Right, ruby.rubyPosition)
        assertEquals(2, ruby.baseFragments.size)
        assertTrue(ruby.annotationFontSize <= settings.fontSize * 0.62f)
        assertTrue(ruby.annotationFontSize >= settings.fontSize * 0.42f)
        assertTrue(ruby.annotationX > ruby.x)
        assertTrue(ruby.annotationY >= ruby.y)
        assertTrue(ruby.flowAdvance >= settings.fontSize * settings.lineHeight)
    }

    @Test
    fun rubyPositionOverAndUnderRespectStyle() {
        val overRuby = layoutRubyFragment(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p><ruby style="ruby-position: over;">漢<rt>kan</rt></ruby></p>
              </body>
            </html>
            """.trimIndent()
        )
        val underRuby = layoutRubyFragment(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p><ruby style="ruby-position: under;">漢<rt>kan</rt></ruby></p>
              </body>
            </html>
            """.trimIndent()
        )

        assertEquals(RubyPosition.Over, overRuby.rubyPosition)
        assertEquals(RubyPosition.Under, underRuby.rubyPosition)
        assertTrue(overRuby.annotationY < overRuby.y)
        assertTrue(underRuby.annotationY > underRuby.y)
    }

    @Test
    fun textOrientationUprightKeepsAsciiFullHeight() {
        val upright = layoutTextFragments(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p style="text-orientation: upright;">AB</p>
              </body>
            </html>
            """.trimIndent()
        )
        val mixed = layoutTextFragments(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p>AB</p>
              </body>
            </html>
            """.trimIndent()
        )

        assertEquals(2, upright.size)
        assertEquals(2, mixed.size)
        assertTrue(upright[1].y - upright[0].y > mixed[1].y - mixed[0].y)
    }

    @Test
    fun verticalPunctuationMapsAsciiMarks() {
        val fragments = layoutTextFragments(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p>,.!?</p>
              </body>
            </html>
            """.trimIndent()
        )

        val mapped = fragments.map { it.displayText }
        assertEquals(listOf("︐", "︒", "︕", "︖"), mapped)
    }

    @Test
    fun arabicDigitsStayAsOneReadableToken() {
        val fragments = layoutTextFragments(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;"><p>编号123完</p></body>
            </html>
            """.trimIndent()
        )

        val number = fragments.first { it.sourceText == "123" }
        assertEquals("123", number.displayText)
        assertEquals(1, fragments.count { it.sourceText == "123" })
    }

    @Test
    fun horizontalOrientationLaysTextAcrossTheLine() {
        val pages = layoutPages(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p>甲乙丙</p></body>
            </html>
            """.trimIndent(),
            title = "",
            orientation = ReadingOrientation.HORIZONTAL
        )
        val fragments = pages.flatMap { it.columns }.flatMap { it.fragments }
            .filterIsInstance<LayoutFragment.Text>()

        assertEquals(listOf("甲", "乙", "丙"), fragments.map { it.displayText })
        assertTrue(fragments[1].x > fragments[0].x)
        assertEquals(fragments[0].y, fragments[1].y, 0.01f)
    }

    @Test
    fun paragraphsStartInSeparateIndentedColumns() {
        val pages = layoutPages(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;">
                <p>甲乙</p><p>丙丁</p>
              </body>
            </html>
            """.trimIndent(),
            title = ""
        )
        val fragments = pages.flatMap { it.columns }.flatMap { it.fragments }
            .filterIsInstance<LayoutFragment.Text>()
        val firstParagraph = fragments.first { it.sourceText == "甲" }
        val secondParagraph = fragments.first { it.sourceText == "丙" }

        assertTrue(firstParagraph.x > secondParagraph.x)
        assertTrue(firstParagraph.y >= settings.marginTop + settings.fontSize * settings.lineHeight * 2f)
        assertTrue(secondParagraph.y >= settings.marginTop + settings.fontSize * settings.lineHeight * 2f)
    }

    @Test
    fun chapterTitleUsesLargerIndependentColumn() {
        val pages = layoutPages(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body style="writing-mode: vertical-rl;"><p>正文</p></body>
            </html>
            """.trimIndent(),
            title = "卷一"
        )
        val fragments = pages.flatMap { it.columns }.flatMap { it.fragments }
            .filterIsInstance<LayoutFragment.Text>()
        val title = fragments.first { it.unitId.startsWith("chapter-title") }
        val body = fragments.first { it.sourceText == "正" }

        assertTrue(title.fontSize > body.fontSize)
        assertTrue(title.x > body.x)
    }

    private fun layoutFragments(html: String): List<LayoutFragment> {
        return layoutPages(html).flatMap { it.columns }.flatMap { it.fragments }
    }

    private fun layoutPages(
        html: String,
        title: String = "",
        orientation: ReadingOrientation = ReadingOrientation.VERTICAL
    ): List<PageLayout> {
        val chapter = Chapter(
            id = "chap",
            href = "chapter.xhtml",
            title = title,
            order = 0,
            linear = true,
            content = html.encodeToByteArray(),
            referencedResourceHrefs = emptySet()
        )
        return engine.layoutChapter(chapter, stylesheet, settings.copy(orientation = orientation))
    }

    private fun layoutRubyFragment(html: String): LayoutFragment.Ruby {
        return layoutFragments(html).filterIsInstance<LayoutFragment.Ruby>().first()
    }

    private fun layoutTextFragments(html: String): List<LayoutFragment.Text> {
        return layoutFragments(html).filterIsInstance<LayoutFragment.Text>()
    }
}
