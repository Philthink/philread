package com.myreading.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object XmlSupport {
    fun parse(input: InputStream): Document {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            return factory.newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw IOException("Failed to parse XML", e)
        }
    }

    fun parse(xml: String): Document {
        val sanitized = xml.replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
        return parse(ByteArrayInputStream(sanitized.toByteArray(StandardCharsets.UTF_8)))
    }

    fun localName(node: Node): String = node.localName ?: node.nodeName

    fun textContent(node: Node?): String = node?.textContent ?: ""

    fun children(node: Node): List<Node> {
        val result = mutableListOf<Node>()
        val nodes: NodeList = node.childNodes
        for (index in 0 until nodes.length) {
            result += nodes.item(index)
        }
        return result
    }
}

object PathSupport {
    fun parent(path: String): String {
        val index = path.lastIndexOf('/')
        return if (index < 0) "" else path.substring(0, index + 1)
    }

    fun stripFragmentAndQuery(href: String): String {
        val hash = href.indexOf('#')
        val query = href.indexOf('?')
        val end = listOf(href.length, hash.takeIf { it >= 0 } ?: href.length, query.takeIf { it >= 0 } ?: href.length).minOrNull() ?: href.length
        return href.substring(0, end)
    }

    fun fragment(href: String): String? {
        val index = href.indexOf('#')
        return if (index >= 0) href.substring(index + 1) else null
    }

    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    fun resolve(basePath: String, href: String): String {
        if (href.isEmpty()) return normalize(basePath)
        val cleaned = stripFragmentAndQuery(href)
        if (cleaned.startsWith("/")) return normalize(cleaned.removePrefix("/"))
        if (cleaned.contains(":") && !cleaned.startsWith("../") && !cleaned.startsWith("./")) return cleaned
        return normalize(parent(basePath) + cleaned)
    }

    fun resolveWithFragment(basePath: String, href: String): String {
        val resolved = resolve(basePath, href)
        val fragment = fragment(href)
        return if (fragment.isNullOrEmpty()) resolved else "$resolved#$fragment"
    }
}

