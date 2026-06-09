package com.myreading.epub.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ManifestItem {
    private final String id;
    private final String href;
    private final String mediaType;
    private final Set<String> properties;
    private final String fallback;
    private final String mediaOverlay;

    public ManifestItem(String id,
                        String href,
                        String mediaType,
                        Set<String> properties,
                        String fallback,
                        String mediaOverlay) {
        this.id = id;
        this.href = href;
        this.mediaType = mediaType;
        this.properties = Collections.unmodifiableSet(new LinkedHashSet<>(properties));
        this.fallback = fallback;
        this.mediaOverlay = mediaOverlay;
    }

    public String getId() {
        return id;
    }

    public String getHref() {
        return href;
    }

    public String getMediaType() {
        return mediaType;
    }

    public Set<String> getProperties() {
        return properties;
    }

    public String getFallback() {
        return fallback;
    }

    public String getMediaOverlay() {
        return mediaOverlay;
    }
}

