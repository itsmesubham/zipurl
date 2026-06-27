package com.example.zipurl.exception;

public class AliasGenerationException extends RuntimeException {

    public AliasGenerationException() {
        super("Could not generate a unique alias");
    }
}
