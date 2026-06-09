package com.myreading.epub.parse;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CssResourceScanner {
    private static final Pattern URL_PATTERN = Pattern.compile("url\\((['\"]?)([^'\"\\)]+)\\1\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPORT_PATTERN = Pattern.compile("@import\\s+(?:url\\()?(?:['\"])?([^'\"\\)\\s;]+)(?:['\"])?\\)?", Pattern.CASE_INSENSITIVE);

    private CssResourceScanner() {
    }

    static Set<String> scan(String css, String baseHref) {
        Set<String> result = new LinkedHashSet<>();
        if (css == null || css.isEmpty()) {
            return result;
        }
        Matcher matcher = URL_PATTERN.matcher(css);
        while (matcher.find()) {
            result.add(PathUtil.resolve(baseHref, matcher.group(2)));
        }
        matcher = IMPORT_PATTERN.matcher(css);
        while (matcher.find()) {
            result.add(PathUtil.resolve(baseHref, matcher.group(1)));
        }
        return result;
    }
}

