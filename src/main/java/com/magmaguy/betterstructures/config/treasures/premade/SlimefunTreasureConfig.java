package com.magmaguy.betterstructures.config.treasures.premade;

import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional Slimefun-flavoured treasure table.
 *
 * <p>This config is generated as {@code treasure_slimefun.yml} but is not assigned
 * to a generator automatically. Server owners can opt specific generators or
 * schematics into it just like any other BetterStructures treasure file.</p>
 */
public class SlimefunTreasureConfig extends TreasureConfigFields {

    public SlimefunTreasureConfig() {
        super("treasure_slimefun", true);
        setRawLoot(createLoot());
        setMean(1.5);
        setStandardDeviation(1.0);
    }

    private static Map<String, Object> createLoot() {
        Map<String, Object> loot = new LinkedHashMap<>();

        loot.put("common", bucket(70,
                entry("IRON_DUST", "1-4", 14),
                entry("GOLD_DUST", "1-3", 10),
                entry("COPPER_DUST", "1-4", 14),
                entry("TIN_DUST", "1-4", 12),
                entry("SILVER_DUST", "1-3", 8),
                entry("ALUMINUM_DUST", "1-3", 10),
                entry("LEAD_DUST", "1-3", 8),
                entry("RAG", "1-2", 7),
                entry("BANDAGE", "1-2", 5)));

        loot.put("rare", bucket(25,
                entry("STEEL_INGOT", "1-3", 16),
                entry("BRONZE_INGOT", "1-3", 14),
                entry("DURALUMIN_INGOT", "1-2", 12),
                entry("BILLON_INGOT", "1-2", 10),
                entry("DAMASCUS_STEEL_INGOT", "1-2", 6),
                entry("SPLINT", "1-2", 8),
                entry("VITAMINS", "1", 5)));

        loot.put("epic", bucket(5,
                entry("HARDENED_METAL_INGOT", "1", 12),
                entry("REINFORCED_ALLOY_INGOT", "1", 5),
                entry("GILDED_IRON", "1", 8),
                entry("ANVIL_TALISMAN", "1", 5),
                entry("MINER_TALISMAN", "1", 4),
                entry("HUNTER_TALISMAN", "1", 4),
                entry("TRAVELLER_TALISMAN", "1", 4)));

        return loot;
    }

    @SafeVarargs
    private static Map<String, Object> bucket(double weight, Map<String, Object>... entries) {
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("weight", weight);
        bucket.put("items", new ArrayList<>(List.of(entries)));
        return bucket;
    }

    private static Map<String, Object> entry(String slimefunItemId, String amount, double weight) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("slimefunItem", slimefunItemId);
        entry.put("amount", amount);
        entry.put("weight", weight);
        entry.put("info", "Slimefun item: " + slimefunItemId);
        return entry;
    }
}
