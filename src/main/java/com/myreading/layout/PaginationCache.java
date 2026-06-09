package com.myreading.layout;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PaginationCache {
    private final int capacity;
    private final Map<String, PaginationResult> cache;

    public PaginationCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.cache = new LinkedHashMap<String, PaginationResult>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PaginationResult> eldest) {
                return size() > PaginationCache.this.capacity;
            }
        };
    }

    public synchronized PaginationResult get(String fingerprint) {
        return cache.get(fingerprint);
    }

    public synchronized void put(String fingerprint, PaginationResult result) {
        cache.put(fingerprint, result);
    }

    public synchronized int size() {
        return cache.size();
    }
}

