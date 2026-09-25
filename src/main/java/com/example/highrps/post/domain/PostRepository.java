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
     * Finds posts by external ID, ordered by that ID. Matching rows receive PostgreSQL
     * {@code FOR NO KEY UPDATE} locks for the surrounding transaction.
     *
     * @param postRefIds external post IDs to match
     * @return matching posts, or an empty list when none match
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
