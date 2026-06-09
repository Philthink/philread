package com.myreading.layout;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class LayoutFingerprint {
    private LayoutFingerprint() {
    }

    static String compute(String chapterId, List<LayoutUnit> units, PaginationSettings settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            update(digest, chapterId);
            update(digest, settings.getFontFamily());
            update(digest, Double.toString(settings.getFontSize()));
            update(digest, Double.toString(settings.getLineHeight()));
            update(digest, Double.toString(settings.getColumnGap()));
            update(digest, Double.toString(settings.getPageWidth()));
            update(digest, Double.toString(settings.getPageHeight()));
            update(digest, Double.toString(settings.getMarginTop()));
            update(digest, Double.toString(settings.getMarginRight()));
            update(digest, Double.toString(settings.getMarginBottom()));
            update(digest, Double.toString(settings.getMarginLeft()));
            for (LayoutUnit unit : units) {
                update(digest, unit.getId());
                update(digest, unit.getType().name());
                update(digest, unit.getText());
                update(digest, unit.getAnnotation());
                update(digest, unit.getResourceHref());
                update(digest, Boolean.toString(unit.isCombineUpright()));
                update(digest, unit.getBreakKind() == null ? null : unit.getBreakKind().name());
                update(digest, Double.toString(unit.getFixedAdvance()));
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}

