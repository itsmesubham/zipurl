package com.example.zipurl.controller;

import com.example.zipurl.service.UrlShorteningService;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
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
    public void redirectToOriginalUrl(@PathVariable String alias, HttpServletResponse response) {
        String originalUrl = urlShorteningService.resolveOriginalUrl(alias);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, originalUrl);
        response.setContentLength(0);
    }
}
