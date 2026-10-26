package com.aiwatchspaces.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiwatchspaces.common.error.ApiException;
import org.junit.jupiter.api.Test;

class LocaleTagsTest {

    @Test
    void canonicalizesValidTags() {
        assertThat(LocaleTags.canonicalize("f", "en")).isEqualTo("en");
        assertThat(LocaleTags.canonicalize("f", "en-in")).isEqualTo("en-IN");
        assertThat(LocaleTags.canonicalize("f", "zh-Hant-TW")).isEqualTo("zh-Hant-TW");
    }

    @Test
    void rejectsInvalidTags() {
        for (String bad : new String[] {"", " ", "not a locale", "en_US", "und", "x".repeat(17), null}) {
            assertThatThrownBy(() -> LocaleTags.canonicalize("f", bad)).as(String.valueOf(bad))
                    .isInstanceOf(ApiException.class);
        }
    }
}
