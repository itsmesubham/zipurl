package com.example.zipurl.controller;

import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
public class RedirectController {

    @GetMapping("/{alias:[A-Za-z0-9_-]+}")
    public ResponseEntity<Void> redirectToOriginalUrl(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, numbers, underscores, or hyphens")
            String alias
    ) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "URL redirect is not implemented yet");
    }
}
