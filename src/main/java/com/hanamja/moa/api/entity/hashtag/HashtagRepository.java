package com.hanamja.moa.api.entity.hashtag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HashtagRepository extends JpaRepository<Hashtag, Long> {

    Boolean existsByName(String name);

    Optional<Hashtag> findByName(String name);
}
