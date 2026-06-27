package com.example.zipurl.exception;

public class ShortUrlNotFoundException extends RuntimeException {

    public ShortUrlNotFoundException(String alias) {
        super("Short URL not found for alias: " + alias);
    }
}
