package com.example.highrps.postcomment.domain.events;

import com.example.highrps.shared.DomainEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Domain event published when a post comment is deleted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostCommentDeletedEvent(Long commentId, Long postId) implements DomainEvent {
    @JsonProperty("__deleted")
    public boolean isDeleted() {
        return true;
    }
}
