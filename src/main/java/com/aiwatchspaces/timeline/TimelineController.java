package com.aiwatchspaces.timeline;

import java.util.List;
import java.util.UUID;

import com.aiwatchspaces.common.error.ApiException;
import com.aiwatchspaces.common.error.ErrorCode;
import com.aiwatchspaces.common.error.ErrorDetail;
import com.aiwatchspaces.infrastructure.db.DbExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Published timeline reads. Query parameters are seconds; persisted and response positions are ms. */
@RestController
@RequestMapping("/api/v1/titles/{titleId}/timeline")
class TimelineController {

    private static final int MAX_RANGE_SECONDS = 3_600;

    private final TimelineQueryService timelines;
    private final DbExecutor db;

    TimelineController(TimelineQueryService timelines, DbExecutor db) {
        this.timelines = timelines;
        this.db = db;
    }

    @GetMapping
    Mono<TimelineResponse> get(
            @PathVariable UUID titleId,
            @RequestParam("from") int fromSeconds,
            @RequestParam("to") int toSeconds) {
        if (fromSeconds < 0) {
            return Mono.error(validation("from", "must be a non-negative integer number of seconds"));
        }
        if (toSeconds < 0) {
            return Mono.error(validation("to", "must be a non-negative integer number of seconds"));
        }
        if (fromSeconds > toSeconds) {
            return Mono.error(validation("from", "must be less than or equal to to"));
        }
        if ((long) toSeconds - fromSeconds > MAX_RANGE_SECONDS) {
            return Mono.error(validation("to", "range must not exceed 3600 seconds"));
        }
        return db.call(() -> timelines.readCurrent(titleId, fromSeconds, toSeconds));
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                List.of(new ErrorDetail(field, "INVALID_RANGE", message)));
    }
}
