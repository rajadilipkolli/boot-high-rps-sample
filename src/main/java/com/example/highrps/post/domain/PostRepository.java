package com.example.highrps.post.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PostRepository extends JpaRepository<PostEntity, Long> {

    boolean existsByPostRefId(Long postRefId);

    @EntityGraph(attributePaths = {"authorEntity"})
    Optional<PostEntity> findByPostRefId(Long postRefId);

    @EntityGraph(attributePaths = {"tags", "details", "authorEntity", "tags.tagEntity"})
    List<PostEntity> findByPostRefIdIn(List<Long> postRefIds);

    /**
     * Refetches posts by postRefId under a pessimistic write lock.
     * Uses a native query with FOR NO KEY UPDATE to bypass a known Hibernate 6 bug
     * where applying PESSIMISTIC_WRITE to an entity with an optional=false @MapsId association fails.
     * Callers must sort {@code postRefIds} before passing them in to prevent deadlocks.
     */
    @Query(
            value = "SELECT * FROM posts WHERE post_ref_id IN :postRefIds ORDER BY post_ref_id FOR NO KEY UPDATE",
            nativeQuery = true)
    List<PostEntity> findByPostRefIdInWithLock(@Param("postRefIds") List<Long> postRefIds);

    /**
     * Deletes posts whose external identifiers match the supplied values.
     *
     * @param postRefIds external post identifiers to delete
     * @return the number of deleted posts
     */
    @Transactional
    long deleteByPostRefIdIn(List<Long> postRefIds);
}
