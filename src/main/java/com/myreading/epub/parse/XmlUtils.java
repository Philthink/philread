package com.myreading.epub.parse;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

public final class XmlUtils {
    private XmlUtils() {
    }

    public static List<Node> children(Node node) {
        List<Node> result = new ArrayList<>();
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add(nodes.item(i));
        }
        return result;
    }

    public static String localName(Node node) {
        String localName = node.getLocalName();
        return localName != null ? localName : node.getNodeName();
    }
}
