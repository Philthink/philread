package com.myreading.layout;

final class TextMetrics {
    private TextMetrics() {
    }

    static double tokenAdvance(String token, boolean combineUpright, PaginationSettings settings) {
        if (token == null || token.isEmpty()) {
            return 0.0d;
        }
        if (combineUpright && isCombineCandidate(token)) {
            return settings.getFontSize() * settings.getLineHeight();
        }
        int codePoint = token.codePointAt(0);
        if (Character.isWhitespace(codePoint)) {
            return settings.getFontSize() * settings.getLineHeight() * 0.33d;
        }
        if (isAsciiAlnum(codePoint)) {
            return settings.getFontSize() * settings.getLineHeight() * 0.5d;
        }
        return settings.getFontSize() * settings.getLineHeight();
    }

    static double breakAdvance(LayoutUnit.BreakKind kind, PaginationSettings settings) {
        switch (kind) {
            case PAGE:
            case COLUMN:
                return Double.POSITIVE_INFINITY;
            case BLOCK:
                return settings.getFontSize() * settings.getLineHeight() * 1.5d;
            case LINE:
            default:
                return settings.getFontSize() * settings.getLineHeight();
        }
    }

    static boolean isCombineCandidate(String token) {
        if (token == null || token.isEmpty() || token.length() > 4) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!(Character.isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAsciiAlnum(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z');
    }
}

