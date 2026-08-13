package com.magmaguy.betterstructures.config.generators;

import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.treasures.TreasureConfig;
import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.magmacore.config.CustomConfigFields;
import com.magmaguy.magmacore.thirdparty.CustomBiomeCompatibility;
import com.magmaguy.magmacore.util.Logger;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.*;

public class GeneratorConfigFields extends CustomConfigFields {

    @Getter
    @Setter
    private List<StructureType> structureTypes = new ArrayList<>(List.of(StructureType.UNDEFINED));
    @Getter
    @Setter
    private int lowestYLevel = -59;
    @Getter
    @Setter
    private int highestYLevel = 320;
    @Getter
    @Setter
    private List<String> validWorlds = null;
    @Getter
    @Setter
    private List<World.Environment> validWorldEnvironments = null;
    @Getter
    @Setter
    private List<String> validBiomesStrings = new ArrayList<>();
    @Getter
    @Setter
    private List<String> validBiomesNamespaces = new ArrayList<>();
    @Getter
    @Setter
    private String treasureFilename = null;
    @Getter
    private ChestContents chestContents = null;
    @Getter
    @Setter
    private String barrelTreasureFilename = "treasure_barrel_food.yml";
    @Getter
    private ChestContents barrelContents = null;
    @Getter
    @Setter
    private boolean generateLootInBarrels = true;

    public GeneratorConfigFields(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    public GeneratorConfigFields(String filename, boolean isEnabled, List<StructureType> structureTypes) {
        super(filename, isEnabled);
        this.structureTypes = structureTypes;
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.structureTypes = processEnumList("structureType", structureTypes, List.of(StructureType.UNDEFINED), StructureType.class, true);
        this.lowestYLevel = processInt("lowestYLevel", lowestYLevel, -59, false);
        this.highestYLevel = processInt("highestYLevel", highestYLevel, 320, false);
        this.validWorlds = processStringList("validWorlds", validWorlds, new ArrayList<>(), false);
        this.validWorldEnvironments = processEnumList("validWorldEnvironments", validWorldEnvironments, null, World.Environment.class, false);

        processBiomes();

        this.treasureFilename = processString("treasureFilename", treasureFilename, null, false);
        TreasureConfigFields treasureConfig = TreasureConfig.getConfigFields(treasureFilename);
        if (treasureConfig != null) {
            this.chestContents = treasureConfig.getChestContents();
        } else {
            Logger.warn("No valid treasure config file found for generator " + filename + " ! This will not spawn loot in chests until fixed.");
        }

        this.generateLootInBarrels = processBoolean("generateLootInBarrels", generateLootInBarrels, true, false);
        this.barrelTreasureFilename = processString("barrelTreasureFilename", barrelTreasureFilename, "treasure_barrel_food.yml", false);
        if (generateLootInBarrels) {
            TreasureConfigFields barrelTreasureConfig = TreasureConfig.getConfigFields(barrelTreasureFilename);
            if (barrelTreasureConfig != null) {
                this.barrelContents = barrelTreasureConfig.getChestContents();
            } else {
                Logger.warn("No valid barrel treasure config found for generator " + filename + " (looked for: " + barrelTreasureFilename + "). Barrels in this generator will be left empty until fixed.");
            }
        }
    }

    private void processBiomes() {
        if (validBiomesNamespaces == null) {
            validBiomesNamespaces = new ArrayList<>();
        } else {
            validBiomesNamespaces.clear();
        }

        if (fileConfiguration.contains("validBiomesV2") &&
                !fileConfiguration.getList("validBiomesV2", new ArrayList<>()).isEmpty()) {
            this.validBiomesStrings = processStringList("validBiomesV2", validBiomesStrings, validBiomesStrings, false);
        }

        Set<String> processedBiomes = new HashSet<>();
        List<String> standardizedBiomes = new ArrayList<>();
        for (String biomeString : validBiomesStrings) {
            String standardizedBiome = standardizeBiomeFormat(biomeString);
            if (standardizedBiome != null) {
                standardizedBiomes.add(standardizedBiome);
                processedBiomes.add(standardizedBiome);
            }
        }

        validBiomesNamespaces.addAll(standardizedBiomes);

        List<String> customBiomes = new ArrayList<>();
        for (String standardizedBiome : standardizedBiomes) {
            if (!standardizedBiome.startsWith("minecraft:")) continue;

            List<String> mappedCustomBiomes = CustomBiomeCompatibility.getCustomBiomes(standardizedBiome);
            for (String customBiome : mappedCustomBiomes) {
                if (!processedBiomes.contains(customBiome)) {
                    customBiomes.add(customBiome);
                    processedBiomes.add(customBiome);
                }
            }
        }

        validBiomesNamespaces.addAll(customBiomes);

        if (!customBiomes.isEmpty()) {
            List<String> fullBiomeList = new ArrayList<>(validBiomesStrings);
            fullBiomeList.addAll(customBiomes);
            validBiomesStrings = fullBiomeList;
            fileConfiguration.set("validBiomesV2", fullBiomeList);
        }
    }

    private String standardizeBiomeFormat(String biomeString) {
        if (biomeString == null || biomeString.isEmpty()) return null;

        // Custom biome namespaces may be supplied by Terra/Iris/Terralith after config load.
        if (biomeString.contains(":")) return biomeString.toLowerCase(Locale.ROOT);

        String normalizedBiome = biomeString.toLowerCase(Locale.ROOT);
        NamespacedKey biomeKey = NamespacedKey.fromString(normalizedBiome);
        if (biomeKey == null) {
            Logger.warn("Invalid biome name: " + biomeString);
            return null;
        }

        Biome biome = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(biomeKey);
        if (biome == null) {
            Logger.warn("Invalid biome name: " + biomeString);
            return null;
        }

        return biomeKey.toString();
    }

    public enum StructureType {
        UNDEFINED,
        UNDERGROUND_DEEP,
        UNDERGROUND_SHALLOW,
        SURFACE,
        SKY,
        LIQUID_SURFACE,
        DUNGEON
    }
}
