package com.myreading.core

import org.junit.Assert.assertEquals
import org.junit.Test

class XmlSupportTest {
    @Test
    fun parsesNamespacedUtf8Xml() {
        val document = XmlSupport.parse(
            """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf">
                    <metadata><title>資治通鑑</title></metadata>
                </package>
            """.trimIndent().encodeToByteArray()
        )

        val titles = document.getElementsByTagNameNS("*", "title")
        assertEquals(1, titles.length)
        assertEquals("資治通鑑", titles.item(0).textContent)
    }
}
