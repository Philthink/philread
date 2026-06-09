package com.myreading.epub.model;

import java.util.Collections;
import java.util.List;

public final class Spine {
    private final List<SpineItem> items;

    public Spine(List<SpineItem> items) {
        this.items = Collections.unmodifiableList(items);
    }

    public List<SpineItem> getItems() {
        return items;
    }
}

