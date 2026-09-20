package com.example.highrps.gatling.simulations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HighRpsSimulationTest {

    @Test
    void acceptsZeroWarmupDuration() {
        assertThat(HighRpsSimulation.injectionSteps(false, 10, 1, 0)).hasSize(2);
        assertThat(HighRpsSimulation.injectionSteps(false, 10, 1, 5)).hasSize(3);
        assertThat(HighRpsSimulation.injectionSteps(true, 10, 1, 0)).hasSize(8);
        assertThat(HighRpsSimulation.injectionSteps(true, 10, 1, 5)).hasSize(9);
    }

    @Test
    void omitsUnstablePerOperationLatencyAssertionsFromSmokeProfile() {
        assertThat(HighRpsSimulation.includePerOperationLatencyAssertions("smoke"))
                .isFalse();
        assertThat(HighRpsSimulation.includePerOperationLatencyAssertions("normal"))
                .isTrue();
        assertThat(HighRpsSimulation.includePerOperationLatencyAssertions("stress"))
                .isTrue();
    }
}
