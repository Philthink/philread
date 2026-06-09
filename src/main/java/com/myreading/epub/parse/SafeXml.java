package com.myreading.epub.parse;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

public final class SafeXml {
    private SafeXml() {
    }

    public static Document parse(InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(input);
        } catch (Exception e) {
            throw new IOException("Failed to parse XML", e);
        }
    }

    public static Document parse(String xml) throws IOException {
        String sanitized = xml == null ? "" : xml.replaceAll("(?is)<!DOCTYPE[^>]*>", "");
        return parse(new java.io.ByteArrayInputStream(sanitized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static String textContent(org.w3c.dom.Node node) {
        return node == null ? "" : node.getTextContent();
    }
}
