package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AliasGeneratorTests {

    private final AliasGenerator aliasGenerator = new AliasGenerator();

    @Test
    void generatesBase62AliasWithRequestedLength() {
        String alias = aliasGenerator.generate(8);

        assertThat(alias).hasSize(8);
        assertThat(alias).matches("^[A-Za-z0-9]+$");
    }

    @Test
    void rejectsInvalidLength() {
        assertThatThrownBy(() -> aliasGenerator.generate(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
