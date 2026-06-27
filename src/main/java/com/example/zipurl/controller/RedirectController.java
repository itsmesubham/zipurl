package com.example.zipurl.controller;

import java.net.URI;

import com.example.zipurl.service.UrlShorteningService;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class RedirectController {

    private final UrlShorteningService urlShorteningService;

    public RedirectController(UrlShorteningService urlShorteningService) {
        this.urlShorteningService = urlShorteningService;
    }

    @GetMapping("/{alias:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirectToOriginalUrl(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, numbers, underscores, or hyphens")
            String alias
    ) {
        String originalUrl = urlShorteningService.resolveOriginalUrl(alias);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }
}
