package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M1-V6 and guardrail 4 at runtime (the enforcer checks the dependency graph at build time):
 * Jackson 3 only, Jackson 2 annotations artifact retained, no Spring MVC.
 */
class ClasspathGuardTest {

    @Test
    void jackson2DatabindAndSpringBootJackson2AreAbsent() {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.databind.ObjectMapper"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.core.JsonFactory"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void jackson3UsesTheComFasterxmlAnnotationsArtifact() {
        record Sample(@JsonProperty("renamed") String value) {
        }
        assertThat(JsonMapper.builder().build().writeValueAsString(new Sample("v"))).isEqualTo("{\"renamed\":\"v\"}");
    }

    @Test
    void springMvcIsAbsent() {
        assertThatThrownBy(() -> Class.forName("org.springframework.web.servlet.DispatcherServlet"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
