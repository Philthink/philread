package com.myreading.epub.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Resource {
    public interface Source {
        InputStream openStream() throws IOException;
    }

    private final String id;
    private final String href;
    private final String mediaType;
    private final ResourceType type;
    private final Set<String> properties;
    private final String fallback;
    private final Source source;

    private byte[] cachedBytes;

    public Resource(String id,
                    String href,
                    String mediaType,
                    ResourceType type,
                    Set<String> properties,
                    String fallback,
                    Source source) {
        this.id = id;
        this.href = href;
        this.mediaType = mediaType;
        this.type = type;
        this.properties = Collections.unmodifiableSet(new LinkedHashSet<>(properties));
        this.fallback = fallback;
        this.source = source;
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

    public ResourceType getType() {
        return type;
    }

    public Set<String> getProperties() {
        return properties;
    }

    public String getFallback() {
        return fallback;
    }

    public InputStream openStream() throws IOException {
        return source.openStream();
    }

    public synchronized byte[] readBytes() throws IOException {
        if (cachedBytes != null) {
            return cachedBytes.clone();
        }
        InputStream input = null;
        try {
            input = openStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            cachedBytes = output.toByteArray();
            return cachedBytes.clone();
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    public String readText() throws IOException {
        return readText(StandardCharsets.UTF_8);
    }

    public String readText(Charset charset) throws IOException {
        return new String(readBytes(), charset);
    }
}

