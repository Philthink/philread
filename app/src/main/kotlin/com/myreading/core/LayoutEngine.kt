package com.myreading.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.max

data class LayoutSettings(
    val fontFamily: String = "Noto Serif CJK",
    val orientation: ReadingOrientation = ReadingOrientation.VERTICAL,
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

enum class ReadingOrientation {
    VERTICAL,
    HORIZONTAL
}

data class LayoutFingerprint(val value: String)

enum class RubyPosition {
    Right,
    Over,
    Under
}

data class InlineStyleState(
    val combineUpright: Boolean = false,
    val combineUprightDigitsOnly: Boolean = false,
    val combineUprightLimit: Int = 4,
    val rubyPosition: RubyPosition? = null,
    val textOrientationUpright: Boolean = false,
    val writingModeVertical: Boolean = true,
    val fontSize: Float? = null,
    val fontFamily: String? = null
)

sealed class LayoutFragment {
    abstract val unitId: String

    data class Text(
        override val unitId: String,
        val sourceText: String,
        val displayText: String,
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val lineHeight: Float,
        val combineUpright: Boolean = false,
        val punctuation: Boolean = false,
        val textOrientationUpright: Boolean = false
    ) : LayoutFragment()

    data class Ruby(
        override val unitId: String,
        val baseFragments: List<Text>,
        val annotationText: String,
        val annotationDisplayText: String,
        val x: Float,
        val y: Float,
        val baseFontSize: Float,
        val baseLineHeight: Float,
        val annotationFontSize: Float,
        val annotationLineHeight: Float,
        val annotationX: Float,
        val annotationY: Float,
        val rubyPosition: RubyPosition = RubyPosition.Right,
        val flowAdvance: Float
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

    @Synchronized
    fun get(key: String): PaginationResult? = cache[key]

    @Synchronized
    fun put(key: String, value: PaginationResult) {
        cache[key] = value
    }
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
        if (settings.orientation == ReadingOrientation.HORIZONTAL) {
            return layoutChapterHorizontal(chapter, stylesheet, settings)
        }
        val document = XmlSupport.parse(chapter.content)
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: return emptyList()

        val usableWidth = max(1f, settings.pageWidth - settings.marginLeft - settings.marginRight)
        val usableHeight = max(1f, settings.pageHeight - settings.marginTop - settings.marginBottom)
        val defaultColumnWidth = max(1f, settings.fontSize * 1.2f)
        var columnWidth = defaultColumnWidth
        var usedPageWidth = 0f

        val pages = mutableListOf<PageLayout>()
        var currentColumns = mutableListOf<ColumnLayout>()
        var currentFragments = mutableListOf<LayoutFragment>()
        var pageIndex = 0
        var columnIndex = 0
        var usedHeight = 0f

        fun columnX(): Float {
            return settings.pageWidth - settings.marginRight - usedPageWidth - columnWidth
        }

        fun flushColumn() {
            if (currentFragments.isEmpty()) return
            currentColumns += ColumnLayout(
                index = columnIndex,
                x = columnX(),
                y = settings.marginTop,
                width = columnWidth,
                height = usableHeight,
                fragments = currentFragments.toList()
            )
            currentFragments = mutableListOf()
        }

        fun flushPage() {
            if (currentColumns.isEmpty()) return
            pages += PageLayout(pageIndex, currentColumns.toList())
            currentColumns = mutableListOf()
        }

        fun newColumnOrPage() {
            flushColumn()
            usedPageWidth += columnWidth + settings.columnGap
            columnWidth = defaultColumnWidth
            if (usedPageWidth + columnWidth > usableWidth) {
                flushPage()
                pageIndex += 1
                columnIndex = 0
                usedPageWidth = 0f
            } else {
                columnIndex += 1
            }
            usedHeight = 0f
        }

        fun ensureSpace(advance: Float) {
            if (usedHeight > 0f && usedHeight + advance > usableHeight) {
                newColumnOrPage()
            }
        }

        fun append(fragment: LayoutFragment, advance: Float) {
            ensureSpace(advance)
            val requiredWidth = when (fragment) {
                is LayoutFragment.Text -> max(fragment.fontSize * 1.2f, fragment.displayText.length * fragment.fontSize * if (fragment.displayText.all { it in '0'..'9' }) 0.6f else 0f)
                is LayoutFragment.Ruby -> fragment.baseFontSize * 1.8f
                is LayoutFragment.Image -> fragment.width
            }
            val previousX = columnX()
            if (max(columnWidth, requiredWidth) + usedPageWidth > usableWidth && currentColumns.isNotEmpty()) {
                flushPage()
                pageIndex += 1
                columnIndex = 0
                usedPageWidth = 0f
            }
            columnWidth = max(columnWidth, requiredWidth)
            val shift = columnX() - previousX
            if (shift != 0f) currentFragments = currentFragments.map { existing ->
                when (existing) {
                    is LayoutFragment.Text -> existing.copy(x = existing.x + shift)
                    is LayoutFragment.Image -> existing.copy(x = existing.x + shift)
                    is LayoutFragment.Ruby -> existing.positionAt(existing.x + shift, existing.y)
                }
            }.toMutableList()
            val positioned = fragment.positionAt(columnX(), settings.marginTop + usedHeight)
            currentFragments += positioned
            usedHeight += advance
        }

        fun walk(node: Node, inherited: InlineStyleState) {
            when (node.nodeType) {
                Node.TEXT_NODE -> {
                    val text = normalizeWhitespace(node.textContent)
                    if (text.isNotEmpty()) {
                        val tokens = VerticalTypography.tokenize(text, inherited)
                        val fontSize = inherited.resolvedFontSize(settings)
                        tokens.forEachIndexed { index, token ->
                            val unitId = "t$pageIndex$columnIndex${currentFragments.size}$index"
                            append(
                                LayoutFragment.Text(
                                    unitId = unitId,
                                    sourceText = token.source,
                                    displayText = token.display,
                                    x = 0f,
                                    y = 0f,
                                    fontSize = fontSize,
                                    lineHeight = settings.lineHeight,
                                    combineUpright = token.combineUpright,
                                    punctuation = token.punctuation,
                                    textOrientationUpright = token.textOrientationUpright
                                ),
                                token.advance(settings, fontSize)
                            )
                        }
                    }
                }

                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val localName = XmlSupport.localName(element).lowercase()
                    val nextState = resolveStyle(element, stylesheet, inherited, settings)
                    when (localName) {
                        "br" -> {
                            val fontSize = inherited.resolvedFontSize(settings)
                            usedHeight += fontSize * settings.lineHeight
                            if (usedHeight > usableHeight) newColumnOrPage()
                        }

                        "img" -> {
                            val href = element.getAttribute("src")
                            val fontSize = inherited.resolvedFontSize(settings)
                            val advance = max(fontSize * settings.lineHeight * 2f, fontSize * 2f)
                            append(
                                LayoutFragment.Image(
                                    unitId = "i$pageIndex$columnIndex${currentFragments.size}",
                                    resourceHref = href,
                                    x = 0f,
                                    y = 0f,
                                    width = fontSize * 2f,
                                    height = fontSize * 2f
                                ),
                                advance
                            )
                        }

                        "ruby" -> {
                            val ruby = layoutRuby(
                                element = element,
                                inherited = nextState,
                                settings = settings,
                                x = columnX(),
                                y = settings.marginTop + usedHeight,
                                unitId = "r$pageIndex$columnIndex${currentFragments.size}"
                            )
                            append(ruby.fragment, ruby.advance)
                        }

                        "audio" -> Unit

                        else -> {
                            when {
                                isHeadingElement(element) -> {
                                    if (currentFragments.isNotEmpty() || usedHeight > 0f) newColumnOrPage()
                                    val scale = if (localName == "h1") 1.6f else 1.4f
                                    val headingState = nextState.copy(
                                        fontSize = max(nextState.resolvedFontSize(settings), settings.fontSize * scale)
                                    )
                                    for (child in XmlSupport.children(element)) {
                                        walk(child, headingState)
                                    }
                                    if (currentFragments.isNotEmpty()) newColumnOrPage()
                                }

                                isParagraphElement(element) -> {
                                    if (currentFragments.isNotEmpty() || usedHeight > 0f) newColumnOrPage()
                                    val fontSize = nextState.resolvedFontSize(settings)
                                    usedHeight = (fontSize * settings.lineHeight * 2f).coerceAtMost(usableHeight * 0.3f)
                                    for (child in XmlSupport.children(element)) {
                                        walk(child, nextState)
                                    }
                                }

                                else -> {
                                    val blockElement = isBlockElement(element)
                                    val fontSize = nextState.resolvedFontSize(settings)
                                    if (blockElement && usedHeight > 0f) {
                                        usedHeight += fontSize * settings.lineHeight * 0.5f
                                        if (usedHeight > usableHeight) newColumnOrPage()
                                    }
                                    for (child in XmlSupport.children(element)) {
                                        walk(child, nextState)
                                    }
                                    if (blockElement && usedHeight > 0f) {
                                        usedHeight += fontSize * settings.lineHeight * 0.35f
                                        if (usedHeight > usableHeight) newColumnOrPage()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val rootState = resolveStyle(body, stylesheet, InlineStyleState(), settings)
        val hasBodyHeading = (1..6).any { body.getElementsByTagNameNS("*", "h$it").length > 0 }
        if (!hasBodyHeading && chapter.title.isNotBlank()) {
            val titleState = rootState.copy(fontSize = settings.fontSize * 1.6f)
            val titleTokens = VerticalTypography.tokenize(chapter.title, titleState)
            titleTokens.forEachIndexed { index, token ->
                append(
                    LayoutFragment.Text(
                        unitId = "chapter-title-$index",
                        sourceText = token.source,
                        displayText = token.display,
                        x = 0f,
                        y = 0f,
                        fontSize = titleState.resolvedFontSize(settings),
                        lineHeight = settings.lineHeight,
                        combineUpright = token.combineUpright,
                        punctuation = token.punctuation,
                        textOrientationUpright = token.textOrientationUpright
                    ),
                    token.advance(settings, titleState.resolvedFontSize(settings))
                )
            }
            if (currentFragments.isNotEmpty()) newColumnOrPage()
        }
        for (child in XmlSupport.children(body)) {
            walk(child, rootState)
        }

        flushColumn()
        flushPage()
        return pages
    }

    private fun layoutChapterHorizontal(
        chapter: Chapter,
        stylesheet: Stylesheet,
        settings: LayoutSettings
    ): List<PageLayout> {
        val document = XmlSupport.parse(chapter.content)
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: return emptyList()
        val usableWidth = max(1f, settings.pageWidth - settings.marginLeft - settings.marginRight)
        val usableHeight = max(1f, settings.pageHeight - settings.marginTop - settings.marginBottom)
        val lineHeight = settings.fontSize * settings.lineHeight
        var currentLineHeight = lineHeight
        var usedPageHeight = 0f
        val pages = mutableListOf<PageLayout>()
        var currentLines = mutableListOf<ColumnLayout>()
        var currentFragments = mutableListOf<LayoutFragment>()
        var pageIndex = 0
        var lineIndex = 0
        var usedWidth = 0f

        fun flushLine() {
            if (currentFragments.isEmpty()) return
            currentLines += ColumnLayout(
                index = lineIndex,
                x = settings.marginLeft,
                y = settings.marginTop + usedPageHeight,
                width = usableWidth,
                height = currentLineHeight,
                fragments = currentFragments.toList()
            )
            currentFragments = mutableListOf()
        }

        fun flushPage() {
            flushLine()
            if (currentLines.isEmpty()) return
            pages += PageLayout(pageIndex, currentLines.toList())
            currentLines = mutableListOf()
        }

        fun newLine() {
            flushLine()
            usedPageHeight += currentLineHeight
            currentLineHeight = lineHeight
            if (usedPageHeight + currentLineHeight > usableHeight) {
                flushPage()
                pageIndex += 1
                lineIndex = 0
                usedPageHeight = 0f
            } else {
                lineIndex += 1
            }
            usedWidth = 0f
        }

        fun appendText(text: String, style: InlineStyleState, fontSize: Float = style.resolvedFontSize(settings)) {
            VerticalTypography.tokenize(text, style, vertical = false).forEachIndexed { index, token ->
                val advance = token.advanceHorizontal(fontSize)
                if (usedWidth > 0f && usedWidth + advance > usableWidth) newLine()
                val requiredHeight = fontSize * max(settings.lineHeight, 1.35f)
                if (usedPageHeight + max(currentLineHeight, requiredHeight) > usableHeight && currentLines.isNotEmpty()) {
                    val shift = usedPageHeight
                    val pending = currentFragments
                    currentFragments = mutableListOf()
                    flushPage()
                    pageIndex += 1
                    lineIndex = 0
                    usedPageHeight = 0f
                    currentFragments = pending.map {
                        if (it is LayoutFragment.Text) it.copy(y = it.y - shift) else it
                    }.toMutableList()
                }
                currentLineHeight = max(currentLineHeight, requiredHeight)
                currentFragments += LayoutFragment.Text(
                    unitId = "h$pageIndex$lineIndex${currentFragments.size}$index",
                    sourceText = token.source,
                    displayText = token.display,
                    x = settings.marginLeft + usedWidth,
                    y = settings.marginTop + usedPageHeight,
                    fontSize = fontSize,
                    lineHeight = settings.lineHeight,
                    combineUpright = false,
                    punctuation = false,
                    textOrientationUpright = true
                )
                usedWidth += advance
            }
        }

        fun walk(node: Node, inherited: InlineStyleState) {
            when (node.nodeType) {
                Node.TEXT_NODE -> appendText(normalizeWhitespace(node.textContent), inherited)
                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val localName = XmlSupport.localName(element).lowercase()
                    val nextState = resolveStyle(element, stylesheet, inherited, settings)
                    when {
                        localName == "br" -> newLine()
                        localName == "img" -> {
                            val size = nextState.resolvedFontSize(settings) * 2f
                            if (usedWidth > 0f && usedWidth + size > usableWidth) newLine()
                            currentFragments += LayoutFragment.Image(
                                unitId = "hi$pageIndex$lineIndex${currentFragments.size}",
                                resourceHref = element.getAttribute("src"),
                                x = settings.marginLeft + usedWidth,
                                y = settings.marginTop + usedPageHeight,
                                width = size,
                                height = size
                            )
                            usedWidth += size
                            currentLineHeight = max(currentLineHeight, size)
                        }
                        isHeadingElement(element) -> {
                            if (currentFragments.isNotEmpty()) newLine()
                            val scale = if (localName == "h1") 1.6f else 1.4f
                            val headingState = nextState.copy(fontSize = max(nextState.resolvedFontSize(settings), settings.fontSize * scale))
                            for (child in XmlSupport.children(element)) walk(child, headingState)
                            newLine()
                        }
                        isParagraphElement(element) -> {
                            if (currentFragments.isNotEmpty()) newLine()
                            usedWidth = (nextState.resolvedFontSize(settings) * 2f).coerceAtMost(usableWidth * 0.3f)
                            for (child in XmlSupport.children(element)) walk(child, nextState)
                            newLine()
                        }
                        isBlockElement(element) -> {
                            if (currentFragments.isNotEmpty()) newLine()
                            for (child in XmlSupport.children(element)) walk(child, nextState)
                            if (currentFragments.isNotEmpty()) newLine()
                        }
                        else -> for (child in XmlSupport.children(element)) walk(child, nextState)
                    }
                }
            }
        }

        val rootState = resolveStyle(body, stylesheet, InlineStyleState(), settings)
        val hasBodyHeading = (1..6).any { body.getElementsByTagNameNS("*", "h$it").length > 0 }
        if (!hasBodyHeading && chapter.title.isNotBlank()) {
            appendText(chapter.title, rootState.copy(fontSize = settings.fontSize * 1.6f), settings.fontSize * 1.6f)
            newLine()
        }
        for (child in XmlSupport.children(body)) walk(child, rootState)
        flushPage()
        return pages
    }

    private fun fingerprint(chapter: Chapter, stylesheet: Stylesheet, settings: LayoutSettings): String {
        val raw = buildString {
            append(chapter.id).append('|')
            append(settings.fontFamily).append('|')
            append(settings.orientation).append('|')
            append(settings.fontSize).append('|')
            append(settings.lineHeight).append('|')
            append(settings.columnGap).append('|')
            append(settings.pageWidth).append('|')
            append(settings.pageHeight).append('|')
            append(settings.marginTop).append('|')
            append(settings.marginRight).append('|')
            append(settings.marginBottom).append('|')
            append(settings.marginLeft).append('|')
            append(stylesheet.rules.joinToString(separator = ";") { rule ->
                rule.selector + rule.declarations.joinToString(separator = ",") { declaration ->
                    "${declaration.property}:${declaration.value}"
                }
            })
            append('|')
            append(chapter.content.contentHashCode())
        }
        return raw.hashCode().toString(16)
    }

    private fun isBlockElement(element: Element): Boolean {
        return XmlSupport.localName(element).lowercase() in setOf(
            "p", "div", "section", "article", "aside", "blockquote",
            "li", "ul", "ol", "table", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6"
        )
    }

    private fun isHeadingElement(element: Element): Boolean {
        return XmlSupport.localName(element).lowercase() in setOf("h1", "h2", "h3", "h4", "h5", "h6")
    }

    private fun isParagraphElement(element: Element): Boolean {
        return XmlSupport.localName(element).lowercase() in setOf("p", "li", "blockquote")
    }

    private fun normalizeWhitespace(value: String?): String {
        return value
            ?.replace('\u00A0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private data class RubyText(
        val baseText: String,
        val annotationText: String,
        val rubyPosition: RubyPosition
    )

    private fun parseRuby(element: Element, inherited: InlineStyleState): RubyText {
        val base = StringBuilder()
        val annotation = StringBuilder()
        for (child in XmlSupport.children(element)) {
            when {
                child.nodeType == Node.TEXT_NODE -> {
                    base.append(normalizeWhitespace(child.textContent))
                }

                child.nodeType == Node.ELEMENT_NODE -> {
                    val local = XmlSupport.localName(child).lowercase()
                    when (local) {
                        "rt" -> annotation.append(normalizeWhitespace(child.textContent))
                        "rp" -> Unit
                        "rb" -> base.append(normalizeWhitespace(child.textContent))
                        else -> base.append(normalizeWhitespace(child.textContent))
                    }
                }
            }
        }

        return RubyText(
            baseText = normalizeWhitespace(base.toString()),
            annotationText = normalizeWhitespace(annotation.toString()),
            rubyPosition = inherited.rubyPosition ?: if (inherited.writingModeVertical) RubyPosition.Right else RubyPosition.Over
        )
    }

    private data class RubyLayout(
        val fragment: LayoutFragment.Ruby,
        val advance: Float
    )

    private fun layoutRuby(
        element: Element,
        inherited: InlineStyleState,
        settings: LayoutSettings,
        x: Float,
        y: Float,
        unitId: String
    ): RubyLayout {
        val ruby = parseRuby(element, inherited)
        val baseTokens = VerticalTypography.tokenize(ruby.baseText, inherited)
        val baseFontSize = inherited.resolvedFontSize(settings)
        val baseFragments = mutableListOf<LayoutFragment.Text>()
        var baseAdvance = 0f
        for ((index, token) in baseTokens.withIndex()) {
            val fragment = LayoutFragment.Text(
                unitId = "$unitId-b$index",
                sourceText = token.source,
                displayText = token.display,
                x = x,
                y = y + baseAdvance,
                fontSize = baseFontSize,
                lineHeight = settings.lineHeight,
                combineUpright = token.combineUpright,
                punctuation = token.punctuation,
                textOrientationUpright = token.textOrientationUpright
            )
            baseFragments += fragment
            baseAdvance += token.advance(settings, baseFontSize)
        }

        val annotationFontSize = rubyAnnotationFontSize(ruby.annotationText, settings, baseFontSize)
        val annotationLineHeight = max(settings.lineHeight, 1.05f)
        val annotationHeight = annotationFontSize * annotationLineHeight
        val gap = max(2f, baseFontSize * 0.12f)
        val annotationX = when (ruby.rubyPosition) {
            RubyPosition.Right -> x + baseFontSize * 0.78f
            RubyPosition.Over -> x
            RubyPosition.Under -> x
        }
        val annotationY = when (ruby.rubyPosition) {
            RubyPosition.Right -> y + max(0f, (baseAdvance - annotationHeight) / 2f)
            RubyPosition.Over -> y - annotationHeight - gap
            RubyPosition.Under -> y + baseAdvance + gap
        }
        val flowAdvance = max(baseAdvance, baseFontSize * settings.lineHeight)

        return RubyLayout(
            fragment = LayoutFragment.Ruby(
                unitId = unitId,
                baseFragments = baseFragments,
                annotationText = ruby.annotationText,
                annotationDisplayText = ruby.annotationText,
                x = x,
                y = y,
                baseFontSize = baseFontSize,
                baseLineHeight = settings.lineHeight,
                annotationFontSize = annotationFontSize,
                annotationLineHeight = annotationLineHeight,
                annotationX = annotationX,
                annotationY = annotationY,
                rubyPosition = ruby.rubyPosition,
                flowAdvance = max(flowAdvance, annotationHeight + gap)
            ),
            advance = max(flowAdvance, annotationHeight + gap)
        )
    }

    private fun rubyAnnotationFontSize(annotation: String, settings: LayoutSettings, baseFontSize: Float): Float {
        if (annotation.isBlank()) {
            return baseFontSize * 0.62f
        }
        val defaultSize = baseFontSize * 0.62f
        val maxSideWidth = max(baseFontSize * 1.45f, settings.columnGap + baseFontSize * 0.35f)
        val widthAtUnitSize = estimateHorizontalTextWidth(annotation, 1f).coerceAtLeast(0.01f)
        val fitSize = maxSideWidth / widthAtUnitSize
        return fitSize.coerceIn(baseFontSize * 0.42f, defaultSize)
    }

    private fun estimateHorizontalTextWidth(text: String, fontSize: Float): Float {
        if (text.isBlank()) {
            return 0f
        }
        var width = 0f
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += when {
                Character.isWhitespace(codePoint) -> fontSize * 0.32f
                VerticalTypography.isAsciiAlnum(codePoint) -> fontSize * 0.56f
                VerticalTypography.isHorizontalPunctuation(codePoint) -> fontSize * 0.48f
                else -> fontSize * 0.9f
            }
            index += Character.charCount(codePoint)
        }
        return width
    }

    private fun resolveStyle(
        element: Element,
        stylesheet: Stylesheet,
        parent: InlineStyleState,
        settings: LayoutSettings
    ): InlineStyleState {
        var state = parent

        for (rule in stylesheet.rules) {
            if (StyleMatcher.matches(rule.selector, element)) {
                state = state.apply(rule.declarations, settings)
            }
        }

        val inlineDeclarations = parseInlineDeclarations(element.getAttribute("style"))
        if (inlineDeclarations.isNotEmpty()) {
            state = state.apply(inlineDeclarations, settings)
        }

        return state
    }

    private fun parseInlineDeclarations(styleText: String): List<CssDeclaration> {
        if (styleText.isBlank()) {
            return emptyList()
        }
        return styleText
            .split(';')
            .mapNotNull { entry ->
                val index = entry.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val property = entry.substring(0, index).trim()
                val value = entry.substring(index + 1).trim()
                if (property.isEmpty() || value.isEmpty()) null else CssDeclaration(property, value)
            }
    }

    private fun InlineStyleState.apply(declarations: List<CssDeclaration>, settings: LayoutSettings): InlineStyleState {
        var result = this
        for (declaration in declarations) {
            when (CssSupport.canonicalProperty(declaration.property)) {
                "text-combine-upright", "-webkit-text-combine" -> {
                    val value = declaration.value.lowercase()
                    result = when {
                        value == "none" || value.isBlank() -> result.copy(
                            combineUpright = false,
                            combineUprightDigitsOnly = false,
                            combineUprightLimit = 4
                        )
                        value.startsWith("digits") -> {
                            val limit = Regex("""digits\s+(\d)""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(2, 4) ?: 2
                            result.copy(
                                combineUpright = true,
                                combineUprightDigitsOnly = true,
                                combineUprightLimit = limit
                            )
                        }
                        else -> result.copy(
                            combineUpright = true,
                            combineUprightDigitsOnly = false,
                            combineUprightLimit = 4
                        )
                    }
                }

                "ruby-position" -> {
                    result = result.copy(rubyPosition = when (declaration.value.lowercase()) {
                        "over" -> RubyPosition.Over
                        "under" -> RubyPosition.Under
                        else -> RubyPosition.Right
                    })
                }

                "text-orientation" -> {
                    result = result.copy(textOrientationUpright = declaration.value.equals("upright", ignoreCase = true))
                }

                "writing-mode" -> {
                    result = result.copy(writingModeVertical = declaration.value.lowercase().startsWith("vertical"))
                }

                "font-size" -> {
                    result = result.copy(fontSize = CssSupport.resolveFontSize(result.fontSize, settings, declaration.value))
                }

                "font-family" -> {
                    val family = CssSupport.firstFontFamily(declaration.value)
                    if (family.isNotBlank()) {
                        result = result.copy(fontFamily = family)
                    }
                }
            }
        }
        return result
    }

    private fun InlineStyleState.resolvedFontSize(settings: LayoutSettings): Float {
        return fontSize ?: settings.fontSize
    }

    private object CssSupport {
        fun canonicalProperty(property: String): String {
            return when (property.lowercase()) {
                "-webkit-writing-mode", "-epub-writing-mode" -> "writing-mode"
                else -> property.lowercase()
            }
        }

        fun resolveFontSize(current: Float?, settings: LayoutSettings, value: String): Float {
            val base = current ?: settings.fontSize
            val normalized = value.trim().lowercase()
            return when {
                normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let { settings.fontSize * it } ?: base
                normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.let { base * it } ?: base
                normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull() ?: base
                normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let { base * it / 100f } ?: base
                normalized == "xx-small" -> settings.fontSize * 0.6f
                normalized == "x-small" -> settings.fontSize * 0.75f
                normalized == "small" -> settings.fontSize * 0.875f
                normalized == "medium" -> settings.fontSize
                normalized == "large" -> settings.fontSize * 1.125f
                normalized == "x-large" -> settings.fontSize * 1.5f
                normalized == "xx-large" -> settings.fontSize * 2f
                else -> normalized.toFloatOrNull() ?: base
            }
        }

        fun firstFontFamily(value: String): String {
            return value.split(',').firstOrNull()?.trim()?.trim('"', '\'').orEmpty()
        }
    }

    private object StyleMatcher {
        fun matches(selector: String, element: Element): Boolean {
            val normalized = selector.trim()
            if (normalized.isEmpty() || normalized.contains(" ")) {
                return false
            }
            if (normalized == "*") {
                return true
            }

            val localName = XmlSupport.localName(element).lowercase()
            val id = element.getAttribute("id")
            val classes = element.getAttribute("class")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toSet()

            return when {
                normalized.startsWith("#") -> id == normalized.drop(1)
                normalized.startsWith(".") -> classes.contains(normalized.drop(1))
                normalized.contains("#") -> {
                    val parts = normalized.split("#", limit = 2)
                    localName == parts[0].lowercase() && id == parts[1]
                }
                normalized.contains(".") -> {
                    val parts = normalized.split(".", limit = 2)
                    localName == parts[0].lowercase() && classes.contains(parts[1])
                }
                else -> localName == normalized.lowercase()
            }
        }
    }

    private data class InlineToken(
        val source: String,
        val display: String,
        val combineUpright: Boolean,
        val punctuation: Boolean,
        val textOrientationUpright: Boolean
    ) {
        fun advance(settings: LayoutSettings, fontSize: Float): Float {
            if (source.isBlank()) {
                return fontSize * settings.lineHeight * 0.5f
            }
            return when {
                combineUpright -> fontSize * settings.lineHeight
                punctuation -> fontSize * settings.lineHeight * 0.92f
                source.all { it.isWhitespace() } -> fontSize * settings.lineHeight * 0.5f
                VerticalTypography.isAsciiAlnum(source.codePointAt(0)) && !textOrientationUpright -> fontSize * settings.lineHeight * 0.5f
                else -> fontSize * settings.lineHeight
            }
        }

        fun advanceHorizontal(fontSize: Float): Float {
            if (source.isBlank()) return fontSize * 0.5f
            return if (source.codePointCount(0, source.length) > 1) {
                source.codePointCount(0, source.length) * fontSize * 0.56f
            } else if (VerticalTypography.isAsciiAlnum(source.codePointAt(0))) {
                fontSize * 0.56f
            } else {
                fontSize
            }
        }
    }

    private object VerticalTypography {
        private val punctuationMap = mapOf(
            '"' to '＂',
            '\'' to '＇',
            '/' to '／',
            '\\' to '＼',
            '-' to '－',
            '_' to '＿',
            '=' to '＝',
            '+' to '＋',
            '*' to '＊',
            '&' to '＆',
            '%' to '％',
            '#' to '＃',
            '@' to '＠',
            '$' to '＄',
            '^' to '＾',
            '~' to '～',
            '|' to '｜',
            '(' to '（',
            ')' to '）',
            '[' to '［',
            ']' to '］',
            '{' to '｛',
            '}' to '｝',
            '<' to '〈',
            '>' to '〉',
            ',' to '︐',
            '.' to '︒',
            ':' to '︓',
            ';' to '︔',
            '?' to '︖',
            '!' to '︕',
            '「' to '﹁',
            '」' to '﹂',
            '『' to '﹃',
            '』' to '﹄',
            '【' to '︻',
            '】' to '︼',
            '《' to '︽',
            '》' to '︾',
            '〔' to '︹',
            '〕' to '︺',
            '（' to '︵',
            '）' to '︶',
            '，' to '︐',
            '。' to '︒',
            '、' to '︑',
            '：' to '︓',
            '；' to '︔',
            '？' to '︖',
            '！' to '︕',
            '—' to '︱',
            '―' to '︱',
            '…' to '︙'
        )

        fun tokenize(text: String, style: InlineStyleState, vertical: Boolean = true): List<InlineToken> {
            val tokens = mutableListOf<InlineToken>()
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val charCount = Character.charCount(codePoint)

                if (!vertical) {
                    val raw = String(Character.toChars(codePoint))
                    tokens += InlineToken(
                        source = raw,
                        display = raw,
                        combineUpright = false,
                        punctuation = false,
                        textOrientationUpright = true
                    )
                    index += charCount
                    continue
                }

                when {
                    Character.isWhitespace(codePoint) -> {
                        tokens += InlineToken(
                            source = "　",
                            display = "　",
                            combineUpright = false,
                            punctuation = false,
                            textOrientationUpright = style.textOrientationUpright
                        )
                        index += charCount
                    }

                    isAsciiDigit(codePoint) -> {
                        val start = index
                        while (index < text.length && isAsciiDigit(text.codePointAt(index))) {
                            index += Character.charCount(text.codePointAt(index))
                        }
                        val raw = text.substring(start, index)
                        tokens += InlineToken(
                            source = raw,
                            display = raw,
                            combineUpright = false,
                            punctuation = false,
                            textOrientationUpright = true
                        )
                    }

                    style.combineUpright && shouldCombine(codePoint, style) -> {
                        val start = index
                        var consumed = 0
                        while (index < text.length && consumed < style.combineUprightLimit) {
                            val next = text.codePointAt(index)
                            if (!shouldCombine(next, style)) {
                                break
                            }
                            index += Character.charCount(next)
                            consumed++
                        }
                        val raw = text.substring(start, index)
                        tokens += InlineToken(
                            source = raw,
                            display = raw,
                            combineUpright = true,
                            punctuation = false,
                            textOrientationUpright = style.textOrientationUpright
                        )
                    }

                    isPunctuation(codePoint) -> {
                        val raw = String(Character.toChars(codePoint))
                        tokens += InlineToken(
                            source = raw,
                            display = verticalize(raw),
                            combineUpright = false,
                            punctuation = true,
                            textOrientationUpright = style.textOrientationUpright
                        )
                        index += charCount
                    }

                    else -> {
                        val raw = String(Character.toChars(codePoint))
                        tokens += InlineToken(
                            source = raw,
                            display = if (style.textOrientationUpright && isAsciiAlnum(codePoint)) raw else verticalize(raw),
                            combineUpright = false,
                            punctuation = false,
                            textOrientationUpright = style.textOrientationUpright
                        )
                        index += charCount
                    }
                }
            }
            return tokens
        }

        fun verticalize(text: String): String {
            if (text.isEmpty()) {
                return text
            }
            val builder = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val mapped = punctuationMap[codePoint.toChar()] ?: codePoint.toChar()
                builder.append(mapped)
                index += Character.charCount(codePoint)
            }
            return builder.toString()
        }

        fun isAsciiAlnum(codePoint: Int): Boolean {
            return codePoint in '0'.code..'9'.code ||
                codePoint in 'a'.code..'z'.code ||
                codePoint in 'A'.code..'Z'.code
        }

        private fun isAsciiDigit(codePoint: Int): Boolean = codePoint in '0'.code..'9'.code

        fun isHorizontalPunctuation(codePoint: Int): Boolean {
            return when (codePoint.toChar()) {
                '"', '\'', '/', '\\', '-', '_', '=', '+', '*', '&', '%', '#', '@', '$', '^', '~', '|' -> true
                else -> false
            }
        }

        private fun shouldCombine(codePoint: Int, style: InlineStyleState): Boolean {
            return if (style.combineUprightDigitsOnly) {
                Character.isDigit(codePoint)
            } else {
                isAsciiAlnum(codePoint)
            }
        }

        private fun isPunctuation(codePoint: Int): Boolean {
            return when (codePoint.toChar()) {
                ',', '.', ':', ';', '!', '?', '(', ')', '[', ']', '{', '}', '<', '>', '、', '。', '，', '：', '；', '？', '！', '「', '」', '『', '』', '《', '》', '（', '）', '—', '―', '…', '"', '\'', '/', '\\', '-', '_', '=', '+', '*', '&', '%', '#', '@', '$', '^', '~', '|' -> true
                else -> false
            }
        }
    }

    private fun LayoutFragment.positionAt(x: Float, y: Float): LayoutFragment {
        return when (this) {
            is LayoutFragment.Text -> copy(x = x, y = y)
            is LayoutFragment.Ruby -> copy(x = x, y = y,
                baseFragments = baseFragments.map { it.copy(x = it.x + x - this.x, y = it.y + y - this.y) },
                annotationX = annotationX + x - this.x, annotationY = annotationY + y - this.y)
            is LayoutFragment.Image -> copy(x = x, y = y)
        }
    }
}
