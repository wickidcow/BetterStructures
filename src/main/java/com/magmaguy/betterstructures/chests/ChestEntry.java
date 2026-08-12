package com.magmaguy.betterstructures.chests;

import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.betterstructures.thirdparty.SlimefunItemResolver;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ChestEntry {
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
                ItemStack rolledItemStack = new ItemStack(material, amount);
                if (!procedurallyGeneratedEnchantments)
                    return rolledItemStack;

                List<TreasureConfigFields.ConfigurationEnchantment> configurationEnchantmentList =
                        treasureConfigFields.getEnchantmentSettings().get(material);
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
            Logger.warn("Failed to roll BetterStructures loot entry in " + getSourceFilename() + ": " +
                    exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage()));
            return null;
        }
    }

    private String getSourceFilename() {
        return treasureConfigFields == null ? "unknown treasure configuration" : treasureConfigFields.getFilename();
    }
}
