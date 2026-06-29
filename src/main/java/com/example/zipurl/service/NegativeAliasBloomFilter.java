package com.example.zipurl.service;

import java.util.concurrent.atomic.AtomicLongArray;

final class NegativeAliasBloomFilter {

    private static final double DEFAULT_FALSE_POSITIVE_RATE = 0.03d;

    private final AtomicLongArray words;
    private final long bitMask;
    private final int hashFunctions;

    NegativeAliasBloomFilter(long expectedInsertions) {
        this(expectedInsertions, DEFAULT_FALSE_POSITIVE_RATE);
    }

    NegativeAliasBloomFilter(long expectedInsertions, double falsePositiveRate) {
        long safeExpectedInsertions = Math.max(1L, expectedInsertions);
        double safeFalsePositiveRate = Math.min(Math.max(falsePositiveRate, 0.0001d), 0.25d);
        long rawBitSize = (long) Math.ceil(-safeExpectedInsertions * Math.log(safeFalsePositiveRate) / (Math.log(2d) * Math.log(2d)));
        long bitSize = 1L;
        while (bitSize < rawBitSize) {
            bitSize <<= 1;
        }

        this.words = new AtomicLongArray((int) Math.max(1L, bitSize >>> 6));
        this.bitMask = bitSize - 1L;
        this.hashFunctions = Math.max(1, (int) Math.round((bitSize / (double) safeExpectedInsertions) * Math.log(2d)));
    }

    void put(String value) {
        long hash1 = mix64(value.hashCode() * 0x9E3779B97F4A7C15L);
        long hash2 = mix64(hash1 ^ 0xC2B2AE3D27D4EB4FL);
        if (hash2 == 0L) {
            hash2 = 0x9E3779B97F4A7C15L;
        }

        for (int i = 0; i < hashFunctions; i++) {
            long combined = hash1 + (long) i * hash2;
            int bitIndex = (int) (combined & bitMask);
            int wordIndex = bitIndex >>> 6;
            long bit = 1L << (bitIndex & 63);
            words.getAndUpdate(wordIndex, current -> current | bit);
        }
    }

    boolean mightContain(String value) {
        long hash1 = mix64(value.hashCode() * 0x9E3779B97F4A7C15L);
        long hash2 = mix64(hash1 ^ 0xC2B2AE3D27D4EB4FL);
        if (hash2 == 0L) {
            hash2 = 0x9E3779B97F4A7C15L;
        }

        for (int i = 0; i < hashFunctions; i++) {
            long combined = hash1 + (long) i * hash2;
            int bitIndex = (int) (combined & bitMask);
            int wordIndex = bitIndex >>> 6;
            long bit = 1L << (bitIndex & 63);
            if ((words.get(wordIndex) & bit) == 0L) {
                return false;
            }
        }

        return true;
    }

    private static long mix64(long value) {
        long z = value;
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
