package com.example.highrps.common;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import com.example.highrps.HighRpsApplication;
import com.example.highrps.author.batch.AuthorBatchProcessor;
import com.example.highrps.author.domain.AuthorRedisRepository;
import com.example.highrps.author.domain.AuthorRepository;
import com.example.highrps.infrastructure.batch.ScheduledBatchProcessor;
import com.example.highrps.post.domain.PostRedisRepository;
import com.example.highrps.post.domain.PostRepository;
import com.example.highrps.post.domain.PostTagRepository;
import com.example.highrps.post.domain.TagRepository;
import com.example.highrps.postcomment.domain.PostCommentRedisRepository;
import com.example.highrps.postcomment.domain.PostCommentRepository;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        classes = {HighRpsApplication.class, ContainersConfig.class, SQLContainerConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureTracing
@AutoConfigureMetrics
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvcTester mockMvcTester;

    @Autowired
    protected Cache<String, String> localCache;

    @Autowired
    protected RedisTemplate<String, String> redisTemplate;

    @Autowired
    protected AuthorRepository authorRepository;

    @Autowired
    protected PostRepository postRepository;

    @Autowired
    protected PostCommentRepository postCommentRepository;

    @Autowired
    protected TagRepository tagRepository;

    @Autowired
    protected PostTagRepository postTagRepository;

    @Autowired
    protected AuthorRedisRepository authorRedisRepository;

    @Autowired
    protected PostRedisRepository postRedisRepository;

    @Autowired
    protected PostCommentRedisRepository postCommentRedisRepository;

    @Autowired
    protected AuthorBatchProcessor authorBatchProcessor;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected List<ScheduledBatchProcessor> scheduledBatchProcessors;

    public void clearDatabase() {
        postCommentRepository.deleteAllInBatch();
        postTagRepository.deleteAllInBatch();
        postRepository.deleteAllInBatch();
        tagRepository.deleteAllInBatch();
        authorRepository.deleteAllInBatch();

        authorRedisRepository.deleteAll();
        postRedisRepository.deleteAll();
        postCommentRedisRepository.deleteAll();
        redisTemplate.execute(
                connection -> {
                    connection.serverCommands().flushDb();
                    return null;
                },
                true);
        localCache.invalidateAll();

        // Re-initialize consumer groups after flushDb
        if (scheduledBatchProcessors != null) {
            scheduledBatchProcessors.forEach(ScheduledBatchProcessor::init);
        }
    }
}
