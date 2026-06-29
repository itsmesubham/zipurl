package com.example.zipurl.controller;

import java.net.URI;

import com.example.zipurl.service.UrlShorteningService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final UrlShorteningService urlShorteningService;

    public RedirectController(UrlShorteningService urlShorteningService) {
        this.urlShorteningService = urlShorteningService;
    }

    @GetMapping("/{alias:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirectToOriginalUrl(@PathVariable String alias) {
        String originalUrl = urlShorteningService.resolveOriginalUrl(alias);
        return ResponseEntity.status(302)
                .location(URI.create(originalUrl))
                .header(HttpHeaders.CONTENT_LENGTH, "0")
                .build();
    }
}
