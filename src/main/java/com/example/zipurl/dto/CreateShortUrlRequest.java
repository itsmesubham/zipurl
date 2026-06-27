package com.example.zipurl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateShortUrlRequest(
        @NotBlank
        @Size(max = 2048)
        String longUrl,

        @Size(min = 3, max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, numbers, underscores, or hyphens")
        String customAlias
) {
}
