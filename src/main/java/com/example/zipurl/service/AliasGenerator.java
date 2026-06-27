package com.example.zipurl.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class AliasGenerator {

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate(int length) {
        StringBuilder alias = new StringBuilder(length);

        for (int index = 0; index < length; index++) {
            alias.append(BASE62[secureRandom.nextInt(BASE62.length)]);
        }

        return alias.toString();
    }
}
