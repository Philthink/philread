package com.myreading.layout;

import java.util.List;

public final class PaginationService {
    private final PaginationCache cache;

    public PaginationService(PaginationCache cache) {
        this.cache = cache;
    }

    public PaginationResult paginate(String chapterId, List<LayoutUnit> units, PaginationSettings settings) {
        String fingerprint = LayoutFingerprint.compute(chapterId, units, settings);
        PaginationResult cached = cache.get(fingerprint);
        if (cached != null) {
            return cached;
        }
        PaginationResult result = VerticalPaginator.paginate(chapterId, units, settings, fingerprint);
        cache.put(fingerprint, result);
        return result;
    }
}

