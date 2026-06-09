package com.myreading.layout;

import com.myreading.epub.parse.SafeXml;
import com.myreading.epub.parse.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ChapterLayoutBuilder {
    public List<LayoutUnit> build(String chapterId, String xhtml) throws IOException {
        Document document = SafeXml.parse(xhtml);
        List<LayoutUnit> units = new ArrayList<>();
        NodeList bodies = document.getElementsByTagNameNS("*", "body");
        if (bodies.getLength() == 0) {
            return units;
        }
        traverseChildren((Element) bodies.item(0), units, new Counter(chapterId + "-"));
        return units;
    }

    private void traverseChildren(Node parent, List<LayoutUnit> out, Counter counter) {
        NodeList children = parent.getChildNodes();
        boolean previousWasBlock = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.TEXT_NODE) {
                String text = normalizeWhitespace(node.getTextContent());
                if (!text.isEmpty()) {
                    out.add(LayoutUnit.text(counter.next("t"), text, false));
                    previousWasBlock = false;
                }
                continue;
            }
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            String localName = XmlUtils.localName(element).toLowerCase();
            if (isBlock(localName) && previousWasBlock) {
                out.add(LayoutUnit.breakUnit(counter.next("b"), LayoutUnit.BreakKind.BLOCK));
            }
            if ("br".equals(localName)) {
                out.add(LayoutUnit.breakUnit(counter.next("l"), LayoutUnit.BreakKind.LINE));
                previousWasBlock = false;
                continue;
            }
            if ("img".equals(localName)) {
                out.add(LayoutUnit.image(counter.next("i"), element.getAttribute("src"), estimateImageAdvance(element)));
                previousWasBlock = false;
                continue;
            }
            if ("ruby".equals(localName)) {
                RubyText rubyText = parseRuby(element);
                if (!rubyText.baseText.isEmpty()) {
                    out.add(LayoutUnit.ruby(counter.next("r"), rubyText.baseText, rubyText.annotation, false));
                }
                previousWasBlock = false;
                continue;
            }
            if ("audio".equals(localName)) {
                previousWasBlock = false;
                continue;
            }
            if (isBlock(localName)) {
                traverseChildren(element, out, counter);
                previousWasBlock = true;
            } else {
                traverseChildren(element, out, counter);
                previousWasBlock = false;
            }
        }
    }

    private boolean isBlock(String localName) {
        return "p".equals(localName)
                || "div".equals(localName)
                || "section".equals(localName)
                || "article".equals(localName)
                || "aside".equals(localName)
                || "blockquote".equals(localName)
                || "li".equals(localName)
                || "ul".equals(localName)
                || "ol".equals(localName)
                || "table".equals(localName)
                || "tr".equals(localName)
                || "td".equals(localName)
                || "th".equals(localName)
                || "h1".equals(localName)
                || "h2".equals(localName)
                || "h3".equals(localName)
                || "h4".equals(localName)
                || "h5".equals(localName)
                || "h6".equals(localName);
    }

    private RubyText parseRuby(Element rubyElement) {
        StringBuilder base = new StringBuilder();
        StringBuilder annotation = new StringBuilder();
        NodeList children = rubyElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE && node.getNodeType() != Node.TEXT_NODE) {
                continue;
            }
            if (node.getNodeType() == Node.TEXT_NODE) {
                base.append(normalizeWhitespace(node.getTextContent()));
                continue;
            }
            String localName = XmlUtils.localName(node).toLowerCase();
            if ("rt".equals(localName)) {
                annotation.append(normalizeWhitespace(node.getTextContent()));
            } else if (!"rp".equals(localName)) {
                base.append(normalizeWhitespace(node.getTextContent()));
            }
        }
        return new RubyText(base.toString(), annotation.toString());
    }

    private String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\u00a0', ' ');
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.trim();
    }

    private double estimateImageAdvance(Element element) {
        double height = parseDouble(element.getAttribute("height"), 0.0d);
        if (height > 0.0d) {
            return height;
        }
        return 48.0d;
    }

    private double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class RubyText {
        private final String baseText;
        private final String annotation;

        private RubyText(String baseText, String annotation) {
            this.baseText = baseText;
            this.annotation = annotation;
        }
    }

    private static final class Counter {
        private final String prefix;
        private int value = 0;

        private Counter(String prefix) {
            this.prefix = prefix;
        }

        private String next(String type) {
            value++;
            return prefix + type + value;
        }
    }
}

