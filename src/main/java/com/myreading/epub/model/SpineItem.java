package com.myreading.epub.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SpineItem {
    private final String idref;
    private final String href;
    private final boolean linear;
    private final Set<String> properties;

    public SpineItem(String idref, String href, boolean linear, Set<String> properties) {
        this.idref = idref;
        this.href = href;
        this.linear = linear;
        this.properties = Collections.unmodifiableSet(new LinkedHashSet<>(properties));
    }

    public String getIdref() {
        return idref;
    }

    public String getHref() {
        return href;
    }

    public boolean isLinear() {
        return linear;
    }

    public Set<String> getProperties() {
        return properties;
    }
}

