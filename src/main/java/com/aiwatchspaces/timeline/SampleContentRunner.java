package com.aiwatchspaces.timeline;

import java.time.Duration;
import java.util.UUID;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Runs the optional local sample seeder once at startup when explicitly enabled. */
@Component
@ConditionalOnProperty(name = "aiws.seed-content.enabled", havingValue = "true")
class SampleContentRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleContentRunner.class);

    private final SampleContentSeeder seeder;
    private final DbExecutor db;
    private final String uploadedBy;

    SampleContentRunner(SampleContentSeeder seeder, DbExecutor db,
                        @Value("${aiws.seed-content.uploaded-by:}") String uploadedBy) {
        this.seeder = seeder;
        this.db = db;
        this.uploadedBy = uploadedBy;
    }

    @Override
    public void run(String... args) {
        if (uploadedBy == null || uploadedBy.isBlank()) {
            throw new IllegalStateException(
                    "Set AIWS_SEED_CONTENT_UPLOADED_BY to an existing app_users.id before enabling sample seeding.");
        }
        UUID userId;
        try {
            userId = UUID.fromString(uploadedBy);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AIWS_SEED_CONTENT_UPLOADED_BY must be a UUID.", e);
        }

        SampleContentSeeder.SeedResult result = db.call(() -> seeder.seed(userId))
                .block(Duration.ofSeconds(60));
        log.info("Sample catalogue seed completed: titleId={}, alreadySeeded={}",
                result.titleId(), result.alreadySeeded());
    }
}
