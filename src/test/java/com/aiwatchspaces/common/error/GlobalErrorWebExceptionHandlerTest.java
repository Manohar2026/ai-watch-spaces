package com.aiwatchspaces.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

class GlobalErrorWebExceptionHandlerTest {

    @Test
    void apiExceptionKeepsItsCodeAndStatus() {
        var mapped = GlobalErrorWebExceptionHandler.map(new ApiException(ErrorCode.ROOM_LOCKED, "Locked."));
        assertThat(mapped.code()).isEqualTo(ErrorCode.ROOM_LOCKED);
        assertThat(mapped.status().value()).isEqualTo(409);
        assertThat(mapped.message()).isEqualTo("Locked.");
    }

    @Test
    void frameworkStatusesMapToCatalogueCodes() {
        assertThat(GlobalErrorWebExceptionHandler.map(new ResponseStatusException(HttpStatus.NOT_FOUND)).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(GlobalErrorWebExceptionHandler.map(new ServerWebInputException("bad")).code())
                .isEqualTo(ErrorCode.MALFORMED_REQUEST);
        var methodNotAllowed = GlobalErrorWebExceptionHandler.map(new MethodNotAllowedException("PATCH", java.util.List.of()));
        assertThat(methodNotAllowed.code()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(methodNotAllowed.status().value()).isEqualTo(405);
    }

    @Test
    void rejectedExecutionIsServiceBusy() {
        var mapped = GlobalErrorWebExceptionHandler.map(new RejectedExecutionException());
        assertThat(mapped.code()).isEqualTo(ErrorCode.SERVICE_BUSY);
        assertThat(mapped.status().value()).isEqualTo(503);
    }

    @Test
    void unknownExceptionIsInternalErrorWithGenericMessage() {
        var mapped = GlobalErrorWebExceptionHandler.map(new IllegalStateException("secret"));
        assertThat(mapped.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(mapped.status().value()).isEqualTo(500);
        assertThat(mapped.message()).doesNotContain("secret");
    }

    @Test
    void everyErrorCodeHasAnErrorStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status().isError()).as(code.name()).isTrue();
        }
    }
}
