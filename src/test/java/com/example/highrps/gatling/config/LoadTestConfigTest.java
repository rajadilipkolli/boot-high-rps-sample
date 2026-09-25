package com.example.highrps.gatling.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoadTestConfigTest {

    @Test
    void resolvesProfileSpecificAssertionDefaults() {
        boolean stressProfile = "stress".equalsIgnoreCase(LoadTestConfig.PROFILE);

        assertThat(LoadTestConfig.PROFILE).isEqualTo(System.getProperty("profile", "smoke"));
        assertThat(LoadTestConfig.MAX_ERROR_RATE)
                .isEqualTo(doublePropertyOrDefault("maxErrorRate", stressProfile ? 5.0 : 1.0));
        assertThat(LoadTestConfig.BASELINE_P95_MS)
                .isEqualTo(intPropertyOrDefault("baseline.p95", stressProfile ? 5000 : 50));
        assertThat(LoadTestConfig.BASELINE_P99_MS)
                .isEqualTo(intPropertyOrDefault("baseline.p99", stressProfile ? 10000 : 100));
        assertThat(LoadTestConfig.ALLOWED_P95_DELTA_PERCENT)
                .isEqualTo(intPropertyOrDefault("allowed.p95.delta.percent", stressProfile ? 50 : 5));
        assertThat(LoadTestConfig.ALLOWED_P99_DELTA_PERCENT)
                .isEqualTo(intPropertyOrDefault("allowed.p99.delta.percent", stressProfile ? 50 : 10));
    }

    private static double doublePropertyOrDefault(String property, double defaultValue) {
        String value = System.getProperty(property);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    private static int intPropertyOrDefault(String property, int defaultValue) {
        String value = System.getProperty(property);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
}
