package com.example.zipurl.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.access-count", name = "mode", havingValue = "disabled")
public class DisabledAccessCountService implements AccessCountService {

    @Override
    public void recordAccess(String alias) {
        // Intentionally disabled for extreme-load scenarios.
    }
}
