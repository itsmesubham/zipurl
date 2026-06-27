package com.example.zipurl.service;

import java.time.Instant;

import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.repository.ShortUrlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseAccessCountService implements AccessCountService {

    private final ShortUrlRepository shortUrlRepository;

    public DatabaseAccessCountService(ShortUrlRepository shortUrlRepository) {
        this.shortUrlRepository = shortUrlRepository;
    }

    @Override
    @Transactional
    public void recordAccess(String alias) {
        int updatedRows = shortUrlRepository.addAccessCountForActiveAlias(alias, 1, Instant.now());
        if (updatedRows == 0) {
            throw new ShortUrlNotFoundException(alias);
        }
    }
}
