package com.example.highrps.author.domain;

import com.example.highrps.shared.ResourceNotFoundException;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuthorRepository extends JpaRepository<AuthorEntity, Long> {

    boolean existsByEmailIgnoreCase(String email);

    @Query("select a from AuthorEntity a where lower(a.email) in :emails")
    List<AuthorEntity> findByEmailInAllIgnoreCase(@Param("emails") List<String> emails);

    /**
     * Finds authors whose lower-case emails match {@code emails}, ordered by lower-case email.
     * Matching rows receive a pessimistic write lock for the surrounding transaction.
     *
     * @param emails lower-case email values to match
     * @return matching authors, or an empty list when none match
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthorEntity a where lower(a.email) in :emails order by lower(a.email)")
    List<AuthorEntity> findByEmailInAllIgnoreCaseWithLock(@Param("emails") List<String> emails);

    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("delete from AuthorEntity a where lower(a.email) in :emails")
    int deleteByEmailInAllIgnoreCase(@Param("emails") List<String> emails);

    Optional<AuthorEntity> findByEmailIgnoreCase(String email);

    AuthorEntity getReferenceByEmail(String email);

    // Default convenience method
    default AuthorEntity getByEmail(String email) {
        return findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("Author not found with email: " + email));
    }
}
