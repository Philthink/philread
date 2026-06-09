package com.myreading.epub.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Chapter {
    private final String id;
    private final String href;
    private final String title;
    private final int order;
    private final boolean linear;
    private final String content;
    private final Set<String> referencedResourceHrefs;

    public Chapter(String id,
                   String href,
                   String title,
                   int order,
                   boolean linear,
                   String content,
                   Set<String> referencedResourceHrefs) {
        this.id = id;
        this.href = href;
        this.title = title;
        this.order = order;
        this.linear = linear;
        this.content = content;
        this.referencedResourceHrefs = Collections.unmodifiableSet(new LinkedHashSet<>(referencedResourceHrefs));
    }

    public String getId() {
        return id;
    }

    public String getHref() {
        return href;
    }

    public String getTitle() {
        return title;
    }

    public int getOrder() {
        return order;
    }

    public boolean isLinear() {
        return linear;
    }

    public String getContent() {
        return content;
    }

    public Set<String> getReferencedResourceHrefs() {
        return referencedResourceHrefs;
    }
}

