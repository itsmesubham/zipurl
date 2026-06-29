package com.example.zipurl.repository;

import java.util.Optional;

import com.example.zipurl.model.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByAlias(String alias);

    boolean existsByAlias(String alias);
}
