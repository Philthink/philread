package com.myreading.layout;

import java.util.ArrayList;
import java.util.List;

final class TextTokenizer {
    private TextTokenizer() {
    }

    static List<String> tokenize(String text, boolean combineUpright) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                tokens.add(new String(Character.toChars(codePoint)));
                index += charCount;
                continue;
            }
            if (combineUpright && TextMetrics.isAsciiAlnum(codePoint)) {
                int start = index;
                int count = 0;
                while (index < text.length() && count < 4) {
                    int cp = text.codePointAt(index);
                    if (!TextMetrics.isAsciiAlnum(cp)) {
                        break;
                    }
                    index += Character.charCount(cp);
                    count++;
                }
                tokens.add(text.substring(start, index));
                continue;
            }
            tokens.add(new String(Character.toChars(codePoint)));
            index += charCount;
        }
        return tokens;
    }
}

