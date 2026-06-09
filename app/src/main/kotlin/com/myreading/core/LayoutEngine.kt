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
    val writingModeVertical: Boolean = true
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
        val document = XmlSupport.parse(chapter.content)
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: return emptyList()

        val usableWidth = max(1f, settings.pageWidth - settings.marginLeft - settings.marginRight)
        val usableHeight = max(1f, settings.pageHeight - settings.marginTop - settings.marginBottom)
        val columnWidth = max(1f, settings.fontSize * 1.2f)
        val columnsPerPage = max(1, floor((usableWidth + settings.columnGap) / (columnWidth + settings.columnGap)).toInt())

        val pages = mutableListOf<PageLayout>()
        var currentColumns = mutableListOf<ColumnLayout>()
        var currentFragments = mutableListOf<LayoutFragment>()
        var pageIndex = 0
        var columnIndex = 0
        var usedHeight = 0f

        fun columnX(index: Int): Float {
            return settings.pageWidth - settings.marginRight - ((index + 1) * columnWidth) - (index * settings.columnGap)
        }

        fun flushColumn() {
            if (currentFragments.isEmpty()) return
            currentColumns += ColumnLayout(
                index = columnIndex,
                x = columnX(columnIndex),
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
            if (columnIndex + 1 >= columnsPerPage) {
                flushPage()
                pageIndex += 1
                columnIndex = 0
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
            val positioned = fragment.positionAt(columnX(columnIndex), settings.marginTop + usedHeight)
            currentFragments += positioned
            usedHeight += advance
        }

        fun walk(node: Node, inherited: InlineStyleState) {
            when (node.nodeType) {
                Node.TEXT_NODE -> {
                    val text = normalizeWhitespace(node.textContent)
                    if (text.isNotEmpty()) {
                        val tokens = VerticalTypography.tokenize(text, inherited)
                        tokens.forEachIndexed { index, token ->
                            val unitId = "t$pageIndex$columnIndex${currentFragments.size}$index"
                            append(
                                LayoutFragment.Text(
                                    unitId = unitId,
                                    sourceText = token.source,
                                    displayText = token.display,
                                    x = 0f,
                                    y = 0f,
                                    fontSize = settings.fontSize,
                                    lineHeight = settings.lineHeight,
                                    combineUpright = token.combineUpright,
                                    punctuation = token.punctuation,
                                    textOrientationUpright = token.textOrientationUpright
                                ),
                                token.advance(settings)
                            )
                        }
                    }
                }

                Node.ELEMENT_NODE -> {
                    val element = node as Element
                    val localName = XmlSupport.localName(element).lowercase()
                    val nextState = resolveStyle(element, stylesheet, inherited)
                    when (localName) {
                        "br" -> {
                            usedHeight += settings.fontSize * settings.lineHeight
                            if (usedHeight > usableHeight) newColumnOrPage()
                        }

                        "img" -> {
                            val href = element.getAttribute("src")
                            val advance = max(settings.fontSize * settings.lineHeight * 2f, settings.fontSize * 2f)
                            append(
                                LayoutFragment.Image(
                                    unitId = "i$pageIndex$columnIndex${currentFragments.size}",
                                    resourceHref = href,
                                    x = 0f,
                                    y = 0f,
                                    width = settings.fontSize * 2f,
                                    height = settings.fontSize * 2f
                                ),
                                advance
                            )
                        }

                        "ruby" -> {
                            val ruby = layoutRuby(
                                element = element,
                                inherited = nextState,
                                settings = settings,
                                x = columnX(columnIndex),
                                y = settings.marginTop + usedHeight,
                                unitId = "r$pageIndex$columnIndex${currentFragments.size}"
                            )
                            append(ruby.fragment, ruby.advance)
                        }

                        "audio" -> Unit

                        else -> {
                            val blockElement = isBlockElement(element)
                            if (blockElement && usedHeight > 0f) {
                                usedHeight += settings.fontSize * settings.lineHeight * 0.5f
                                if (usedHeight > usableHeight) {
                                    newColumnOrPage()
                                }
                            }
                            for (child in XmlSupport.children(element)) {
                                walk(child, nextState)
                            }
                            if (blockElement && usedHeight > 0f) {
                                usedHeight += settings.fontSize * settings.lineHeight * 0.35f
                                if (usedHeight > usableHeight) {
                                    newColumnOrPage()
                                }
                            }
                        }
                    }
                }
            }
        }

        val rootState = resolveStyle(body, stylesheet, InlineStyleState())
        for (child in XmlSupport.children(body)) {
            walk(child, rootState)
        }

        flushColumn()
        flushPage()
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
            append(stylesheet.rules.joinToString(separator = ";") { rule ->
                rule.selector + rule.declarations.joinToString(separator = ",") { declaration ->
                    "${declaration.property}:${declaration.value}"
                }
            })
            append('|')
            append(chapter.content.hashCode())
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
        val baseFragments = mutableListOf<LayoutFragment.Text>()
        var baseAdvance = 0f
        for ((index, token) in baseTokens.withIndex()) {
            val fragment = LayoutFragment.Text(
                unitId = "$unitId-b$index",
                sourceText = token.source,
                displayText = token.display,
                x = x,
                y = y + baseAdvance,
                fontSize = settings.fontSize,
                lineHeight = settings.lineHeight,
                combineUpright = token.combineUpright,
                punctuation = token.punctuation,
                textOrientationUpright = token.textOrientationUpright
            )
            baseFragments += fragment
            baseAdvance += token.advance(settings)
        }

        val annotationFontSize = rubyAnnotationFontSize(ruby.annotationText, settings)
        val annotationLineHeight = max(settings.lineHeight, 1.05f)
        val annotationHeight = annotationFontSize * annotationLineHeight
        val gap = max(2f, settings.fontSize * 0.12f)
        val annotationX = when (ruby.rubyPosition) {
            RubyPosition.Right -> x + settings.fontSize * 0.78f
            RubyPosition.Over -> x
            RubyPosition.Under -> x
        }
        val annotationY = when (ruby.rubyPosition) {
            RubyPosition.Right -> y + max(0f, (baseAdvance - annotationHeight) / 2f)
            RubyPosition.Over -> y - annotationHeight - gap
            RubyPosition.Under -> y + baseAdvance + gap
        }
        val flowAdvance = max(baseAdvance, settings.fontSize * settings.lineHeight)

        return RubyLayout(
            fragment = LayoutFragment.Ruby(
                unitId = unitId,
                baseFragments = baseFragments,
                annotationText = ruby.annotationText,
                annotationDisplayText = ruby.annotationText,
                x = x,
                y = y,
                baseFontSize = settings.fontSize,
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

    private fun rubyAnnotationFontSize(annotation: String, settings: LayoutSettings): Float {
        if (annotation.isBlank()) {
            return settings.fontSize * 0.62f
        }
        val defaultSize = settings.fontSize * 0.62f
        val maxSideWidth = max(settings.fontSize * 1.45f, settings.columnGap + settings.fontSize * 0.35f)
        val widthAtUnitSize = estimateHorizontalTextWidth(annotation, 1f).coerceAtLeast(0.01f)
        val fitSize = maxSideWidth / widthAtUnitSize
        return fitSize.coerceIn(settings.fontSize * 0.42f, defaultSize)
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

    private fun resolveStyle(element: Element, stylesheet: Stylesheet, parent: InlineStyleState): InlineStyleState {
        var state = parent

        for (rule in stylesheet.rules) {
            if (StyleMatcher.matches(rule.selector, element)) {
                state = state.apply(rule.declarations)
            }
        }

        val inlineDeclarations = parseInlineDeclarations(element.getAttribute("style"))
        if (inlineDeclarations.isNotEmpty()) {
            state = state.apply(inlineDeclarations)
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

    private fun InlineStyleState.apply(declarations: List<CssDeclaration>): InlineStyleState {
        var result = this
        for (declaration in declarations) {
            when (declaration.property.lowercase()) {
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
            }
        }
        return result
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
        fun advance(settings: LayoutSettings): Float {
            if (source.isBlank()) {
                return settings.fontSize * settings.lineHeight * 0.5f
            }
            return when {
                combineUpright -> settings.fontSize * settings.lineHeight
                punctuation -> settings.fontSize * settings.lineHeight * 0.92f
                source.all { it.isWhitespace() } -> settings.fontSize * settings.lineHeight * 0.5f
                VerticalTypography.isAsciiAlnum(source.codePointAt(0)) && !textOrientationUpright -> settings.fontSize * settings.lineHeight * 0.5f
                else -> settings.fontSize * settings.lineHeight
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

        fun tokenize(text: String, style: InlineStyleState): List<InlineToken> {
            val tokens = mutableListOf<InlineToken>()
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                val charCount = Character.charCount(codePoint)

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
            is LayoutFragment.Ruby -> copy(x = x, y = y)
            is LayoutFragment.Image -> copy(x = x, y = y)
        }
    }
}
