package com.myreading.epub.model;

import java.util.Collections;
import java.util.List;

public final class Navigation {
    private final String source;
    private final List<NavigationNode> roots;

    public Navigation(String source, List<NavigationNode> roots) {
        this.source = source;
        this.roots = Collections.unmodifiableList(roots);
    }

    public String getSource() {
        return source;
    }

    public List<NavigationNode> getRoots() {
        return roots;
    }
}

