package com.aiwatchspaces.variations;

import java.util.UUID;

import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Active subtitle track metadata reads (SYSTEM_DESIGN §4.9, §6.3). */
@RestController
@RequestMapping("/api/v1/titles/{titleId}/subtitles")
class SubtitleController {

    private final SubtitleReadService subtitles;
    private final DbExecutor db;

    SubtitleController(SubtitleReadService subtitles, DbExecutor db) {
        this.subtitles = subtitles;
        this.db = db;
    }

    @GetMapping
    Mono<SubtitleTrackResponse> getActive(
            @PathVariable UUID titleId,
            @RequestParam(required = false) String locale) {
        return db.call(() -> subtitles.getActive(titleId, locale));
    }
}
