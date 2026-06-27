package com.example.zipurl.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateShortUrlRequest(
        @NotBlank
        @Size(max = 2048)
        @URL(message = "must be a valid URL")
        String longUrl,

        @Size(min = 3, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, numbers, underscores, or hyphens")
        String customAlias,

        @Min(1)
        @Max(value = 315_360_000L, message = "must not exceed 10 years (315360000 seconds)")
        Long ttlSeconds
) {
}
