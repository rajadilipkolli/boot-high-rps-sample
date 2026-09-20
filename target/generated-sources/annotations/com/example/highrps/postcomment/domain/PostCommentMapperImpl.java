package com.example.highrps.postcomment.domain;

import com.example.highrps.post.domain.PostEntity;
import com.example.highrps.postcomment.command.PostCommentCommandResult;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-20T14:40:42+0000",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.100.v20260826-1225, environment: Java 21.0.12.1 (Eclipse Adoptium)"
)
@Component
public class PostCommentMapperImpl extends PostCommentMapper {

    @Override
    public PostCommentCommandResult toResult(PostCommentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        long postId = 0L;
        long id = 0L;
        String title = null;
        String content = null;
        boolean published = false;
        OffsetDateTime publishedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime modifiedAt = null;

        Long postRefId = entityPostEntityPostRefId( entity );
        if ( postRefId != null ) {
            postId = postRefId;
        }
        if ( entity.getCommentRefId() != null ) {
            id = entity.getCommentRefId();
        }
        title = entity.getTitle();
        content = entity.getContent();
        published = entity.isPublished();
        publishedAt = entity.getPublishedAt();
        createdAt = entity.getCreatedAt();
        modifiedAt = entity.getModifiedAt();

        PostCommentCommandResult postCommentCommandResult = new PostCommentCommandResult( id, postId, title, content, published, publishedAt, createdAt, modifiedAt );

        return postCommentCommandResult;
    }

    @Override
    public List<PostCommentCommandResult> toResultList(List<PostCommentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PostCommentCommandResult> list = new ArrayList<PostCommentCommandResult>( entities.size() );
        for ( PostCommentEntity postCommentEntity : entities ) {
            list.add( toResult( postCommentEntity ) );
        }

        return list;
    }

    @Override
    public PostCommentCommandResult toResultFromRequest(PostCommentRequest request) {
        if ( request == null ) {
            return null;
        }

        long id = 0L;
        long postId = 0L;
        OffsetDateTime publishedAt = null;
        String title = null;
        String content = null;
        boolean published = false;
        LocalDateTime createdAt = null;
        LocalDateTime modifiedAt = null;

        if ( request.commentId() != null ) {
            id = request.commentId();
        }
        if ( request.postId() != null ) {
            postId = request.postId();
        }
        publishedAt = request.publishedAt();
        title = request.title();
        content = request.content();
        if ( request.published() != null ) {
            published = request.published();
        }
        createdAt = request.createdAt();
        modifiedAt = request.modifiedAt();

        PostCommentCommandResult postCommentCommandResult = new PostCommentCommandResult( id, postId, title, content, published, publishedAt, createdAt, modifiedAt );

        return postCommentCommandResult;
    }

    @Override
    public PostCommentCommandResult toResultFromRedis(PostCommentRedis redis) {
        if ( redis == null ) {
            return null;
        }

        long id = 0L;
        long postId = 0L;
        OffsetDateTime publishedAt = null;
        String title = null;
        String content = null;
        boolean published = false;
        LocalDateTime createdAt = null;
        LocalDateTime modifiedAt = null;

        if ( redis.getCommentId() != null ) {
            id = Long.parseLong( redis.getCommentId() );
        }
        if ( redis.getPostId() != null ) {
            postId = redis.getPostId();
        }
        publishedAt = redis.getPublishedAt();
        title = redis.getTitle();
        content = redis.getContent();
        published = redis.isPublished();
        createdAt = redis.getCreatedAt();
        modifiedAt = redis.getModifiedAt();

        PostCommentCommandResult postCommentCommandResult = new PostCommentCommandResult( id, postId, title, content, published, publishedAt, createdAt, modifiedAt );

        return postCommentCommandResult;
    }

    @Override
    public PostCommentRedis toRedis(PostCommentRequest request) {
        if ( request == null ) {
            return null;
        }

        PostCommentRedis postCommentRedis = new PostCommentRedis();

        if ( request.commentId() != null ) {
            postCommentRedis.setCommentId( String.valueOf( request.commentId() ) );
        }
        postCommentRedis.setPostId( request.postId() );
        postCommentRedis.setCreatedAt( request.createdAt() );
        postCommentRedis.setModifiedAt( request.modifiedAt() );
        postCommentRedis.setTitle( request.title() );
        postCommentRedis.setContent( request.content() );
        if ( request.published() != null ) {
            postCommentRedis.setPublished( request.published() );
        }
        postCommentRedis.setPublishedAt( request.publishedAt() );

        return postCommentRedis;
    }

    @Override
    public PostCommentRedis toRedisFromEntity(PostCommentEntity comment) {
        if ( comment == null ) {
            return null;
        }

        PostCommentRedis postCommentRedis = new PostCommentRedis();

        if ( comment.getCommentRefId() != null ) {
            postCommentRedis.setCommentId( String.valueOf( comment.getCommentRefId() ) );
        }
        postCommentRedis.setPostId( entityPostEntityPostRefId( comment ) );
        postCommentRedis.setCreatedAt( comment.getCreatedAt() );
        postCommentRedis.setModifiedAt( comment.getModifiedAt() );
        postCommentRedis.setTitle( comment.getTitle() );
        postCommentRedis.setContent( comment.getContent() );
        postCommentRedis.setPublished( comment.isPublished() );
        postCommentRedis.setPublishedAt( comment.getPublishedAt() );

        return postCommentRedis;
    }

    private Long entityPostEntityPostRefId(PostCommentEntity postCommentEntity) {
        PostEntity postEntity = postCommentEntity.getPostEntity();
        if ( postEntity == null ) {
            return null;
        }
        return postEntity.getPostRefId();
    }
}
