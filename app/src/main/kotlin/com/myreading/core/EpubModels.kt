package com.myreading.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

enum class ResourceType {
    XHTML,
    CSS,
    FONT,
    IMAGE,
    SVG,
    AUDIO,
    NAVIGATION,
    XML,
    TEXT,
    OTHER
}

data class ParseIssue(
    val severity: Severity,
    val message: String,
    val path: String? = null
) {
    enum class Severity { INFO, WARNING, ERROR }
}

data class Metadata(
    val titles: List<String>,
    val creators: List<String>,
    val languages: List<String>,
    val identifiers: List<String>,
    val publishers: List<String>,
    val descriptions: List<String>,
    val subjects: List<String>,
    val dates: List<String>,
    val modified: String?,
    val extras: Map<String, List<String>>
) {
    val title: String? get() = titles.firstOrNull()
    val creator: String? get() = creators.firstOrNull()
    val language: String? get() = languages.firstOrNull()
    val identifier: String? get() = identifiers.firstOrNull()
}

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: Set<String> = emptySet(),
    val fallback: String? = null,
    val mediaOverlay: String? = null
)

data class Manifest(
    val items: List<ManifestItem>
) {
    val byId: Map<String, ManifestItem> = items.associateBy { it.id }
    val byHref: Map<String, ManifestItem> = items.associateBy { it.href }
}

data class SpineItem(
    val idref: String,
    val href: String,
    val linear: Boolean,
    val properties: Set<String> = emptySet()
)

data class Spine(
    val items: List<SpineItem>
)

data class NavigationNode(
    val title: String,
    val href: String?,
    val children: List<NavigationNode> = emptyList()
)

data class Navigation(
    val source: String?,
    val roots: List<NavigationNode>
)

data class Chapter(
    val id: String,
    val href: String,
    val title: String,
    val order: Int,
    val linear: Boolean,
    val content: ByteArray,
    val referencedResourceHrefs: Set<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as Chapter
        return id == other.id && href == other.href && title == other.title &&
            order == other.order && linear == other.linear &&
            content.contentEquals(other.content) && referencedResourceHrefs == other.referencedResourceHrefs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + href.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + order
        result = 31 * result + linear.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + referencedResourceHrefs.hashCode()
        return result
    }
}

class Resource(
    val id: String,
    val href: String,
    val mediaType: String,
    val type: ResourceType,
    val properties: Set<String>,
    val fallback: String?,
    private val opener: () -> InputStream
) {
    private var cachedBytes: ByteArray? = null

    fun openStream(): InputStream = opener()

    @Synchronized
    fun readBytes(): ByteArray {
        cachedBytes?.let { return it.clone() }
        val input = openStream()
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            cachedBytes = bytes
            return bytes.clone()
        }
    }

    fun readText(charset: java.nio.charset.Charset = Charsets.UTF_8): String = String(readBytes(), charset)
}

data class Book(
    val sourcePath: String,
    val metadata: Metadata,
    val manifest: Manifest,
    val spine: Spine,
    val navigation: Navigation,
    val chapters: List<Chapter>,
    val resourcesByHref: Map<String, Resource>,
    val issues: List<ParseIssue>
)
