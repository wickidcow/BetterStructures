package com.magmaguy.betterstructures.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Minecraft263ItemCatalogTest {

    @Test
    void containsExactlyTheKnown26_3ItemIds() {
        assertEquals(121, Minecraft263ItemCatalog.itemIds().size());
    }

    @Test
    void acceptsNamespacedAndBareIds() {
        assertTrue(Minecraft263ItemCatalog.contains("poplar_sapling"));
        assertTrue(Minecraft263ItemCatalog.contains("minecraft:poplar_sapling"));
        assertTrue(Minecraft263ItemCatalog.contains("MINECRAFT:POPLAR_SAPLING"));
        assertTrue(Minecraft263ItemCatalog.contains("buried_trial_chambers_map"));
        assertTrue(Minecraft263ItemCatalog.contains("woodland_mansion_map"));
    }

    @Test
    void excludesOldSnapshotMapNames() {
        assertFalse(Minecraft263ItemCatalog.contains("jungle_explorer_map"));
        assertFalse(Minecraft263ItemCatalog.contains("ocean_explorer_map"));
        assertFalse(Minecraft263ItemCatalog.contains("swamp_explorer_map"));
        assertFalse(Minecraft263ItemCatalog.contains("trial_chambers_map"));
        assertFalse(Minecraft263ItemCatalog.contains("woodland_explorer_map"));
    }
}
