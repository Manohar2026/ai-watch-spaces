package com.aiwatchspaces.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aiwatchspaces.common.error.ApiException;
import org.junit.jupiter.api.Test;

class PlainTextTest {

    @Test
    void normalizesToNfcAndTrims() {
        assertThat(PlainText.normalize("f", "  Café  ", 1, 80)).isEqualTo("Café");
    }

    @Test
    void rejectsMarkupButAllowsBareLessThan() {
        for (String markup : new String[] {"<b>x", "</p>", "<!-- c -->", "<?xml?>", "a<script>"}) {
            assertThatThrownBy(() -> PlainText.normalize("f", markup, 1, 80)).isInstanceOf(ApiException.class);
        }
        assertThat(PlainText.normalize("f", "a < b and 3<4", 1, 80)).isEqualTo("a < b and 3<4");
    }

    @Test
    void rejectsControlCharactersAndTooManyLineBreaks() {
        assertThatThrownBy(() -> PlainText.normalize("f", "a\u0000b", 1, 80)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlainText.normalize("f", "a\u0085b", 1, 80)).isInstanceOf(ApiException.class);
        assertThat(PlainText.normalize("f", "a\tb\nc", 1, 80)).isEqualTo("a\tb\nc");
        assertThatThrownBy(() -> PlainText.normalize("f", "a" + "\nx".repeat(21), 1, 200))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void lengthIsInCodePoints() {
        assertThat(PlainText.normalize("f", "🎬".repeat(80), 1, 80)).hasSize(160);
        assertThatThrownBy(() -> PlainText.normalize("f", "🎬".repeat(81), 1, 80)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PlainText.normalize("f", "  ", 1, 80)).isInstanceOf(ApiException.class);
    }
}
