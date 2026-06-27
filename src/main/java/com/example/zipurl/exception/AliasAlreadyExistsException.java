package com.example.zipurl.exception;

public class AliasAlreadyExistsException extends RuntimeException {

    public AliasAlreadyExistsException(String alias) {
        super("Alias already exists: " + alias);
    }

    public AliasAlreadyExistsException(String alias, Throwable cause) {
        super("Alias already exists: " + alias, cause);
    }
}
