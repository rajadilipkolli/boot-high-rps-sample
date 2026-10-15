package com.example.highrps;

import com.example.highrps.author.command.AuthorCommandResult;
import com.example.highrps.author.command.CreateAuthorCommand;
import com.example.highrps.author.command.UpdateAuthorCommand;
import com.example.highrps.author.domain.AuthorRedis;
import com.example.highrps.author.domain.events.AuthorCreatedEvent;
import com.example.highrps.author.domain.events.AuthorDeletedEvent;
import com.example.highrps.author.domain.events.AuthorUpdatedEvent;
import com.example.highrps.author.dto.AuthorRequest;
import com.example.highrps.author.query.AuthorProjection;
import com.example.highrps.post.command.CreatePostCommand;
import com.example.highrps.post.command.PostCommandResult;
import com.example.highrps.post.command.UpdatePostCommand;
import com.example.highrps.post.domain.PostDetailsResponse;
import com.example.highrps.post.domain.PostRedis;
import com.example.highrps.post.domain.TagResponse;
import com.example.highrps.post.domain.events.PostCreatedEvent;
import com.example.highrps.post.domain.events.PostDeletedEvent;
import com.example.highrps.post.domain.events.PostUpdatedEvent;
import com.example.highrps.post.domain.requests.NewPostRequest;
import com.example.highrps.post.domain.requests.PostDetailsRequest;
import com.example.highrps.post.domain.requests.TagRequest;
import com.example.highrps.post.query.PostProjection;
import com.example.highrps.postcomment.command.CreatePostCommentCommand;
import com.example.highrps.postcomment.command.PostCommentCommandResult;
import com.example.highrps.postcomment.command.UpdatePostCommentCommand;
import com.example.highrps.postcomment.domain.PostCommentRedis;
import com.example.highrps.postcomment.domain.PostCommentRequest;
import com.example.highrps.postcomment.domain.events.PostCommentCreatedEvent;
import com.example.highrps.postcomment.domain.events.PostCommentDeletedEvent;
import com.example.highrps.postcomment.domain.events.PostCommentUpdatedEvent;
import com.example.highrps.postcomment.domain.vo.PostCommentId;
import com.example.highrps.postcomment.query.GetPostCommentQuery;
import com.example.highrps.postcomment.rest.CreatePostCommentRequest;
import com.example.highrps.postcomment.rest.UpdatePostCommentRequest;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class ProtobufRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * Adds reflection hints for Protobuf registries, Caffeine cache implementations, and application
     * data binding in native images.
     *
     * @param hints the registry to update
     * @param classLoader unused by this registrar
     */
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection()
                .registerType(
                        TypeReference.of("com.google.protobuf.ExtensionRegistry"),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
        hints.reflection()
                .registerType(
                        TypeReference.of("com.google.protobuf.ExtensionRegistryLite"),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                        MemberCategory.INVOKE_DECLARED_METHODS);

        String[] caffeineClasses = {
            "com.github.benmanes.caffeine.cache.SSLSMSW",
            "com.github.benmanes.caffeine.cache.SSLSMSWR",
            "com.github.benmanes.caffeine.cache.SSLSMS",
            "com.github.benmanes.caffeine.cache.SSLS",
            "com.github.benmanes.caffeine.cache.SSMSW",
            "com.github.benmanes.caffeine.cache.SSSMSW",
            "com.github.benmanes.caffeine.cache.SSMS",
            "com.github.benmanes.caffeine.cache.SSA"
        };
        for (String c : caffeineClasses) {
            hints.reflection()
                    .registerType(
                            TypeReference.of(c),
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                            MemberCategory.INVOKE_PUBLIC_METHODS,
                            MemberCategory.INVOKE_DECLARED_METHODS,
                            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        }

        // Register record components and accessors for event serialization, batch processing, DTOs, and Redis entities
        BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();
        Class<?>[] bindingClasses = {
            AuthorCreatedEvent.class,
            AuthorUpdatedEvent.class,
            AuthorDeletedEvent.class,
            PostCreatedEvent.class,
            PostUpdatedEvent.class,
            PostDeletedEvent.class,
            PostCommentCreatedEvent.class,
            PostCommentUpdatedEvent.class,
            PostCommentDeletedEvent.class,
            CreateAuthorCommand.class,
            UpdateAuthorCommand.class,
            AuthorCommandResult.class,
            CreatePostCommand.class,
            UpdatePostCommand.class,
            PostCommandResult.class,
            CreatePostCommentCommand.class,
            UpdatePostCommentCommand.class,
            PostCommentCommandResult.class,
            AuthorRequest.class,
            AuthorProjection.class,
            NewPostRequest.class,
            PostDetailsRequest.class,
            TagRequest.class,
            PostDetailsResponse.class,
            TagResponse.class,
            PostProjection.class,
            PostCommentRequest.class,
            CreatePostCommentRequest.class,
            UpdatePostCommentRequest.class,
            PostCommentId.class,
            GetPostCommentQuery.class,
            AuthorRedis.class,
            PostRedis.class,
            PostCommentRedis.class
        };
        for (Class<?> clazz : bindingClasses) {
            bindingRegistrar.registerReflectionHints(hints.reflection(), clazz);
        }
    }
}
