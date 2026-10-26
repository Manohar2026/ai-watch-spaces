package com.aiwatchspaces.m1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.aiwatchspaces.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/** M1-V9: the modular data-jpa-test starter provides the JPA slice, here on Testcontainers PostgreSQL 17. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DataJpaSliceIT {

    @Autowired
    JsonbProbeRepository repository;

    @Test
    void jpaSliceRunsAgainstPostgres() {
        var payload = JsonMapper.builder().build().createObjectNode().put("k", "v");
        repository.saveAndFlush(new JsonbProbe(UUID.randomUUID(), payload, Map.of(), Instant.now()));
        assertThat(repository.count()).isGreaterThanOrEqualTo(1);
    }
}
