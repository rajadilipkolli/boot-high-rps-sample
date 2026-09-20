package com.example.highrps.post.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface PostRepository extends JpaRepository<PostEntity, Long> {

    boolean existsByPostRefId(Long postRefId);

    @EntityGraph(attributePaths = {"authorEntity"})
    Optional<PostEntity> findByPostRefId(Long postRefId);

    @EntityGraph(attributePaths = {"tags", "details", "authorEntity", "tags.tagEntity"})
    List<PostEntity> findByPostRefIdIn(List<Long> postRefIds);

    /**
     * Deletes posts whose external identifiers match the supplied values.
     *
     * @param postRefIds external post identifiers to delete
     * @return the number of deleted posts
     */
    @Transactional
    long deleteByPostRefIdIn(List<Long> postRefIds);
}
