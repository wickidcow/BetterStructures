package com.magmaguy.betterstructures.chests;

import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.betterstructures.util.ItemStackSerialization;
import com.magmaguy.betterstructures.util.WeighedProbability;
import com.magmaguy.magmacore.util.Logger;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.configuration.MemorySection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ChestContents {

    private final List<ChestRarity> chestRarities = new ArrayList<>();
    private final TreasureConfigFields treasureConfigFields;
    private final HashMap<Integer, Double> rarityWeights = new HashMap<>();
    private static boolean warnedNullRarityPick = false;
    private static boolean warnedNullEntryPick = false;

    /*
    Entry format:
    - material: MATERIAL
      amount: min-max
      weight: weight
      mmoitem: TYPE@ID
      slimefunItem: SLIMEFUN_ITEM_ID
      serialized: serialized item map
      info: human-readable information for admins
     */
    public ChestContents(TreasureConfigFields treasureConfigFields) {
        this.treasureConfigFields = treasureConfigFields;
        if (treasureConfigFields.getRawLoot() == null) return;
        processRarities(treasureConfigFields.getRawLoot());
        for (int i = chestRarities.size() - 1; i >= 0; i--)
            rarityWeights.put(i, chestRarities.get(i).chestWeight);
    }

    private Material getMaterial(String string) {
        Material material = Material.matchMaterial(string);
        if (material == null) {
            Logger.warn("Invalid material detected! Problematic entry: " + string
                    + " in configuration file " + treasureConfigFields.getFilename());
        }
        return material;
    }

    private double getWeight(String string) {
        try {
            return Double.parseDouble(string);
        } catch (Exception exception) {
            Logger.warn("Invalid double value detected! Problematic entry: " + string
                    + " in configuration file " + treasureConfigFields.getFilename());
            return -1;
        }
    }

    private void processRarities(Map<String, Object> rawChestEntries) {
        for (Map.Entry<String, Object> entry : rawChestEntries.entrySet()) {
            if (!(entry.getValue() instanceof MemorySection raritySection)) {
                Logger.warn("Invalid rarity section '" + entry.getKey() + "' in configuration file "
                        + treasureConfigFields.getFilename());
                continue;
            }

            double weight = -1;
            List<ChestEntry> chestEntries = null;
            for (Map.Entry<String, Object> innerEntry : raritySection.getValues(false).entrySet()) {
                switch (innerEntry.getKey().toLowerCase(Locale.ROOT)) {
                    case "weight" -> weight = getWeight(innerEntry.getValue().toString());
                    case "items" -> chestEntries = processEntries((List<Map<String, ?>>) innerEntry.getValue());
                    default -> Logger.warn("Failed to read key " + innerEntry.getKey()
                            + " for configuration file " + treasureConfigFields.getFilename());
                }
            }

            if (weight <= 0) {
                Logger.warn("Skipping rarity '" + entry.getKey() + "' in " + treasureConfigFields.getFilename()
                        + " because its weight is not greater than zero.");
                continue;
            }
            if (chestEntries == null || chestEntries.isEmpty()) {
                Logger.warn("Skipping rarity '" + entry.getKey() + "' in " + treasureConfigFields.getFilename()
                        + " because it contains no valid loot entries.");
                continue;
            }
            chestRarities.add(new ChestRarity(weight, chestEntries));
        }
    }

    private ItemStack getSerializedItemStack(Map<String, Object> deserializedItemStack, String string) {
        try {
            // 2.7.0 corrected this direction: config maps must be deserialized into ItemStacks.
            return ItemStackSerialization.deserializeItem(deserializedItemStack);
        } catch (Exception ex) {
            Logger.warn("Invalid serialized value detected! Problematic entry: " + string
                    + " for configuration file " + treasureConfigFields.getFilename());
            ex.printStackTrace();
            return null;
        }
    }

    private boolean getProcedurallyGeneratedEnchantments(String string) {
        try {
            return Boolean.parseBoolean(string);
        } catch (Exception ex) {
            Logger.warn("Invalid boolean value detected! Problematic entry: " + string
                    + " for configuration file " + treasureConfigFields.getFilename());
            return false;
        }
    }

    private ItemStack getMMOItemsItemStack(String string) {
        try {
            String[] args = string.split("@");
            MMOItems mmo = MMOItems.plugin;
            MMOItem mmoitem = mmo.getMMOItem(mmo.getTypes().get(args[0]), args[1]);
            if (mmoitem == null) throw new NullPointerException("mmo item is null");
            return mmoitem.newBuilder().build();
        } catch (Exception ex) {
            Logger.warn("Invalid mmo item detected! Problematic entry: " + string
                    + " in " + treasureConfigFields.getFilename());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<ChestEntry> processEntries(List<Map<String, ?>> rawChestEntries) {
        List<ChestEntry> chestEntries = new ArrayList<>();
        if (rawChestEntries == null) return chestEntries;

        for (Map<String, ?> rawChestEntry : rawChestEntries) {
            Material material = null;
            int minAmount = -1;
            int maxAmount = -1;
            double weight = -1;
            boolean procedurallyGeneratedEnchantments = false;
            ItemStack itemStack = null;
            String slimefunItemId = null;

            for (Map.Entry<String, ?> entry : rawChestEntry.entrySet()) {
                if (entry.getValue() == null) {
                    Logger.warn("Null value for loot key '" + entry.getKey() + "' in "
                            + treasureConfigFields.getFilename());
                    continue;
                }

                String value = entry.getValue().toString();
                switch (entry.getKey().toLowerCase(Locale.ROOT)) {
                    case "material" -> material = getMaterial(value);
                    case "amount" -> {
                        try {
                            if (value.contains("-")) {
                                String[] amounts = value.split("-", 2);
                                minAmount = Integer.parseInt(amounts[0]);
                                maxAmount = Integer.parseInt(amounts[1]);
                            } else {
                                minAmount = Integer.parseInt(value);
                                maxAmount = minAmount;
                            }
                        } catch (Exception exception) {
                            Logger.warn("Invalid amount detected! Problematic entry: " + value
                                    + " in file " + treasureConfigFields.getFilename());
                        }
                    }
                    case "weight" -> weight = getWeight(value);
                    case "mmoitem", "mmoitems" -> itemStack = getMMOItemsItemStack(value);
                    case "slimefun", "slimefunitem", "slimefunitems", "sfitem" -> slimefunItemId = value.trim();
                    case "serialized" -> itemStack = getSerializedItemStack((Map<String, Object>) entry.getValue(), value);
                    case "procedurallygenerateenchantments" ->
                            procedurallyGeneratedEnchantments = getProcedurallyGeneratedEnchantments(value);
                    case "info" -> {
                    }
                    default -> Logger.warn("Failed to read key " + entry.getKey()
                            + " for configuration file " + treasureConfigFields.getFilename());
                }
            }

            if (minAmount < 1 || maxAmount < minAmount) {
                Logger.warn("Skipping invalid loot entry in " + treasureConfigFields.getFilename()
                        + ": amount must be at least 1 and max must be >= min.");
                continue;
            }
            if (weight <= 0) {
                Logger.warn("Skipping invalid loot entry in " + treasureConfigFields.getFilename()
                        + ": weight must be greater than zero.");
                continue;
            }
            if (material == null && itemStack == null && (slimefunItemId == null || slimefunItemId.isBlank())) {
                Logger.warn("Skipping loot entry in " + treasureConfigFields.getFilename()
                        + " because it has no valid material, serialized/MMOItem, or Slimefun item id.");
                continue;
            }

            chestEntries.add(new ChestEntry(material, weight, minAmount, maxAmount, itemStack,
                    slimefunItemId, procedurallyGeneratedEnchantments, treasureConfigFields));
        }
        return chestEntries;
    }

    public void rollChestContents(Container chest) {
        if (!chestRarities.isEmpty()) {
            rollCustomLoot(chest);
        }

        LootTables vanillaTreasure = treasureConfigFields.getVanillaTreasure();
        if (vanillaTreasure != null) {
            rollVanillaLoot(chest, vanillaTreasure);
        }
    }

    private void rollCustomLoot(Container chest) {
        int amount = (int) Math.max(Math.ceil(ThreadLocalRandom.current().nextGaussian(
                treasureConfigFields.getMean(), treasureConfigFields.getStandardDeviation())), 0);
        amount++;

        for (int i = 0; i < amount; i++) {
            Integer rarityIndex = WeighedProbability.pickWeightedProbability(rarityWeights);
            if (rarityIndex == null) {
                if (!warnedNullRarityPick) {
                    warnedNullRarityPick = true;
                    Logger.warn("Could not pick a loot rarity for treasure file " + treasureConfigFields.getFilename()
                            + " ! Check that its rarity weights are positive numbers. This will only be warned about once.");
                }
                return;
            }
            ItemStack itemStack = chestRarities.get(rarityIndex).rollLoot();
            if (itemStack != null) {
                placeItemInChest(chest, itemStack);
            }
        }
    }

    private void rollVanillaLoot(Container chest, LootTables lootTable) {
        LootContext lootContext = new LootContext.Builder(chest.getLocation()).build();
        Collection<ItemStack> loot = lootTable.getLootTable().populateLoot(ThreadLocalRandom.current(), lootContext);
        for (ItemStack itemStack : loot) {
            if (itemStack != null && itemStack.getType() != Material.AIR) {
                placeItemInChest(chest, itemStack);
            }
        }
    }

    private void placeItemInChest(Container chest, ItemStack itemStack) {
        int counter = 0;
        while (counter < 100) {
            int randomizedIndex = ThreadLocalRandom.current().nextInt(0, chest.getSnapshotInventory().getSize());
            if (chest.getSnapshotInventory().getItem(randomizedIndex) == null) {
                chest.getSnapshotInventory().setItem(randomizedIndex, itemStack);
                break;
            }
            counter++;
        }
    }

    private class ChestRarity {
        private final double chestWeight;
        private final List<ChestEntry> chestEntries;
        private final HashMap<Integer, Double> entryWeights = new HashMap<>();

        public ChestRarity(double chestWeight, List<ChestEntry> chestEntries) {
            this.chestEntries = chestEntries;
            this.chestWeight = chestWeight;
            for (int i = chestEntries.size() - 1; i >= 0; i--)
                entryWeights.put(i, chestEntries.get(i).getWeight());
        }

        public ItemStack rollLoot() {
            if (chestEntries.isEmpty()) return null;
            Integer entryIndex = WeighedProbability.pickWeightedProbability(entryWeights);
            if (entryIndex == null) {
                if (!warnedNullEntryPick) {
                    warnedNullEntryPick = true;
                    Logger.warn("Could not pick a loot entry for treasure file " + treasureConfigFields.getFilename()
                            + " ! Check that its item weights are positive numbers. This will only be warned about once.");
                }
                return null;
            }
            return chestEntries.get(entryIndex).rollEntry();
        }
    }
}
