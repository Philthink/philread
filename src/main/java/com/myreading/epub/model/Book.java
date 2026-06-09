package com.myreading.epub.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class Book {
    private final String sourcePath;
    private final Metadata metadata;
    private final Manifest manifest;
    private final Spine spine;
    private final Navigation navigation;
    private final List<Chapter> chapters;
    private final Map<String, Resource> resourcesByHref;
    private final List<ParseIssue> issues;

    public Book(String sourcePath,
                Metadata metadata,
                Manifest manifest,
                Spine spine,
                Navigation navigation,
                List<Chapter> chapters,
                Map<String, Resource> resourcesByHref,
                List<ParseIssue> issues) {
        this.sourcePath = sourcePath;
        this.metadata = metadata;
        this.manifest = manifest;
        this.spine = spine;
        this.navigation = navigation;
        this.chapters = Collections.unmodifiableList(chapters);
        this.resourcesByHref = Collections.unmodifiableMap(resourcesByHref);
        this.issues = Collections.unmodifiableList(issues);
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public Metadata getMetadata() {
        return metadata;
    }

    public Manifest getManifest() {
        return manifest;
    }

    public Spine getSpine() {
        return spine;
    }

    public Navigation getNavigation() {
        return navigation;
    }

    public List<Chapter> getChapters() {
        return chapters;
    }

    public Map<String, Resource> getResourcesByHref() {
        return resourcesByHref;
    }

    public List<ParseIssue> getIssues() {
        return issues;
    }
}

