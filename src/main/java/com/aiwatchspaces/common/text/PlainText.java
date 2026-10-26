package com.aiwatchspaces.common.text;

import java.text.Normalizer;
import java.util.List;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;

/**
 * Plain-text input policy (SYSTEM_DESIGN §8.3): NFC + trim, no C0/C1 controls except {@code \n} and
 * {@code \t}, at most 20 line breaks, no markup ({@code <} followed by a letter, {@code /}, {@code !}
 * or {@code ?}), length in code points.
 */
public final class PlainText {

    public static final int MAX_LINE_BREAKS = 20;

    private PlainText() {
    }

    /**
     * Normalizes and validates a non-chat field. Violations throw {@code 400 VALIDATION_FAILED} with
     * one detail for {@code field}.
     */
    public static String normalize(String field, String input, int minLength, int maxLength) {
        if (input == null) {
            throw invalid(field, "NOT_NULL", "must not be null");
        }
        String text = Normalizer.normalize(input, Normalizer.Form.NFC).strip();
        int lineBreaks = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '\n') {
                lineBreaks++;
            } else if (cp != '\t' && isControl(cp)) {
                throw invalid(field, "CONTROL_CHARACTER", "must not contain control characters");
            }
            i += Character.charCount(cp);
        }
        if (lineBreaks > MAX_LINE_BREAKS) {
            throw invalid(field, "TOO_MANY_LINE_BREAKS", "must contain at most " + MAX_LINE_BREAKS + " line breaks");
        }
        if (containsMarkup(text)) {
            throw invalid(field, "MARKUP_NOT_ALLOWED", "must be plain text");
        }
        int length = text.codePointCount(0, text.length());
        if (length < minLength || length > maxLength) {
            throw invalid(field, "SIZE", "length must be between " + minLength + " and " + maxLength);
        }
        return text;
    }

    public static boolean containsMarkup(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '<') {
                char next = text.charAt(i + 1);
                if (Character.isLetter(next) || next == '/' || next == '!' || next == '?') {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isControl(int cp) {
        return cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F);
    }

    private static ApiException invalid(String field, String code, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, code, message)));
    }
}
