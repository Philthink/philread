package com.myreading.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.floor
import kotlin.math.max

data class LayoutSettings(
    val fontFamily: String = "Noto Serif CJK",
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.25f,
    val columnGap: Float = 18f,
    val pageWidth: Float = 360f,
    val pageHeight: Float = 640f,
    val marginTop: Float = 24f,
    val marginRight: Float = 24f,
    val marginBottom: Float = 24f,
    val marginLeft: Float = 24f
)

data class LayoutFingerprint(val value: String)

sealed class LayoutFragment {
    abstract val unitId: String

    data class Text(
        override val unitId: String,
        val text: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val lineHeight: Float,
        val combineUpright: Boolean = false
    ) : LayoutFragment()

    data class Ruby(
        override val unitId: String,
        val baseText: String,
        val annotation: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val lineHeight: Float
    ) : LayoutFragment()

    data class Image(
        override val unitId: String,
        val resourceHref: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    ) : LayoutFragment()
}

data class ColumnLayout(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fragments: List<LayoutFragment>
)

data class PageLayout(
    val index: Int,
    val columns: List<ColumnLayout>
)

data class PaginationResult(
    val chapterId: String,
    val fingerprint: String,
    val pages: List<PageLayout>
)

class LayoutCache(private val capacity: Int = 16) {
    private val cache = object : LinkedHashMap<String, PaginationResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PaginationResult>?): Boolean {
            return size > capacity
        }
    }

    @Synchronized fun get(key: String): PaginationResult? = cache[key]
    @Synchronized fun put(key: String, value: PaginationResult) { cache[key] = value }
}

class VerticalLayoutEngine(
    private val cache: LayoutCache = LayoutCache()
) {
    fun paginate(chapter: Chapter, stylesheet: Stylesheet, settings: LayoutSettings): PaginationResult {
        val fingerprint = fingerprint(chapter, stylesheet, settings)
        cache.get(fingerprint)?.let { return it }
        val pages = layoutChapter(chapter, stylesheet, settings)
        return PaginationResult(chapter.id, fingerprint, pages).also { cache.put(fingerprint, it) }
    }

    fun layoutChapter(chapter: Chapter, stylesheet: Stylesheet, settings: LayoutSettings): List<PageLayout> {
        val document = XmlSupport.parse(chapter.content)
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: return emptyList()

        val usableWidth = max(1f, settings.pageWidth - settings.marginLeft - settings.marginRight)
        val usableHeight = max(1f, settings.pageHeight - settings.marginTop - settings.marginBottom)
        val columnWidth = max(1f, settings.fontSize * 1.2f)
        val columnsPerPage = max(1, floor((usableWidth + settings.columnGap) / (columnWidth + settings.columnGap)).toInt())

        val pages = mutableListOf<PageLayout>()
        val fragments = mutableListOf<LayoutFragment>()
        var currentColumnIndex = 0
        var currentPageIndex = 0
        var usedHeight = 0f

        fun columnX(index: Int): Float {
            return settings.pageWidth - settings.marginRight - ((index + 1) * columnWidth) - (index * settings.columnGap)
        }

        fun flushColumn() {
            if (fragments.isEmpty()) return
            val page = pages.lastOrNull()
            val column = ColumnLayout(
                index = currentColumnIndex,
                x = columnX(currentColumnIndex),
                y = settings.marginTop,
                width = columnWidth,
                height = usableHeight,
                fragments = fragments.toList()
            )
            if (page == null || page.index != currentPageIndex) {
                pages += PageLayout(currentPageIndex, listOf(column))
            } else {
                pages[pages.lastIndex] = page.copy(columns = page.columns + column)
            }
            fragments.clear()
        }

        fun nextColumnOrPage() {
            flushColumn()
            if (currentColumnIndex + 1 >= columnsPerPage) {
                currentPageIndex += 1
                currentColumnIndex = 0
            } else {
                currentColumnIndex += 1
            }
            usedHeight = 0f
        }

        fun ensureSpace(advance: Float) {
            if (usedHeight > 0f && usedHeight + advance > usableHeight) {
                nextColumnOrPage()
            }
        }

        fun appendFragment(fragment: LayoutFragment, advance: Float) {
            ensureSpace(advance)
            fragments += fragment.copyAt(columnX(currentColumnIndex), settings.marginTop + usedHeight)
            usedHeight += advance
        }

        fun walk(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE -> {
                    val text = normalizeWhitespace(node.textContent)
                    if (text.isNotEmpty()) {
                        val unitId = "t${pages.size}_${currentColumnIndex}_${fragments.size}"
                        tokenize(text, false).forEach { token ->
                            val advance = tokenAdvance(token, false, settings)
                            appendFragment(LayoutFragment.Text(unitId, token, 0f, 0f, settings.fontSize, settings.lineHeight, false), advance)
                        }
                    }
                }
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    when (XmlSupport.localName(element).lowercase()) {
                        "br" -> {
                            usedHeight += settings.fontSize * settings.lineHeight
                            if (usedHeight > usableHeight) nextColumnOrPage()
                        }
                        "img" -> {
                            val href = element.getAttribute("src")
                            val advance = max(settings.fontSize * settings.lineHeight * 2f, 48f)
                            appendFragment(LayoutFragment.Image("i${pages.size}_${currentColumnIndex}_${fragments.size}", href, 0f, 0f, settings.fontSize * 2f, settings.fontSize * 2f), advance)
                        }
                        "ruby" -> {
                            val ruby = parseRuby(element)
                            val advance = settings.fontSize * settings.lineHeight
                            appendFragment(LayoutFragment.Ruby("r${pages.size}_${currentColumnIndex}_${fragments.size}", ruby.base, ruby.annotation, 0f, 0f, settings.fontSize * 0.9f, settings.lineHeight), advance)
                        }
                        "audio" -> Unit
                        else -> {
                            if (isBlock(element) && usedHeight > 0f) nextColumnOrPage()
                            for (child in XmlSupport.children(element)) walk(child)
                            if (isBlock(element) && usedHeight > 0f) nextColumnOrPage()
                        }
                    }
                }
            }
        }

        for (child in XmlSupport.children(body)) walk(child)
        flushColumn()
        return pages
    }

    private fun fingerprint(chapter: Chapter, stylesheet: Stylesheet, settings: LayoutSettings): String {
        val raw = buildString {
            append(chapter.id).append('|')
            append(settings.fontFamily).append('|')
            append(settings.fontSize).append('|')
            append(settings.lineHeight).append('|')
            append(settings.columnGap).append('|')
            append(settings.pageWidth).append('|')
            append(settings.pageHeight).append('|')
            append(settings.marginTop).append('|')
            append(settings.marginRight).append('|')
            append(settings.marginBottom).append('|')
            append(settings.marginLeft).append('|')
            append(stylesheet.rules.joinToString(separator = ";") { it.selector + it.declarations.joinToString { d -> "${d.property}:${d.value}" } })
            append('|')
            append(chapter.content.hashCode())
        }
        return raw.hashCode().toString(16)
    }

    private fun isBlock(element: Element): Boolean {
        return XmlSupport.localName(element).lowercase() in setOf("p", "div", "section", "article", "aside", "blockquote", "li", "ul", "ol", "table", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6")
    }

    private fun tokenize(text: String, combineUpright: Boolean): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val count = Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> {
                    tokens += String(Character.toChars(cp))
                    index += count
                }
                combineUpright && isAsciiAlnum(cp) -> {
                    val start = index
                    var consumed = 0
                    while (index < text.length && consumed < 4) {
                        val next = text.codePointAt(index)
                        if (!isAsciiAlnum(next)) break
                        index += Character.charCount(next)
                        consumed++
                    }
                    tokens += text.substring(start, index)
                }
                else -> {
                    tokens += String(Character.toChars(cp))
                    index += count
                }
            }
        }
        return tokens
    }

    private fun tokenAdvance(token: String, combineUpright: Boolean, settings: LayoutSettings): Float {
        if (token.isBlank()) return 0f
        if (combineUpright && token.length <= 4 && token.all { it.isLetterOrDigit() }) {
            return settings.fontSize * settings.lineHeight
        }
        val cp = token.codePointAt(0)
        return when {
            Character.isWhitespace(cp) -> settings.fontSize * settings.lineHeight * 0.33f
            isAsciiAlnum(cp) -> settings.fontSize * settings.lineHeight * 0.5f
            else -> settings.fontSize * settings.lineHeight
        }
    }

    private fun isAsciiAlnum(codePoint: Int): Boolean {
        return codePoint in '0'.code..'9'.code || codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code
    }

    private fun normalizeWhitespace(value: String?): String {
        return value?.replace('\u00A0', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    private data class RubyText(val base: String, val annotation: String)

    private fun parseRuby(element: Element): RubyText {
        val base = StringBuilder()
        val rt = StringBuilder()
        for (child in XmlSupport.children(element)) {
            if (child.nodeType == Node.TEXT_NODE) {
                base.append(normalizeWhitespace(child.textContent))
            } else if (child.nodeType == Node.ELEMENT_NODE) {
                val local = XmlSupport.localName(child).lowercase()
                when (local) {
                    "rt" -> rt.append(normalizeWhitespace(child.textContent))
                    "rp" -> Unit
                    else -> base.append(normalizeWhitespace(child.textContent))
                }
            }
        }
        return RubyText(base.toString(), rt.toString())
    }

    private fun LayoutFragment.copyAt(x: Float, y: Float): LayoutFragment {
        return when (this) {
            is LayoutFragment.Text -> copy(x = x, y = y)
            is LayoutFragment.Ruby -> copy(x = x, y = y)
            is LayoutFragment.Image -> copy(x = x, y = y)
        }
    }
}
