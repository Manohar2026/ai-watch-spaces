package com.aiwatchspaces.common.error;

/** One entry of {@code details[]} in the error envelope. */
public record ErrorDetail(String field, String code, String message) {
}
