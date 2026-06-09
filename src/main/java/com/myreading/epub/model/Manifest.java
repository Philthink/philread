package com.myreading.epub.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Manifest {
    private final List<ManifestItem> items;
    private final Map<String, ManifestItem> itemsById;
    private final Map<String, ManifestItem> itemsByHref;

    public Manifest(List<ManifestItem> items) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.itemsById = new LinkedHashMap<>();
        this.itemsByHref = new LinkedHashMap<>();
        for (ManifestItem item : items) {
            itemsById.put(item.getId(), item);
            itemsByHref.put(item.getHref(), item);
        }
    }

    public List<ManifestItem> getItems() {
        return items;
    }

    public ManifestItem getById(String id) {
        return itemsById.get(id);
    }

    public ManifestItem getByHref(String href) {
        return itemsByHref.get(href);
    }
}

