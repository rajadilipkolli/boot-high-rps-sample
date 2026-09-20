package com.example.highrps.post.mapper;

import com.example.highrps.post.domain.PostEntity;
import com.example.highrps.post.domain.PostTagEntity;
import com.example.highrps.post.domain.TagResponse;
import com.example.highrps.post.query.PostProjection;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueCheckStrategy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface PostEntityToPostProjectionMapper {

    @Mapping(target = "authorEmail", source = "authorEntity.email")
    @Mapping(target = "postId", source = "postRefId")
    PostProjection fromEntity(PostEntity postEntity);

    @Mapping(target = "tagName", source = "tagEntity.tagName")
    @Mapping(target = "tagDescription", source = "tagEntity.tagDescription")
    @Mapping(target = "id", source = "tagEntity.id")
    TagResponse fromPostTagEntity(PostTagEntity postTagEntity);
}
