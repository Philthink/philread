package com.myreading.layout;

import java.util.Collections;
import java.util.List;

public final class PaginationResult {
    private final String fingerprint;
    private final String chapterId;
    private final List<Page> pages;

    public PaginationResult(String fingerprint, String chapterId, List<Page> pages) {
        this.fingerprint = fingerprint;
        this.chapterId = chapterId;
        this.pages = Collections.unmodifiableList(pages);
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getChapterId() {
        return chapterId;
    }

    public List<Page> getPages() {
        return pages;
    }
}

