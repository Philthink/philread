package com.myreading.epub.parse;

import java.util.ArrayDeque;
import java.util.Deque;

final class PathUtil {
    private PathUtil() {
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }

    static String resolve(String basePath, String href) {
        if (href == null || href.isEmpty()) {
            return normalize(basePath);
        }
        String cleanedHref = stripFragmentAndQuery(href);
        if (cleanedHref.startsWith("/")) {
            return normalize(cleanedHref.substring(1));
        }
        if (cleanedHref.contains(":") && !cleanedHref.startsWith("../") && !cleanedHref.startsWith("./")) {
            return cleanedHref;
        }
        return normalize(parent(basePath) + cleanedHref);
    }

    static String resolveWithFragment(String basePath, String href) {
        if (href == null || href.isEmpty()) {
            return normalize(basePath);
        }
        String resolved = resolve(basePath, href);
        String fragment = fragment(href);
        return fragment == null || fragment.isEmpty() ? resolved : resolved + "#" + fragment;
    }

    static String stripFragmentAndQuery(String href) {
        int hash = href.indexOf('#');
        int query = href.indexOf('?');
        int end = href.length();
        if (hash >= 0) {
            end = Math.min(end, hash);
        }
        if (query >= 0) {
            end = Math.min(end, query);
        }
        return href.substring(0, end);
    }

    static String fragment(String href) {
        int hash = href.indexOf('#');
        if (hash < 0) {
            return null;
        }
        return href.substring(hash + 1);
    }

    static String normalize(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (String part : stack) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
