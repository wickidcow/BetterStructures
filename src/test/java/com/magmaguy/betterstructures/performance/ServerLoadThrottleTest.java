package com.magmaguy.betterstructures.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ServerLoadThrottleTest {
    private static final double PAUSE_TPS = 18.5;
    private static final double PAUSE_MSPT = 42.0;

    @Test
    void defaultBandsBackOffBeforeHardPause() {
        assertEquals(ServerLoadThrottle.Band.HEALTHY,
                ServerLoadThrottle.classify(19.8, 30.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.WARM,
                ServerLoadThrottle.classify(19.6, 30.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.ELEVATED,
                ServerLoadThrottle.classify(19.2, 30.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.HIGH,
                ServerLoadThrottle.classify(18.8, 30.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.CRITICAL,
                ServerLoadThrottle.classify(18.5, 30.0, PAUSE_TPS, PAUSE_MSPT));
    }

    @Test
    void msptCanEscalateThrottleEvenWhenTpsLooksHealthy() {
        assertEquals(ServerLoadThrottle.Band.WARM,
                ServerLoadThrottle.classify(20.0, 35.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.ELEVATED,
                ServerLoadThrottle.classify(20.0, 38.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.HIGH,
                ServerLoadThrottle.classify(20.0, 40.0, PAUSE_TPS, PAUSE_MSPT));
        assertEquals(ServerLoadThrottle.Band.CRITICAL,
                ServerLoadThrottle.classify(20.0, 42.0, PAUSE_TPS, PAUSE_MSPT));
    }

    @Test
    void defaultFourMillisecondPasteBudgetScalesProgressively() {
        long configuredBudget = 4_000_000L;
        assertEquals(4_000_000L,
                ServerLoadThrottle.adaptivePasteBudgetNanos(configuredBudget, ServerLoadThrottle.Band.HEALTHY));
        assertEquals(3_000_000L,
                ServerLoadThrottle.adaptivePasteBudgetNanos(configuredBudget, ServerLoadThrottle.Band.WARM));
        assertEquals(2_000_000L,
                ServerLoadThrottle.adaptivePasteBudgetNanos(configuredBudget, ServerLoadThrottle.Band.ELEVATED));
        assertEquals(1_000_000L,
                ServerLoadThrottle.adaptivePasteBudgetNanos(configuredBudget, ServerLoadThrottle.Band.HIGH));
        assertEquals(0L,
                ServerLoadThrottle.adaptivePasteBudgetNanos(configuredBudget, ServerLoadThrottle.Band.CRITICAL));
    }

    @Test
    void fitJobsAndChunkScansBecomeMoreConservativeWithLoad() {
        assertEquals(2, ServerLoadThrottle.adaptiveGenerationCooldownTicks(2, ServerLoadThrottle.Band.HEALTHY));
        assertEquals(4, ServerLoadThrottle.adaptiveGenerationCooldownTicks(2, ServerLoadThrottle.Band.WARM));
        assertEquals(8, ServerLoadThrottle.adaptiveGenerationCooldownTicks(2, ServerLoadThrottle.Band.ELEVATED));
        assertEquals(15, ServerLoadThrottle.adaptiveGenerationCooldownTicks(2, ServerLoadThrottle.Band.HIGH));

        assertEquals(8, ServerLoadThrottle.deferredChunkScanLimit(ServerLoadThrottle.Band.HEALTHY));
        assertEquals(4, ServerLoadThrottle.deferredChunkScanLimit(ServerLoadThrottle.Band.WARM));
        assertEquals(2, ServerLoadThrottle.deferredChunkScanLimit(ServerLoadThrottle.Band.ELEVATED));
        assertEquals(1, ServerLoadThrottle.deferredChunkScanLimit(ServerLoadThrottle.Band.HIGH));
        assertEquals(0, ServerLoadThrottle.deferredChunkScanLimit(ServerLoadThrottle.Band.CRITICAL));
    }
}
