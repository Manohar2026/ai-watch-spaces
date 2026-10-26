package com.aiwatchspaces.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** M1-V7 / SYSTEM_DESIGN §8.4: aliases, nesting, size and duplicate keys. */
class SafeTreeParsersTest {

    @Test
    void parsesOrdinaryYamlToATree() {
        JsonNode tree = SafeTreeParsers.readYamlTree("""
                schemaVersion: 1
                events:
                  - type: TRIVIA
                    startMs: 1000
                    text: "Plain text"
                """);
        assertThat(tree.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(tree.get("events").get(0).get("startMs").asInt()).isEqualTo(1000);
    }

    @Test
    void rejectsYamlDuplicateKeys() {
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree("a: 1\na: 2\n"))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsYamlAliasExpansionBomb() {
        StringBuilder yaml = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i <= 25; i++) {
            yaml.append("a").append(i).append(": &a").append(i).append(" [");
            for (int j = 0; j < 10; j++) {
                yaml.append(j == 0 ? "" : ", ").append("*a").append(i - 1);
            }
            yaml.append("]\n");
        }
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree(yaml.toString()))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("aliases are not supported");
    }

    @Test
    void rejectsAnySingleAliasAndMergeKey() {
        // Jackson does not expand aliases; accepting them would yield the anchor name as data.
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree("a: &x [1, 2]\nb: *x\n"))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree("base: &b {k: v}\nother:\n  <<: *b\n"))
                .isInstanceOf(JacksonException.class);
        assertThat(SafeTreeParsers.readYamlTree("a: &x [1, 2]\n").get("a").size()).isEqualTo(2);
    }

    @Test
    void acceptsYamlNestingAtTheLimitAndRejectsDeeper() {
        assertThat(SafeTreeParsers.readYamlTree(nestedYaml(SafeTreeParsers.MAX_NESTING_DEPTH))).isNotNull();
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree(nestedYaml(SafeTreeParsers.MAX_NESTING_DEPTH + 1)))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("nesting depth");
    }

    @Test
    void yamlStringLengthIsBoundedOnlyByTheCodePointLimit() {
        // Recorded M1-V7 behaviour: the YAML backend ignores maxStringLength (§8.4 sets that limit for JSON).
        String long25k = "k: " + "a".repeat(25_000) + "\n";
        assertThat(SafeTreeParsers.readYamlTree(long25k).get("k").asString()).hasSize(25_000);
    }

    @Test
    void rejectsYamlDocumentOverTheCodePointLimit() {
        StringBuilder yaml = new StringBuilder();
        int i = 0;
        while (yaml.length() <= SafeTreeParsers.YAML_CODE_POINT_LIMIT) {
            yaml.append("k").append(i++).append(": ").append("v".repeat(100)).append('\n');
        }
        assertThatThrownBy(() -> SafeTreeParsers.readYamlTree(yaml.toString()))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("code points");
    }

    @Test
    void yamlTagsAreNotInstantiated() {
        JsonNode tree = SafeTreeParsers.readYamlTree("x: !!java.lang.ProcessBuilder [\"id\"]\n");
        assertThat(tree.get("x").isArray()).isTrue();
    }

    @Test
    void jsonEnforcesDuplicatesNestingAndStringLength() {
        assertThatThrownBy(() -> SafeTreeParsers.readJsonTree("{\"a\":1,\"a\":2}"))
                .isInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> SafeTreeParsers.readJsonTree("[".repeat(33) + "]".repeat(33)))
                .isInstanceOf(JacksonException.class);
        assertThat(SafeTreeParsers.readJsonTree("[".repeat(32) + "]".repeat(32))).isNotNull();
        assertThatThrownBy(() -> SafeTreeParsers.readJsonTree("\"" + "a".repeat(20_001) + "\""))
                .isInstanceOf(JacksonException.class);
    }

    private static String nestedYaml(int depth) {
        StringBuilder yaml = new StringBuilder();
        for (int level = 0; level < depth - 1; level++) {
            yaml.append("  ".repeat(level)).append("k").append(level).append(":\n");
        }
        yaml.append("  ".repeat(depth - 1)).append("leaf: 1\n");
        return yaml.toString();
    }
}
