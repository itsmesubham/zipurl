package com.example.zipurl.controller;

import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.dto.ShortUrlResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/urls")
public class UrlController {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShortUrlResponse createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "URL creation is not implemented yet");
    }
}
