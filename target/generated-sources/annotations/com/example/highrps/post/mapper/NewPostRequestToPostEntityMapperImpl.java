package com.example.highrps.post.mapper;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.post.domain.PostDetailsEntity;
import com.example.highrps.post.domain.PostDetailsResponse;
import com.example.highrps.post.domain.PostEntity;
import com.example.highrps.post.domain.TagEntity;
import com.example.highrps.post.domain.requests.NewPostRequest;
import java.util.Map;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-20T14:40:42+0000",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.100.v20260826-1225, environment: Java 21.0.12.1 (Eclipse Adoptium)"
)
@Component
public class NewPostRequestToPostEntityMapperImpl implements NewPostRequestToPostEntityMapper {

    @Override
    public PostEntity convert(NewPostRequest newPostRequest, Map<String, TagEntity> tagMap) {
        if ( newPostRequest == null ) {
            return null;
        }

        String title = null;
        String content = null;

        if ( newPostRequest.title() != null ) {
            title = newPostRequest.title();
        }
        if ( newPostRequest.content() != null ) {
            content = newPostRequest.content();
        }

        AuthorEntity authorEntity = null;

        PostEntity postEntity = new PostEntity( title, content, authorEntity );

        if ( newPostRequest.postId() != null ) {
            postEntity.setPostRefId( newPostRequest.postId() );
        }
        if ( newPostRequest.createdAt() != null ) {
            postEntity.setCreatedAt( newPostRequest.createdAt() );
        }
        if ( newPostRequest.modifiedAt() != null ) {
            postEntity.setModifiedAt( newPostRequest.modifiedAt() );
        }
        if ( newPostRequest.published() != null ) {
            postEntity.setPublished( newPostRequest.published() );
        }
        if ( newPostRequest.publishedAt() != null ) {
            postEntity.setPublishedAt( newPostRequest.publishedAt() );
        }
        if ( newPostRequest.details() != null ) {
            postEntity.setDetails( postDetailsResponseToPostDetailsEntity( newPostRequest.details(), tagMap ) );
        }

        afterMapping( newPostRequest, postEntity, tagMap );

        return postEntity;
    }

    @Override
    public void updatePostEntity(NewPostRequest newPostRequest, PostEntity postEntity, Map<String, TagEntity> tagMap) {
        if ( newPostRequest == null ) {
            return;
        }

        if ( newPostRequest.createdAt() != null ) {
            postEntity.setCreatedAt( newPostRequest.createdAt() );
        }
        else {
            postEntity.setCreatedAt( null );
        }
        if ( newPostRequest.modifiedAt() != null ) {
            postEntity.setModifiedAt( newPostRequest.modifiedAt() );
        }
        else {
            postEntity.setModifiedAt( null );
        }
        if ( newPostRequest.title() != null ) {
            postEntity.setTitle( newPostRequest.title() );
        }
        else {
            postEntity.setTitle( null );
        }
        if ( newPostRequest.content() != null ) {
            postEntity.setContent( newPostRequest.content() );
        }
        else {
            postEntity.setContent( null );
        }
        if ( newPostRequest.published() != null ) {
            postEntity.setPublished( newPostRequest.published() );
        }
        if ( newPostRequest.publishedAt() != null ) {
            postEntity.setPublishedAt( newPostRequest.publishedAt() );
        }
        else {
            postEntity.setPublishedAt( null );
        }
        if ( newPostRequest.details() != null ) {
            if ( postEntity.getDetails() == null ) {
                postEntity.setDetails( new PostDetailsEntity() );
            }
            postDetailsResponseToPostDetailsEntity1( newPostRequest.details(), tagMap, postEntity.getDetails() );
        }
        else {
            postEntity.setDetails( null );
        }

        afterMapping( newPostRequest, postEntity, tagMap );
    }

    protected PostDetailsEntity postDetailsResponseToPostDetailsEntity(PostDetailsResponse postDetailsResponse, Map<String, TagEntity> tagMap) {
        if ( postDetailsResponse == null ) {
            return null;
        }

        PostDetailsEntity postDetailsEntity = new PostDetailsEntity();

        if ( postDetailsResponse.detailsKey() != null ) {
            postDetailsEntity.setDetailsKey( postDetailsResponse.detailsKey() );
        }
        if ( postDetailsResponse.createdBy() != null ) {
            postDetailsEntity.setCreatedBy( postDetailsResponse.createdBy() );
        }

        return postDetailsEntity;
    }

    protected void postDetailsResponseToPostDetailsEntity1(PostDetailsResponse postDetailsResponse, Map<String, TagEntity> tagMap, PostDetailsEntity mappingTarget) {
        if ( postDetailsResponse == null ) {
            return;
        }

        if ( postDetailsResponse.detailsKey() != null ) {
            mappingTarget.setDetailsKey( postDetailsResponse.detailsKey() );
        }
        else {
            mappingTarget.setDetailsKey( null );
        }
        if ( postDetailsResponse.createdBy() != null ) {
            mappingTarget.setCreatedBy( postDetailsResponse.createdBy() );
        }
        else {
            mappingTarget.setCreatedBy( null );
        }
    }
}
