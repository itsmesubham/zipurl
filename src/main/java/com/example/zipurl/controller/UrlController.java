package com.example.zipurl.controller;

import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.dto.ShortUrlResponse;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.service.UrlShorteningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Validated
@RestController
@RequestMapping("/api/urls")
public class UrlController {

    private final UrlShorteningService urlShorteningService;

    public UrlController(UrlShorteningService urlShorteningService) {
        this.urlShorteningService = urlShorteningService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShortUrlResponse createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrl shortUrl = urlShorteningService.createShortUrl(request);

        return toResponse(shortUrl);
    }

    @GetMapping("/{alias:[A-Za-z0-9_-]+}")
    public ShortUrlResponse getShortUrlMetadata(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, numbers, underscores, or hyphens")
            String alias
    ) {
        ShortUrl shortUrl = urlShorteningService.getShortUrl(alias);

        return toResponse(shortUrl);
    }

    private ShortUrlResponse toResponse(ShortUrl shortUrl) {
        return new ShortUrlResponse(
                shortUrl.getAlias(),
                buildShortUrl(shortUrl.getAlias()),
                shortUrl.getOriginalUrl(),
                shortUrl.getCreatedAt(),
                shortUrl.getAccessCount()
        );
    }

    private String buildShortUrl(String alias) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{alias}")
                .buildAndExpand(alias)
                .toUriString();
    }
}
