package com.myreading.core

data class CssDeclaration(val property: String, val value: String)
data class CssRule(val selector: String, val declarations: List<CssDeclaration>)
data class Stylesheet(val rules: List<CssRule>, val resourceRefs: Set<String>)

object CssParser {
    private val urlPattern = Regex("""url\((['"]?)([^'")]+)\1\)""", RegexOption.IGNORE_CASE)
    private val importPattern = Regex("""@import\s+(?:url\()?(?:['"])?([^'")\s;]+)(?:['"])?\)?""", RegexOption.IGNORE_CASE)

    fun parse(css: String, baseHref: String? = null): Stylesheet {
        val refs = linkedSetOf<String>()
        val rules = mutableListOf<CssRule>()

        urlPattern.findAll(css).forEach { match ->
            val href = match.groupValues[2].trim()
            if (href.isNotEmpty() && baseHref != null) refs += PathSupport.resolve(baseHref, href)
        }
        importPattern.findAll(css).forEach { match ->
            val href = match.groupValues[1].trim()
            if (href.isNotEmpty() && baseHref != null) refs += PathSupport.resolve(baseHref, href)
        }

        val normalized = css
            .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")

        val rulePattern = Regex("""(?s)([^{}]+)\{([^}]*)\}""")
        rulePattern.findAll(normalized).forEach { match ->
            val selector = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (selector.startsWith("@font-face")) {
                urlPattern.findAll(body).forEach { urlMatch ->
                    val href = urlMatch.groupValues[2].trim()
                    if (href.isNotEmpty() && baseHref != null) refs += PathSupport.resolve(baseHref, href)
                }
                return@forEach
            }
            val declarations = body.split(';')
                .mapNotNull {
                    val index = it.indexOf(':')
                    if (index <= 0) return@mapNotNull null
                    val property = it.substring(0, index).trim()
                    val value = it.substring(index + 1).trim()
                    if (property.isEmpty() || value.isEmpty()) null else CssDeclaration(property, value)
                }
            if (selector.isNotEmpty() && declarations.isNotEmpty()) {
                selector.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { part ->
                        rules += CssRule(part, declarations)
                    }
            }
        }

        return Stylesheet(rules = rules, resourceRefs = refs)
    }
}

