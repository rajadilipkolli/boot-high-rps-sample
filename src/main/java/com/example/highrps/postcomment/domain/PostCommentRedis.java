package com.example.highrps.postcomment.domain;

import com.example.highrps.shared.Auditable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

@RedisHash("post-comments:entity")
public class PostCommentRedis extends Auditable implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id
    private String commentId;

    @Indexed
    private Long postId;

    private String title;
    private String content;
    private Boolean published = Boolean.FALSE;
    private OffsetDateTime publishedAt;

    public PostCommentRedis() {}

    public PostCommentRedis setPostId(Long postId) {
        this.postId = postId;
        return this;
    }

    public Long getPostId() {
        return postId;
    }

    public PostCommentRedis setCommentId(String commentId) {
        this.commentId = commentId;
        return this;
    }

    public String getCommentId() {
        return commentId;
    }

    public PostCommentRedis setTitle(String title) {
        this.title = title;
        return this;
    }

    public String getTitle() {
        return title;
    }

    public PostCommentRedis setContent(String content) {
        this.content = content;
        return this;
    }

    public String getContent() {
        return content;
    }

    /**
     * Sets the publication flag without changing the publication timestamp.
     *
     * @param published the flag to store; {@code null} is read as unpublished by {@link #isPublished()}
     * @return this instance
     */
    public PostCommentRedis setPublished(Boolean published) {
        this.published = published;
        return this;
    }

    /**
     * Returns whether the stored publication flag is true, treating {@code null} as false.
     *
     * @return true only when the stored flag is {@link Boolean#TRUE}
     */
    public boolean isPublished() {
        return Boolean.TRUE.equals(published);
    }

    public PostCommentRedis setPublishedAt(OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
        return this;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }
}
