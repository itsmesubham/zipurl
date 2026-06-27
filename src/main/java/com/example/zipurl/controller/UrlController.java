package com.example.zipurl.controller;

import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.dto.ShortUrlResponse;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.service.UrlShorteningService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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

        return new ShortUrlResponse(
                shortUrl.getAlias(),
                buildShortUrl(shortUrl.getAlias()),
                shortUrl.getOriginalUrl(),
                shortUrl.getCreatedAt()
        );
    }

    private String buildShortUrl(String alias) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{alias}")
                .buildAndExpand(alias)
                .toUriString();
    }
}
