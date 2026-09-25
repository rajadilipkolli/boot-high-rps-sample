package com.example.highrps.gatling.simulations;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;

import com.example.highrps.gatling.config.LoadTestConfig;
import com.example.highrps.gatling.scenarios.AuthorScenario;
import com.example.highrps.gatling.scenarios.CommentScenario;
import com.example.highrps.gatling.scenarios.PostScenario;
import com.example.highrps.gatling.scenarios.ReadCommentsScenario;
import com.example.highrps.gatling.scenarios.ReadPostScenario;
import com.example.highrps.gatling.scenarios.TagScenario;
import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class HighRpsSimulation extends Simulation {

    HttpProtocolBuilder httpProtocol = http.baseUrl(LoadTestConfig.BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    ScenarioBuilder scn = scenario("High RPS Scenario")
            .randomSwitch()
            .on(
                    percent(LoadTestConfig.READ_POST_WEIGHT).then(ReadPostScenario.read()),
                    percent(LoadTestConfig.READ_COMMENTS_WEIGHT).then(ReadCommentsScenario.read()),
                    percent(LoadTestConfig.READ_TAG_POSTS_WEIGHT).then(TagScenario.read()),
                    percent(LoadTestConfig.CREATE_COMMENT_WEIGHT).then(CommentScenario.create()),
                    percent(LoadTestConfig.CREATE_POST_WEIGHT).then(PostScenario.create()),
                    percent(LoadTestConfig.REGISTER_AUTHOR_WEIGHT).then(AuthorScenario.register()),
                    percent(LoadTestConfig.UPDATE_POST_WEIGHT).then(PostScenario.update()),
                    percent(LoadTestConfig.DELETE_POST_WEIGHT).then(PostScenario.delete()),
                    percent(LoadTestConfig.UPDATE_COMMENT_WEIGHT).then(CommentScenario.update()),
                    percent(LoadTestConfig.DELETE_COMMENT_WEIGHT).then(CommentScenario.delete()));

    /**
     * Configures the selected load profile and its regression assertions.
     */
    public HighRpsSimulation() {
        System.out.println("=== Effective Per-Operation Distribution ===");
        System.out.println("Read Post: " + LoadTestConfig.READ_POST_WEIGHT + "%");
        System.out.println("Read Comments: " + LoadTestConfig.READ_COMMENTS_WEIGHT + "%");
        System.out.println("Read Tags: " + LoadTestConfig.READ_TAG_POSTS_WEIGHT + "%");
        System.out.println("Create Comment: " + LoadTestConfig.CREATE_COMMENT_WEIGHT + "%");
        System.out.println("Create Post: " + LoadTestConfig.CREATE_POST_WEIGHT + "%");
        System.out.println("Register Author: " + LoadTestConfig.REGISTER_AUTHOR_WEIGHT + "%");
        System.out.println("Update Post: " + LoadTestConfig.UPDATE_POST_WEIGHT + "%");
        System.out.println("Delete Post: " + LoadTestConfig.DELETE_POST_WEIGHT + "%");
        System.out.println("Update Comment: " + LoadTestConfig.UPDATE_COMMENT_WEIGHT + "%");
        System.out.println("Delete Comment: " + LoadTestConfig.DELETE_COMMENT_WEIGHT + "%");
        double total = LoadTestConfig.READ_POST_WEIGHT
                + LoadTestConfig.READ_COMMENTS_WEIGHT
                + LoadTestConfig.READ_TAG_POSTS_WEIGHT
                + LoadTestConfig.CREATE_COMMENT_WEIGHT
                + LoadTestConfig.CREATE_POST_WEIGHT
                + LoadTestConfig.REGISTER_AUTHOR_WEIGHT
                + LoadTestConfig.UPDATE_POST_WEIGHT
                + LoadTestConfig.DELETE_POST_WEIGHT
                + LoadTestConfig.UPDATE_COMMENT_WEIGHT
                + LoadTestConfig.DELETE_COMMENT_WEIGHT;
        System.out.println("Total: " + total + "%");
        System.out.println("============================================");

        double targetRps = LoadTestConfig.TARGET_RPS;
        int durationMins = LoadTestConfig.DURATION_MINUTES;
        int warmupMins = LoadTestConfig.WARMUP_MINUTES;

        boolean stressProfile = "stress".equalsIgnoreCase(LoadTestConfig.PROFILE);
        PopulationBuilder population =
                scn.injectOpen(injectionSteps(stressProfile, targetRps, durationMins, warmupMins));

        setUp(population)
                .protocols(httpProtocol)
                .assertions(assertionsForProfile(includePerOperationLatencyAssertions(LoadTestConfig.PROFILE))
                        .toArray(Assertion[]::new));
    }

    static boolean includePerOperationLatencyAssertions(String profile) {
        // smoke: too short to be stable; stress: intentionally breaches normal latency ceilings at 500-1000 RPS
        return !("smoke".equalsIgnoreCase(profile) || "stress".equalsIgnoreCase(profile));
    }

    /**
     * Builds global assertions and optional per-operation latency assertions.
     *
     * @param includePerOperationLatencyAssertions whether each operation should enforce latency thresholds
     * @return assertions for the active load-test profile
     */
    static List<Assertion> assertionsForProfile(boolean includePerOperationLatencyAssertions) {
        List<Assertion> assertions = new ArrayList<>();
        assertions.add(global().failedRequests().percent().lte(LoadTestConfig.MAX_ERROR_RATE));
        assertions.add(global().responseTime().percentile3().lte((int)
                (LoadTestConfig.BASELINE_P95_MS * (1.0 + LoadTestConfig.ALLOWED_P95_DELTA_PERCENT / 100.0))));
        assertions.add(global().responseTime().percentile4().lte((int)
                (LoadTestConfig.BASELINE_P99_MS * (1.0 + LoadTestConfig.ALLOWED_P99_DELTA_PERCENT / 100.0))));
        assertions.add(global().requestsPerSec()
                .gte(LoadTestConfig.BASELINE_THROUGHPUT
                        * (1.0 + LoadTestConfig.ALLOWED_THROUGHPUT_DELTA_PERCENT / 100.0)));

        List<String> operations = List.of(
                "author_register",
                "post_create",
                "post_update",
                "post_delete",
                "Verify Deleted Post",
                "post_read",
                "comment_create",
                "comment_update",
                "comment_delete",
                "Verify Deleted Comment",
                "comment_read",
                "tag_read");
        for (String operation : operations) {
            assertions.add(details(operation).failedRequests().percent().lte(LoadTestConfig.MAX_ERROR_RATE));
            if (includePerOperationLatencyAssertions) {
                assertions.add(details(operation).responseTime().percentile3().lte((int)
                        (LoadTestConfig.BASELINE_P95_MS * (1.0 + LoadTestConfig.ALLOWED_P95_DELTA_PERCENT / 100.0))));
                assertions.add(details(operation).responseTime().percentile4().lte((int)
                        (LoadTestConfig.BASELINE_P99_MS * (1.0 + LoadTestConfig.ALLOWED_P99_DELTA_PERCENT / 100.0))));
            }
        }
        return assertions;
    }

    /**
     * Builds the user-injection schedule for the selected load profile.
     *
     * <p>For the stress profile the schedule is:
     * <ol>
     *   <li>5s nothing</li>
     *   <li>optional warmup ramp</li>
     *   <li>durationMins @ 100 RPS</li>
     *   <li>rampDuration ramp 100→250</li>
     *   <li>durationMins @ 250 RPS</li>
     *   <li>rampDuration ramp 250→500</li>
     *   <li>durationMins @ 500 RPS</li>
     *   <li>rampDuration ramp 500→1000</li>
     *   <li>durationMins @ 1000 RPS</li>
     * </ol>
     * The ramp duration is {@code max(1, min(5, durationMins))} minutes,
     * so a {@code durationMins=1} run takes ~8 min total instead of ~20 min.
     *
     * @param stressProfile whether to use the stepped stress schedule
     * @param targetRps the steady-state request rate
     * @param durationMins the duration of each steady-state stage
     * @param warmupMins the optional warm-up duration
     * @return the ordered Gatling injection steps
     */
    static List<OpenInjectionStep> injectionSteps(
            boolean stressProfile, double targetRps, int durationMins, int warmupMins) {
        List<OpenInjectionStep> steps = new ArrayList<>();
        steps.add(nothingFor(5));

        if (warmupMins != 0) {
            double warmupTargetRps = stressProfile ? 100 : targetRps;
            steps.add(rampUsersPerSec(0).to(warmupTargetRps).during(Duration.ofMinutes(warmupMins)));
        }

        if (stressProfile) {
            // Ramp duration scales with plateau: max(1, min(5, durationMins))
            int rampMins = Math.clamp(durationMins, 1, 5);
            steps.add(constantUsersPerSec(100).during(Duration.ofMinutes(durationMins)));
            steps.add(rampUsersPerSec(100).to(250).during(Duration.ofMinutes(rampMins)));
            steps.add(constantUsersPerSec(250).during(Duration.ofMinutes(durationMins)));
            steps.add(rampUsersPerSec(250).to(500).during(Duration.ofMinutes(rampMins)));
            steps.add(constantUsersPerSec(500).during(Duration.ofMinutes(durationMins)));
            steps.add(rampUsersPerSec(500).to(1000).during(Duration.ofMinutes(rampMins)));
            steps.add(constantUsersPerSec(1000).during(Duration.ofMinutes(durationMins)));
        } else {
            steps.add(constantUsersPerSec(targetRps).during(Duration.ofMinutes(durationMins)));
        }

        return steps;
    }
}
