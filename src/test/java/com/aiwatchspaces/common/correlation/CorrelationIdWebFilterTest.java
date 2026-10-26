package com.aiwatchspaces.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CorrelationIdWebFilterTest {

    @Test
    void acceptsCanonicalUuidAndLowerCasesIt() {
        String id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301";
        assertThat(CorrelationIdWebFilter.resolve(id)).isEqualTo(id.toLowerCase());
    }

    @Test
    void replacesMissingOrInvalidValues() {
        for (String bad : new String[] {null, "", "abc", "1-1-1-1-1", "3f2504e0-4f89-11d3-9a0c-0305e82c3301x"}) {
            String resolved = CorrelationIdWebFilter.resolve(bad);
            assertThat(resolved).isNotEqualTo(bad);
            assertThat(UUID.fromString(resolved).toString()).isEqualTo(resolved);
        }
    }
}
