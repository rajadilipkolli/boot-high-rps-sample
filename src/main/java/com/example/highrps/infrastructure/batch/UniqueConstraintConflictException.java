package com.example.highrps.infrastructure.batch;

import java.util.List;

/**
 * Thrown by a batch processor's Phase-1 native insert when a row that was
 * expected to be new is absent after the INSERT … ON CONFLICT DO NOTHING,
 * meaning another concurrent transaction already holds that unique key.
 *
 * <p>The exception carries enough information for the scheduler's
 * individual-retry path to classify the DLQ reason as
 * {@code unique_constraint_conflict} without inspecting vendor SQL state.
 *
 * <p>This exception intentionally does <em>not</em> change retry count or
 * acknowledgement order; it is only used to label DLQ entries.
 */
public class UniqueConstraintConflictException extends RuntimeException {

    /** Human-readable entity type, e.g. {@code "post"} or {@code "author"}. */
    private final String entityType;

    /**
     * The constraint name as declared in the DDL, e.g.
     * {@code "uc_postentity_post_ref_id"}.
     */
    private final String constraintName;

    /**
     * The key values (e.g. postRefId or email) that collided.
     * May contain one or more elements depending on the unique index arity.
     */
    private final List<String> conflictingKeys;

    /**
     * Constructs a conflict exception.
     *
     * @param entityType     human-readable entity type label
     * @param constraintName the constraint label to report
     * @param conflictingKeys the key values to report
     * @throws NullPointerException if {@code conflictingKeys} or an element is {@code null}
     */
    public UniqueConstraintConflictException(String entityType, String constraintName, List<String> conflictingKeys) {
        super(buildMessage(entityType, constraintName, conflictingKeys));
        this.entityType = entityType;
        this.constraintName = constraintName;
        this.conflictingKeys = List.copyOf(conflictingKeys);
    }

    /**
     * Constructs a conflict exception with a cause.
     *
     * @param entityType     human-readable entity type label
     * @param constraintName the constraint label to report
     * @param conflictingKeys the key values to report
     * @param cause          the cause to retain
     * @throws NullPointerException if {@code conflictingKeys} or an element is {@code null}
     */
    public UniqueConstraintConflictException(
            String entityType, String constraintName, List<String> conflictingKeys, Throwable cause) {
        super(buildMessage(entityType, constraintName, conflictingKeys), cause);
        this.entityType = entityType;
        this.constraintName = constraintName;
        this.conflictingKeys = List.copyOf(conflictingKeys);
    }

    private static String buildMessage(String entityType, String constraintName, List<String> conflictingKeys) {
        return "Unique-constraint conflict on entity=" + entityType
                + " constraint=" + constraintName
                + " keys=" + conflictingKeys;
    }

    /** @return the entity type label, e.g. {@code "post"} */
    public String getEntityType() {
        return entityType;
    }

    /** @return the supplied constraint label, which may be {@code null} */
    public String getConstraintName() {
        return constraintName;
    }

    /** @return an immutable copy of the supplied key values */
    public List<String> getConflictingKeys() {
        return conflictingKeys;
    }
}
