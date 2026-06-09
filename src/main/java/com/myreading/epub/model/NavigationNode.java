package com.myreading.epub.model;

import java.util.Collections;
import java.util.List;

public final class NavigationNode {
    private final String title;
    private final String href;
    private final List<NavigationNode> children;

    public NavigationNode(String title, String href, List<NavigationNode> children) {
        this.title = title;
        this.href = href;
        this.children = Collections.unmodifiableList(children);
    }

    public String getTitle() {
        return title;
    }

    public String getHref() {
        return href;
    }

    public List<NavigationNode> getChildren() {
        return children;
    }
}

