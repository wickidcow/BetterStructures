package com.magmaguy.betterstructures.util;

import java.util.Locale;
import java.util.Set;

/**
 * Minecraft 26.3 item ids added after 26.2.
 *
 * <p>These are stored as strings so BetterStructures can still compile against Paper 26.2.
 * On a 26.3 server, Bukkit's Material registry resolves them normally. On older servers,
 * they remain recognized future ids and are skipped cleanly.</p>
 */
public final class Minecraft263ItemCatalog {

    private static final Set<String> ITEM_IDS = Set.of(
            "black_wool_slab",
            "black_wool_stairs",
            "blue_wool_slab",
            "blue_wool_stairs",
            "brown_wool_slab",
            "brown_wool_stairs",
            "cyan_wool_slab",
            "cyan_wool_stairs",
            "gray_wool_slab",
            "gray_wool_stairs",
            "green_wool_slab",
            "green_wool_stairs",
            "light_blue_wool_slab",
            "light_blue_wool_stairs",
            "light_gray_wool_slab",
            "light_gray_wool_stairs",
            "lime_wool_slab",
            "lime_wool_stairs",
            "magenta_wool_slab",
            "magenta_wool_stairs",
            "orange_wool_slab",
            "orange_wool_stairs",
            "pink_wool_slab",
            "pink_wool_stairs",
            "purple_wool_slab",
            "purple_wool_stairs",
            "red_wool_slab",
            "red_wool_stairs",
            "white_wool_slab",
            "white_wool_stairs",
            "yellow_wool_slab",
            "yellow_wool_stairs",
            "orange_poplar_leaves",
            "poplar_boat",
            "poplar_button",
            "poplar_chest_boat",
            "poplar_door",
            "poplar_fence",
            "poplar_fence_gate",
            "poplar_hanging_sign",
            "poplar_log",
            "poplar_planks",
            "poplar_pressure_plate",
            "poplar_sapling",
            "poplar_shelf",
            "poplar_sign",
            "poplar_slab",
            "poplar_stairs",
            "poplar_trapdoor",
            "poplar_wood",
            "red_poplar_leaves",
            "red_shrub",
            "shelf_mushroom",
            "stripped_poplar_log",
            "stripped_poplar_wood",
            "yellow_poplar_leaves",
            "black_cushion",
            "blue_cushion",
            "brown_cushion",
            "cyan_cushion",
            "gray_cushion",
            "green_cushion",
            "light_blue_cushion",
            "light_gray_cushion",
            "lime_cushion",
            "magenta_cushion",
            "orange_cushion",
            "pink_cushion",
            "purple_cushion",
            "red_cushion",
            "white_cushion",
            "yellow_cushion",
            "straw_bed",
            "black_concrete_slab",
            "black_concrete_stairs",
            "blue_concrete_slab",
            "blue_concrete_stairs",
            "brown_concrete_slab",
            "brown_concrete_stairs",
            "cyan_concrete_slab",
            "cyan_concrete_stairs",
            "gray_concrete_slab",
            "gray_concrete_stairs",
            "green_concrete_slab",
            "green_concrete_stairs",
            "light_blue_concrete_slab",
            "light_blue_concrete_stairs",
            "light_gray_concrete_slab",
            "light_gray_concrete_stairs",
            "lime_concrete_slab",
            "lime_concrete_stairs",
            "magenta_concrete_slab",
            "magenta_concrete_stairs",
            "orange_concrete_slab",
            "orange_concrete_stairs",
            "pink_concrete_slab",
            "pink_concrete_stairs",
            "purple_concrete_slab",
            "purple_concrete_stairs",
            "red_concrete_slab",
            "red_concrete_stairs",
            "white_concrete_slab",
            "white_concrete_stairs",
            "yellow_concrete_slab",
            "yellow_concrete_stairs",
            "abandoned_camp_map",
            "buried_ancient_city_map",
            "buried_mineshaft_map",
            "buried_treasure_map",
            "desert_pyramid_map",
            "desert_village_map",
            "plains_village_map",
            "savanna_village_map",
            "snowy_village_map",
            "taiga_village_map",
            "warm_ocean_ruins_map",
            "jungle_pyramid_map",
            "ocean_monument_map",
            "swamp_hut_map",
            "buried_trial_chambers_map",
            "woodland_mansion_map"
    );

    private Minecraft263ItemCatalog() {
    }

    public static boolean contains(String itemId) {
        if (itemId == null) return false;
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        return ITEM_IDS.contains(normalized);
    }

    public static Set<String> itemIds() {
        return ITEM_IDS;
    }
}
