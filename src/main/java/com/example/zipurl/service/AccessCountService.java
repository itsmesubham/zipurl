package com.example.zipurl.service;

public interface AccessCountService {

    void recordAccess(String alias);

    long pendingAccessCount(String alias);
}
