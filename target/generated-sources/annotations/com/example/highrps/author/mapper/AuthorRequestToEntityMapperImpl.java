package com.example.highrps.author.mapper;

import com.example.highrps.author.domain.AuthorEntity;
import com.example.highrps.author.dto.AuthorRequest;
import java.time.LocalDateTime;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-09-20T14:40:42+0000",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.46.100.v20260826-1225, environment: Java 21.0.12.1 (Eclipse Adoptium)"
)
@Component
public class AuthorRequestToEntityMapperImpl implements AuthorRequestToEntityMapper {

    @Override
    public AuthorEntity convert(AuthorRequest authorRequest) {
        if ( authorRequest == null ) {
            return null;
        }

        AuthorEntity authorEntity = new AuthorEntity();

        if ( authorRequest.createdAt() != null ) {
            authorEntity.setCreatedAt( authorRequest.createdAt() );
        }
        if ( authorRequest.modifiedAt() != null ) {
            authorEntity.setModifiedAt( authorRequest.modifiedAt() );
        }
        if ( authorRequest.firstName() != null ) {
            authorEntity.setFirstName( authorRequest.firstName() );
        }
        if ( authorRequest.middleName() != null ) {
            authorEntity.setMiddleName( authorRequest.middleName() );
        }
        if ( authorRequest.lastName() != null ) {
            authorEntity.setLastName( authorRequest.lastName() );
        }
        if ( authorRequest.mobile() != null ) {
            authorEntity.setMobile( authorRequest.mobile() );
        }
        if ( authorRequest.email() != null ) {
            authorEntity.setEmail( authorRequest.email() );
        }

        authorEntity.setRegisteredAt( LocalDateTime.now() );

        return authorEntity;
    }

    @Override
    public void updateAuthorEntity(AuthorRequest authorRequest, AuthorEntity authorEntity) {
        if ( authorRequest == null ) {
            return;
        }

        if ( authorRequest.createdAt() != null ) {
            authorEntity.setCreatedAt( authorRequest.createdAt() );
        }
        else {
            authorEntity.setCreatedAt( null );
        }
        if ( authorRequest.modifiedAt() != null ) {
            authorEntity.setModifiedAt( authorRequest.modifiedAt() );
        }
        else {
            authorEntity.setModifiedAt( null );
        }
        if ( authorRequest.firstName() != null ) {
            authorEntity.setFirstName( authorRequest.firstName() );
        }
        else {
            authorEntity.setFirstName( null );
        }
        if ( authorRequest.middleName() != null ) {
            authorEntity.setMiddleName( authorRequest.middleName() );
        }
        else {
            authorEntity.setMiddleName( null );
        }
        if ( authorRequest.lastName() != null ) {
            authorEntity.setLastName( authorRequest.lastName() );
        }
        else {
            authorEntity.setLastName( null );
        }
        if ( authorRequest.mobile() != null ) {
            authorEntity.setMobile( authorRequest.mobile() );
        }
        else {
            authorEntity.setMobile( null );
        }
        if ( authorRequest.email() != null ) {
            authorEntity.setEmail( authorRequest.email() );
        }
        else {
            authorEntity.setEmail( null );
        }
        if ( authorRequest.registeredAt() != null ) {
            authorEntity.setRegisteredAt( authorRequest.registeredAt() );
        }
        else {
            authorEntity.setRegisteredAt( null );
        }
    }
}
