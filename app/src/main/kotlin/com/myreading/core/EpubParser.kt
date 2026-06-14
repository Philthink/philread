package com.myreading.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class EpubParser {
    fun parse(epubFile: File): Book {
        ZipFile(epubFile).use { archive ->
            val issues = mutableListOf<ParseIssue>()
            val rootfilePath = readRootfilePath(archive)
            val opfBytes = readBytes(archive, rootfilePath)
            val opf = XmlSupport.parse(opfBytes)
            val opfBasePath = PathSupport.parent(rootfilePath)

            val metadata = parseMetadata(opf)
            val manifest = parseManifest(opf, opfBasePath)
            val spine = parseSpine(opf, manifest)
            val navigation = parseNavigation(archive, manifest)
            val resourcesByHref = buildResources(epubFile, manifest)
            val chapters = buildChapters(archive, spine, manifest, navigation, issues)

            return Book(
                sourcePath = epubFile.absolutePath,
                metadata = metadata,
                manifest = manifest,
                spine = spine,
                navigation = navigation,
                chapters = chapters,
                resourcesByHref = resourcesByHref,
                issues = issues
            )
        }
    }

    private fun readRootfilePath(archive: ZipFile): String {
        val entry = archive.getEntry("META-INF/container.xml")
            ?: throw IOException("Missing META-INF/container.xml")
        val bytes = archive.getInputStream(entry).use { it.readBytes() }
        val document = XmlSupport.parse(bytes)
        val rootfiles = document.getElementsByTagNameNS("*", "rootfile")
        if (rootfiles.length == 0) throw IOException("container.xml missing rootfile")
        val rootfile = rootfiles.item(0) as Element
        val path = rootfile.getAttribute("full-path")
        if (path.isNullOrBlank()) throw IOException("container.xml rootfile missing full-path")
        return PathSupport.normalize(path)
    }

    private fun readBytes(archive: ZipFile, entryPath: String): ByteArray {
        val entry = archive.getEntry(entryPath) ?: throw IOException("Missing EPUB entry: $entryPath")
        return archive.getInputStream(entry).use { it.readBytes() }
    }

    private fun parseMetadata(opf: Document): Metadata {
        val metadataNodes = opf.getElementsByTagNameNS("*", "metadata")
        if (metadataNodes.length == 0) {
            return Metadata(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, emptyMap())
        }
        val metadata = metadataNodes.item(0) as Element
        fun texts(name: String): List<String> = metadata.getElementsByTagNameNS("*", name).let { list ->
            (0 until list.length).mapNotNull { list.item(it).textContent?.trim()?.takeIf(String::isNotEmpty) }
        }
        var modified: String? = null
        val extras = linkedMapOf<String, MutableList<String>>()

        for (child in XmlSupport.children(metadata)) {
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val element = child as Element
            when (XmlSupport.localName(element)) {
                "meta" -> {
                    val property = element.getAttribute("property")
                    val value = element.textContent?.trim().orEmpty()
                    if (property == "dcterms:modified") modified = value else if (property.isNotBlank()) {
                        extras.getOrPut(property) { mutableListOf() }.add(value)
                    }
                }
                else -> {
                    val local = XmlSupport.localName(element)
                    if (local !in setOf("title", "creator", "language", "identifier", "publisher", "description", "subject", "date")) {
                        val value = element.textContent?.trim().orEmpty()
                        extras.getOrPut(local) { mutableListOf() }.add(value)
                    }
                }
            }
        }

        return Metadata(
            titles = texts("title"),
            creators = texts("creator"),
            languages = texts("language"),
            identifiers = texts("identifier"),
            publishers = texts("publisher"),
            descriptions = texts("description"),
            subjects = texts("subject"),
            dates = texts("date"),
            modified = modified,
            extras = extras.mapValues { it.value.toList() }
        )
    }

    private fun parseManifest(opf: Document, opfBasePath: String): Manifest {
        val manifestNodes = opf.getElementsByTagNameNS("*", "manifest")
        if (manifestNodes.length == 0) return Manifest(emptyList())
        val element = manifestNodes.item(0) as Element
        val items = mutableListOf<ManifestItem>()
        val nodes = element.getElementsByTagNameNS("*", "item")
        for (index in 0 until nodes.length) {
            val item = nodes.item(index) as Element
            items += ManifestItem(
                id = item.getAttribute("id"),
                href = PathSupport.resolve(opfBasePath, item.getAttribute("href")),
                mediaType = item.getAttribute("media-type"),
                properties = item.getAttribute("properties").splitToSequence(Regex("\\s+")).filter { it.isNotBlank() }.toSet(),
                fallback = item.getAttribute("fallback").takeIf { it.isNotBlank() },
                mediaOverlay = item.getAttribute("media-overlay").takeIf { it.isNotBlank() }
            )
        }
        return Manifest(items)
    }

    private fun parseSpine(opf: Document, manifest: Manifest): Spine {
        val spineNodes = opf.getElementsByTagNameNS("*", "spine")
        if (spineNodes.length == 0) return Spine(emptyList())
        val element = spineNodes.item(0) as Element
        val items = mutableListOf<SpineItem>()
        val nodes = element.getElementsByTagNameNS("*", "itemref")
        for (index in 0 until nodes.length) {
            val itemref = nodes.item(index) as Element
            val idref = itemref.getAttribute("idref")
            val manifestItem = manifest.byId[idref] ?: continue
            items += SpineItem(
                idref = idref,
                href = manifestItem.href,
                linear = itemref.getAttribute("linear").lowercase() != "no",
                properties = itemref.getAttribute("properties").splitToSequence(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
            )
        }
        return Spine(items)
    }

    private fun parseNavigation(archive: ZipFile, manifest: Manifest): Navigation {
        val navItem = manifest.items.firstOrNull { it.properties.contains("nav") }
        val ncxItem = manifest.items.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }

        navItem?.let {
            runCatching {
                val bytes = readBytes(archive, it.href)
                val doc = XmlSupport.parse(bytes)
                val roots = parseNavXhtml(doc, it.href)
                if (roots.isNotEmpty()) return Navigation(it.href, roots)
            }
        }

        ncxItem?.let {
            runCatching {
                val bytes = readBytes(archive, it.href)
                val doc = XmlSupport.parse(bytes)
                val roots = parseNcx(doc, it.href)
                return Navigation(it.href, roots)
            }
        }

        return Navigation(null, emptyList())
    }

    private fun parseNavXhtml(document: Document, navHref: String): List<NavigationNode> {
        val result = mutableListOf<NavigationNode>()
        val navs = document.getElementsByTagNameNS("*", "nav")
        for (i in 0 until navs.length) {
            val nav = navs.item(i) as Element
            val epubType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type")
            val role = nav.getAttribute("role")
            if (!epubType.equals("toc", true) && !role.equals("doc-toc", true)) continue
            val lists = nav.getElementsByTagNameNS("*", "ol")
            for (j in 0 until lists.length) {
                result += parseNavList(lists.item(j) as Element, navHref)
            }
        }
        return result
    }

    private fun parseNavList(listElement: Element, navHref: String): List<NavigationNode> {
        val result = mutableListOf<NavigationNode>()
        for (child in XmlSupport.children(listElement)) {
            if (child.nodeType != Node.ELEMENT_NODE || XmlSupport.localName(child) != "li") continue
            result += parseNavItem(child as Element, navHref)
        }
        return result
    }

    private fun parseNavItem(li: Element, navHref: String): NavigationNode {
        var title = ""
        var href: String? = null
        val children = mutableListOf<NavigationNode>()
        for (child in XmlSupport.children(li)) {
            if (child.nodeType != Node.ELEMENT_NODE) continue
            when (XmlSupport.localName(child)) {
                "a" -> {
                    val anchor = child as Element
                    title = anchor.textContent?.trim().orEmpty()
                    href = PathSupport.resolveWithFragment(navHref, anchor.getAttribute("href"))
                }
                "ol" -> children += parseNavList(child as Element, navHref)
            }
        }
        return NavigationNode(title, href, children)
    }

    private fun parseNcx(document: Document, ncxHref: String): List<NavigationNode> {
        val roots = mutableListOf<NavigationNode>()
        val navMaps = document.getElementsByTagNameNS("*", "navMap")
        if (navMaps.length == 0) return roots
        val navMap = navMaps.item(0) as Element
        for (child in XmlSupport.children(navMap)) {
            if (child.nodeType != Node.ELEMENT_NODE || XmlSupport.localName(child) != "navPoint") continue
            roots += parseNcxNavPoint(child as Element, ncxHref)
        }
        return roots
    }

    private fun parseNcxNavPoint(navPoint: Element, ncxHref: String): NavigationNode {
        var title = ""
        var href: String? = null
        val children = mutableListOf<NavigationNode>()
        for (child in XmlSupport.children(navPoint)) {
            if (child.nodeType != Node.ELEMENT_NODE) continue
            when (XmlSupport.localName(child)) {
                "navLabel" -> {
                    val texts = (child as Element).getElementsByTagNameNS("*", "text")
                    if (texts.length > 0) title = texts.item(0).textContent.trim()
                }
                "content" -> href = PathSupport.resolveWithFragment(ncxHref, (child as Element).getAttribute("src"))
                "navPoint" -> children += parseNcxNavPoint(child as Element, ncxHref)
            }
        }
        return NavigationNode(title, href, children)
    }

    private fun buildResources(epubFile: File, manifest: Manifest): Map<String, Resource> {
        return manifest.items.associateBy({ it.href }) { item ->
            Resource(
                id = item.id,
                href = item.href,
                mediaType = item.mediaType,
                type = classifyResource(item.mediaType, item.href),
                properties = item.properties,
                fallback = item.fallback
            ) {
                ZipFile(epubFile).use { zip ->
                    val entry = zip.getEntry(item.href) ?: throw IOException("Missing EPUB entry: ${item.href}")
                    java.io.ByteArrayInputStream(zip.getInputStream(entry).use { it.readBytes() })
                }
            }
        }
    }

    private fun classifyResource(mediaType: String, href: String): ResourceType {
        val normalized = mediaType.lowercase()
        return when {
            normalized.contains("xhtml") || normalized.contains("html") -> ResourceType.XHTML
            normalized.contains("css") -> ResourceType.CSS
            normalized.contains("ncx") || normalized.contains("xml") -> ResourceType.XML
            normalized.startsWith("image/svg") || href.endsWith(".svg", true) -> ResourceType.SVG
            normalized.startsWith("image/") -> ResourceType.IMAGE
            normalized.startsWith("audio/") -> ResourceType.AUDIO
            normalized.contains("font") || normalized.contains("opentype") || normalized.contains("truetype") || normalized.contains("woff") -> ResourceType.FONT
            normalized.startsWith("text/") -> ResourceType.TEXT
            else -> ResourceType.OTHER
        }
    }

    private fun buildChapters(
        archive: ZipFile,
        spine: Spine,
        manifest: Manifest,
        navigation: Navigation,
        issues: MutableList<ParseIssue>
    ): List<Chapter> {
        val titleIndex = buildNavigationTitleIndex(navigation)
        return spine.items.mapIndexedNotNull { order, spineItem ->
            val manifestItem = manifest.byId[spineItem.idref] ?: return@mapIndexedNotNull null
            val content = readBytes(archive, manifestItem.href)
            val title = titleIndex[PathSupport.stripFragmentAndQuery(manifestItem.href)]
                ?: extractChapterTitle(content, manifestItem.href)
            val referenced = linkedSetOf<String>()
            referenced += manifestItem.href
            referenced += scanChapterResources(archive, content, manifestItem.href, manifest)
            Chapter(
                id = spineItem.idref,
                href = manifestItem.href,
                title = title,
                order = order,
                linear = spineItem.linear,
                content = content,
                referencedResourceHrefs = referenced
            )
        }
    }

    private fun buildNavigationTitleIndex(navigation: Navigation): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun visit(nodes: List<NavigationNode>) {
            for (node in nodes) {
                node.href?.let { result.putIfAbsent(PathSupport.stripFragmentAndQuery(it), node.title) }
                visit(node.children)
            }
        }
        visit(navigation.roots)
        return result
    }

    private fun extractChapterTitle(content: ByteArray, href: String): String {
        return runCatching {
            val doc = XmlSupport.parse(content)
            val titleNodes = doc.getElementsByTagNameNS("*", "title")
            if (titleNodes.length > 0) {
                val title = titleNodes.item(0).textContent.trim()
                if (title.isNotEmpty()) return@runCatching title
            }
            for (tag in listOf("h1", "h2", "h3", "h4", "h5", "h6")) {
                val nodes = doc.getElementsByTagNameNS("*", tag)
                if (nodes.length > 0) {
                    val text = nodes.item(0).textContent.trim()
                    if (text.isNotEmpty()) return@runCatching text
                }
            }
            href
        }.getOrDefault(href)
    }

    private fun scanChapterResources(
        archive: ZipFile,
        content: ByteArray,
        chapterHref: String,
        manifest: Manifest
    ): Set<String> {
        val refs = linkedSetOf<String>()
        runCatching {
            val doc = XmlSupport.parse(content)
            collectXhtmlResourceRefs(doc.documentElement, chapterHref, refs)
            collectStylesheets(doc, chapterHref, refs, archive, manifest)
        }
        return refs
    }

    private fun collectStylesheets(
        document: Document,
        chapterHref: String,
        refs: MutableSet<String>,
        archive: ZipFile,
        manifest: Manifest
    ) {
        val links = document.getElementsByTagNameNS("*", "link")
        for (index in 0 until links.length) {
            val link = links.item(index) as Element
            val rel = link.getAttribute("rel")
            if (!rel.lowercase().contains("stylesheet")) continue
            val href = PathSupport.resolve(chapterHref, link.getAttribute("href"))
            refs += href
            val resource = manifest.byHref[href] ?: continue
            if (resource.mediaType.lowercase().contains("css")) {
                runCatching {
                    val cssBytes = readBytes(archive, href)
                    val css = cssBytes.toString(Charsets.UTF_8)
                    refs += CssParser.parse(css, href).resourceRefs
                }
            }
        }
    }

    private fun collectXhtmlResourceRefs(element: Element?, chapterHref: String, refs: MutableSet<String>) {
        if (element == null) return
        val localName = XmlSupport.localName(element).lowercase()
        if (localName in setOf("img", "image", "audio", "source", "video", "object", "embed", "script", "link")) {
            for (attr in listOf("src", "href", "data", "poster")) {
                val value = element.getAttribute(attr)
                if (value.isNotBlank()) refs += PathSupport.resolve(chapterHref, value)
            }
            val xlinkHref = element.getAttributeNS("http://www.w3.org/1999/xlink", "href")
            if (xlinkHref.isNotBlank()) refs += PathSupport.resolve(chapterHref, xlinkHref)
        }
        if (element.hasAttribute("style")) {
            refs += CssParser.parse(element.getAttribute("style"), chapterHref).resourceRefs
        }
        if (localName == "style") {
            refs += CssParser.parse(element.textContent.orEmpty(), chapterHref).resourceRefs
        }
        for (child in XmlSupport.children(element)) {
            if (child.nodeType == Node.ELEMENT_NODE) collectXhtmlResourceRefs(child as Element, chapterHref, refs)
        }
    }
}
