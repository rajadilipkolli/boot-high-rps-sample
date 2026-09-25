package com.example.highrps.author.batch;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.author.domain.AuthorRepository;
import com.example.highrps.author.dto.AuthorRequest;
import com.example.highrps.author.mapper.AuthorRequestToEntityMapper;
import com.example.highrps.infrastructure.batch.EntityBatchProcessor;
import com.example.highrps.shared.redis.DeletionMarkerHandler;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AuthorBatchProcessor implements EntityBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(AuthorBatchProcessor.class);

    private final AuthorRequestToEntityMapper mapper;
    private final AuthorRepository authorRepository;
    private final JsonMapper jsonMapper;
    private final DeletionMarkerHandler deletionMarkerHandler;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public AuthorBatchProcessor(
            AuthorRequestToEntityMapper mapper,
            AuthorRepository authorRepository,
            JsonMapper jsonMapper,
            DeletionMarkerHandler deletionMarkerHandler,
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.authorRepository = authorRepository;
        this.jsonMapper = jsonMapper;
        this.deletionMarkerHandler = deletionMarkerHandler;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getEntityType() {
        return "author";
    }

    /**
     * Applies the last payload for each email to its author row. Payloads with a deletion marker,
     * invalid JSON, or no usable email are skipped. Update failures are caught after insertion is
     * attempted, so a newly inserted row may remain. Database failures propagate and roll back the batch.
     *
     * @param payloads serialized author requests
     */
    @Override
    public void processUpserts(List<String> payloads) {
        // ── Phase 1 (no transaction): parse payloads + tombstone filter ──────────
        // Redis calls and JSON parsing happen outside any DB transaction so we don't
        // hold a connection while doing non-DB work.
        List<ParsedAuthor> parsedAuthors = payloads.stream()
                .map(payload -> {
                    String email = extractKey(payload);
                    if (email != null) {
                        // Skip if tombstone exists
                        if (deletionMarkerHandler.isDeleted("author", email)) {
                            log.debug("Skipping upsert for email {} because recent tombstone present", email);
                            return null;
                        }
                    }
                    try {
                        AuthorRequest req = jsonMapper.readValue(payload, AuthorRequest.class);
                        return new ParsedAuthor(email, req);
                    } catch (Exception e) {
                        log.warn("Failed to map author payload to entity: {}", payload, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(p -> p.email() != null)
                .collect(Collectors.toMap(ParsedAuthor::email, Function.identity(), (a, b) -> b))
                .values()
                .stream()
                .toList();

        if (parsedAuthors.isEmpty()) {
            return;
        }

        // ── Phase 2 (transaction): native insert + pessimistic refetch + saveAll ──
        // Sort emails to guarantee consistent lock-acquisition order across
        // concurrent transactions and prevent deadlocks.
        List<String> sortedEmails =
                parsedAuthors.stream().map(ParsedAuthor::email).sorted().collect(Collectors.toList());

        transactionTemplate.executeWithoutResult(status -> {
            // Step 2a: Best-effort insert of genuinely new rows.
            //          ON CONFLICT (email) DO NOTHING makes concurrent inserts safe.
            String insertSql = "INSERT INTO authors "
                    + "(id, first_name, middle_name, last_name, mobile, email, registered_at, version, created_at, modified_at)"
                    + " VALUES (nextval('authors_seq'), ?, ?, ?, ?, ?, now(), 0, now(), now())"
                    + " ON CONFLICT (email) DO NOTHING";

            jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    ParsedAuthor pa = parsedAuthors.get(i);
                    AuthorRequest req = pa.request();
                    ps.setString(1, req.firstName());
                    ps.setString(2, req.middleName());
                    ps.setString(3, req.lastName());
                    ps.setLong(4, req.mobile());
                    ps.setString(5, req.email());
                }

                @Override
                public int getBatchSize() {
                    return parsedAuthors.size();
                }
            });

            // Step 2b: Refetch under pessimistic write lock to get all rows
            // (both just-inserted and pre-existing) with exclusive locks.
            List<AuthorEntity> lockedAuthors = authorRepository.findByEmailInAllIgnoreCaseWithLock(sortedEmails);

            Map<String, AuthorEntity> existingByEmail = lockedAuthors.stream()
                    .collect(Collectors.toMap(
                            a -> a.getEmail().toLowerCase(Locale.ROOT), Function.identity(), (a1, a2) -> a1));

            // Step 2c: Apply payload data through the JPA mapper and collect for saveAll.
            List<AuthorEntity> entitiesToSave = new ArrayList<>();
            for (ParsedAuthor parsed : parsedAuthors) {
                AuthorEntity entity = existingByEmail.get(parsed.email());
                if (entity == null) {
                    // Row missing even after lock – should not happen given DO NOTHING,
                    // but skip gracefully to preserve existing skip-on-failure behaviour.
                    log.warn("Author row unexpectedly absent after insert for email: {}", parsed.email());
                    continue;
                }
                try {
                    mapper.updateAuthorEntity(parsed.request(), entity);
                    entitiesToSave.add(entity);
                    log.debug("Upserting author with email: {}", parsed.email());
                } catch (Exception e) {
                    log.warn("Failed to update author entity for email: {}", parsed.email(), e);
                    // Preserve existing skip-for-mapping-failures behaviour.
                }
            }

            // Step 2d: Save all (both new and updated)
            if (!entitiesToSave.isEmpty()) {
                try {
                    authorRepository.saveAll(entitiesToSave);
                    log.debug("Persisted batch of {} author entities", entitiesToSave.size());
                } catch (Exception e) {
                    log.error("Failed to persist batch of {} author entities", entitiesToSave.size(), e);
                    throw e;
                }
            }
        });
    }

    @Override
    public void processDeletes(List<String> keys) {
        if (keys.isEmpty()) return;
        try {
            List<String> lowerCaseKeys = keys.stream().map(String::toLowerCase).toList();
            long deletedRows = authorRepository.deleteByEmailInAllIgnoreCase(lowerCaseKeys);
            log.debug("Deleted {} author entities for {} keys", deletedRows, keys.size());
        } catch (Exception e) {
            log.warn("Failed to batch delete author entities for keys: {}", keys, e);
        }
    }

    @Override
    public String extractKey(String payload) {
        try {
            var node = jsonMapper.readTree(payload);
            String email = node.path("email").asString(null);
            if (email == null || email.isBlank()) {
                log.warn("Author payload missing email");
                return null;
            }
            return email.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            log.warn("Failed to extract email from author payload", e);
            return null;
        }
    }

    // Helper record to hold parsed data
    private record ParsedAuthor(String email, AuthorRequest request) {}
}
