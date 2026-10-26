package com.aiwatchspaces.common.text;

import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;

/** BCP 47 language-tag validation (SYSTEM_DESIGN §8.3). */
public final class LocaleTags {

    public static final int MAX_LENGTH = 16;

    private LocaleTags() {
    }

    /** Returns the canonical tag (e.g. {@code en-IN}) or throws {@code 400 VALIDATION_FAILED}. */
    public static String canonicalize(String field, String tag) {
        if (tag == null || tag.isBlank() || tag.length() > MAX_LENGTH) {
            throw invalid(field);
        }
        try {
            String canonical = new Locale.Builder().setLanguageTag(tag.strip()).build().toLanguageTag();
            if (canonical.isEmpty() || canonical.equals("und") || canonical.length() > MAX_LENGTH) {
                throw invalid(field);
            }
            return canonical;
        } catch (IllformedLocaleException e) {
            throw invalid(field);
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, "INVALID_LOCALE", "must be a BCP 47 language tag")));
    }
}
