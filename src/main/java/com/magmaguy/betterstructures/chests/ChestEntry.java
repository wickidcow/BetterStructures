package com.magmaguy.betterstructures.chests;

import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.betterstructures.thirdparty.SlimefunItemResolver;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class ChestEntry {
    private static final Set<String> WARNED_INVALID_MATERIALS = ConcurrentHashMap.newKeySet();

    private final Material material;
    @Getter
    private final double weight;
    private final int minAmount;
    private final int maxAmount;
    private final ItemStack itemStack;
    private final String slimefunItemId;
    private final boolean procedurallyGeneratedEnchantments;
    private final TreasureConfigFields treasureConfigFields;

    public ChestEntry(Material material, double chance, int minAmount, int maxAmount, ItemStack itemStack,
                      boolean procedurallyGeneratedEnchantments, TreasureConfigFields treasureConfigFields) {
        this(material, chance, minAmount, maxAmount, itemStack, null,
                procedurallyGeneratedEnchantments, treasureConfigFields);
    }

    public ChestEntry(Material material, double chance, int minAmount, int maxAmount, ItemStack itemStack,
                      String slimefunItemId, boolean procedurallyGeneratedEnchantments,
                      TreasureConfigFields treasureConfigFields) {
        this.material = material;
        this.weight = chance;
        if (minAmount < 1 || maxAmount < minAmount) {
            Logger.warn("Missing or invalid amount for loot entry"
                    + (material != null ? " " + material : "")
                    + " in treasure file " + (treasureConfigFields != null ? treasureConfigFields.getFilename() : "unknown")
                    + " ! Defaulting the amount to 1.");
            minAmount = Math.max(minAmount, 1);
            maxAmount = Math.max(maxAmount, minAmount);
        }
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.itemStack = itemStack;
        this.slimefunItemId = slimefunItemId;
        this.procedurallyGeneratedEnchantments = procedurallyGeneratedEnchantments;
        this.treasureConfigFields = treasureConfigFields;
    }

    public ItemStack rollEntry() {
        int amount;
        if (minAmount != maxAmount) amount = ThreadLocalRandom.current().nextInt(minAmount, maxAmount + 1);
        else amount = minAmount;

        try {
            if (material != null) {
                Material rollMaterial = resolveRollMaterial(material);
                if (rollMaterial == null) return null;

                ItemStack rolledItemStack = new ItemStack(rollMaterial, amount);
                if (!procedurallyGeneratedEnchantments)
                    return rolledItemStack;

                List<TreasureConfigFields.ConfigurationEnchantment> configurationEnchantmentList =
                        treasureConfigFields.getEnchantmentSettings().get(rollMaterial);
                if (configurationEnchantmentList == null || configurationEnchantmentList.isEmpty()) return rolledItemStack;

                ItemMeta itemMeta = rolledItemStack.getItemMeta();
                for (TreasureConfigFields.ConfigurationEnchantment configurationEnchantment : configurationEnchantmentList) {
                    configurationEnchantment.rollEnchantment(itemMeta);
                }
                rolledItemStack.setItemMeta(itemMeta);
                return rolledItemStack;
            }

            ItemStack resolvedItemStack;
            if (slimefunItemId != null) {
                resolvedItemStack = SlimefunItemResolver.resolve(slimefunItemId, getSourceFilename());
                if (resolvedItemStack == null) return null;
            } else if (itemStack != null) {
                resolvedItemStack = itemStack.clone();
            } else {
                Logger.warn("BetterStructures encountered an empty loot entry in " + getSourceFilename() + ". Entry skipped.");
                return null;
            }

            resolvedItemStack.setAmount(amount);
            return resolvedItemStack;
        } catch (Exception exception) {
            Logger.warn("Failed to roll BetterStructures loot entry in " + getSourceFilename() + ": "
                    + exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage()));
            return null;
        }
    }

    private Material resolveRollMaterial(Material configuredMaterial) {
        // Bukkit exposes BAMBOO_SAPLING as a block-only material on the current API.
        // Existing BetterStructures configs used it as loot, so preserve those configs by
        // converting the legacy entry to the actual inventory item instead of throwing.
        if (configuredMaterial == Material.BAMBOO_SAPLING) {
            return Material.BAMBOO;
        }

        if (!configuredMaterial.isItem()) {
            String warningKey = configuredMaterial.name() + ':' + getSourceFilename();
            if (WARNED_INVALID_MATERIALS.add(warningKey)) {
                Logger.warn("Material '" + configuredMaterial.name() + "' in BetterStructures treasure file "
                        + getSourceFilename() + " is not an inventory item. Entry skipped. This warning is shown once.");
            }
            return null;
        }

        return configuredMaterial;
    }

    private String getSourceFilename() {
        return treasureConfigFields == null ? "unknown treasure configuration" : treasureConfigFields.getFilename();
    }
}
