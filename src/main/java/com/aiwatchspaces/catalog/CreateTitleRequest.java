package com.aiwatchspaces.catalog;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTitleRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description,
        @NotBlank @Size(max = 2000) String videoAssetUri,
        @Min(1) @Max(86400) int durationSeconds,
        @Size(max = 10) List<@NotBlank @Size(max = 80) String> genres) {
}

