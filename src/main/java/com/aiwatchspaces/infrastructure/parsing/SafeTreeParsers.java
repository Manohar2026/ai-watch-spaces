package com.aiwatchspaces.infrastructure.parsing;

import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLParser;

/**
 * Safe YAML/JSON parsing to Jackson 3 trees only (SYSTEM_DESIGN §8.4). Never binds to arbitrary
 * types and never enables default typing. Wiring confirmed at M1-V7:
 * <ul>
 *   <li>Jackson 3.1's YAML module is built on snakeyaml-engine, so SnakeYAML {@code LoaderOptions}
 *       are replaced by {@link LoadSettings} (code-point limit, no duplicate or recursive keys).</li>
 *   <li>Nesting depth is enforced by Jackson's {@link StreamReadConstraints}; duplicate keys also by
 *       {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}.</li>
 *   <li>Jackson's YAML parser does not expand aliases: it reports an alias as a plain string (the
 *       anchor name), so {@code maxAliasesForCollections} has no effect and a document with aliases
 *       would parse into silently wrong data. YAML aliases (including merge keys) are therefore
 *       rejected.</li>
 *   <li>The YAML backend does not apply {@code maxStringLength}; YAML documents are bounded by the
 *       code-point limit and field lengths by the JSON Schema.</li>
 * </ul>
 */
public final class SafeTreeParsers {

    public static final int MAX_NESTING_DEPTH = 32;
    public static final int MAX_STRING_LENGTH = 20_000;
    public static final int MAX_ALIASES_FOR_COLLECTIONS = 20;
    public static final int YAML_CODE_POINT_LIMIT = 2 * 1024 * 1024;

    private static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxStringLength(MAX_STRING_LENGTH)
            .build();

    private static final YAMLMapper YAML = YAMLMapper.builder(YAMLFactory.builder()
                    .loadSettings(LoadSettings.builder()
                            .setAllowDuplicateKeys(false)
                            .setMaxAliasesForCollections(MAX_ALIASES_FOR_COLLECTIONS)
                            .setAllowRecursiveKeys(false)
                            .setCodePointLimit(YAML_CODE_POINT_LIMIT)
                            .build())
                    .streamReadConstraints(CONSTRAINTS)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(CONSTRAINTS)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private SafeTreeParsers() {
    }

    /** Parses YAML to a tree. Throws {@link JacksonException} on any violation, including any alias. */
    public static JsonNode readYamlTree(String document) {
        rejectAliases(document);
        return YAML.readTree(document);
    }

    private static void rejectAliases(String document) {
        try (YAMLParser parser = (YAMLParser) YAML.createParser(document)) {
            while (parser.nextToken() != null) {
                if (parser.isCurrentAlias()) {
                    throw new StreamReadException(parser, "YAML aliases are not supported");
                }
            }
        }
    }

    /** Parses JSON to a tree. Throws {@link JacksonException} on any violation. */
    public static JsonNode readJsonTree(String document) {
        return JSON.readTree(document);
    }
}
