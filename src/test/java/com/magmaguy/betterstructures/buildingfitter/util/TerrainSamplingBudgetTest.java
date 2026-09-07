package com.magmaguy.betterstructures.buildingfitter.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainSamplingBudgetTest {

    @Test
    void normalTerrainScanKeepsOriginalDensity() {
        assertEquals(3, TerrainAdequacy.effectiveScanStep(3, 30, 30, 30));
    }

    @Test
    void largeTerrainScanRaisesStepToStayBounded() {
        int step = TerrainAdequacy.effectiveScanStep(3, 100, 50, 100);

        assertEquals(9, step);
        long samples = ceilDiv(100, step) * ceilDiv(50, step) * ceilDiv(100, step);
        assertTrue(samples <= 1024L);
    }

    @Test
    void normalTopologyScanKeepsOriginalDensity() {
        assertEquals(3, Topology.effectiveScanStep(3, 60, 60));
    }

    @Test
    void largeTopologyScanRaisesStepToStayBounded() {
        int step = Topology.effectiveScanStep(3, 100, 100);

        assertEquals(5, step);
        long samples = ceilDiv(100, step) * ceilDiv(100, step);
        assertTrue(samples <= 512L);
    }

    private static long ceilDiv(int value, int divisor) {
        return ((long) value + divisor - 1L) / divisor;
    }
}
