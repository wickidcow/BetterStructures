package com.magmaguy.betterstructures.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selects one random enabled core Slimefun item without creating a hard
 * compile/runtime dependency on Slimefun.
 *
 * <p>Only items registered by the Slimefun plugin itself are eligible. Addon
 * items, disabled items, per-world disabled items, hidden/internal items and
 * VanillaItem compatibility wrappers are excluded.</p>
 */
public final class RandomSlimefunItemResolver {

    private static final String SLIMEFUN_CLASS = "io.github.thebusybiscuit.slimefun4.implementation.Slimefun";
    private static final String SLIMEFUN_ITEM_CLASS = "io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem";
    private static final String SLIMEFUN_REGISTRY_CLASS = "io.github.thebusybiscuit.slimefun4.core.SlimefunRegistry";

    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private static volatile ClassLoader cachedClassLoader;
    private static volatile Method getRegistryMethod;
    private static volatile Method getEnabledItemsMethod;
    private static volatile Method getByIdMethod;
    private static volatile Method getIdMethod;
    private static volatile Method getItemMethod;
    private static volatile Method getAddonMethod;
    private static volatile Method isHiddenMethod;
    private static volatile Method isDisabledInMethod;
    private static volatile List<String> cachedCoreEnabledItemIds = List.of();

    private RandomSlimefunItemResolver() {
    }

    /**
     * Returns one random, amount-one, enabled core Slimefun item for the supplied world.
     * If Slimefun is absent or disabled this quietly returns {@code null}.
     */
    public static ItemStack resolve(World world, String sourceFilename) {
        Plugin slimefun = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (slimefun == null || !slimefun.isEnabled()) {
            return null;
        }

        try {
            initializeReflection(slimefun);
            List<String> itemIds = getCoreEnabledItemIds(slimefun);
            if (itemIds.isEmpty()) {
                warnOnce("empty-pool",
                        "BetterStructures could not find any enabled core Slimefun items for random chest loot.");
                return null;
            }

            int startIndex = ThreadLocalRandom.current().nextInt(itemIds.size());
            for (int offset = 0; offset < itemIds.size(); offset++) {
                String itemId = itemIds.get((startIndex + offset) % itemIds.size());
                Object slimefunItem = getByIdMethod.invoke(null, itemId);
                if (slimefunItem == null) {
                    continue;
                }

                if (world != null && Boolean.TRUE.equals(isDisabledInMethod.invoke(slimefunItem, world))) {
                    continue;
                }

                Object rawItemStack = getItemMethod.invoke(slimefunItem);
                if (!(rawItemStack instanceof ItemStack itemStack) || itemStack.getType().isAir()) {
                    continue;
                }

                ItemStack rolledItem = itemStack.clone();
                rolledItem.setAmount(1);
                return rolledItem;
            }

            warnOnce("no-world-items:" + (world == null ? "unknown" : world.getName()),
                    "BetterStructures found no enabled core Slimefun items usable in world "
                            + (world == null ? "unknown" : world.getName()) + ". Random Slimefun chest loot was skipped.");
            return null;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            warnOnce("invoke:" + cause.getClass().getName(),
                    "BetterStructures failed to select random Slimefun chest loot for " + sourceFilename + ": "
                            + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
            return null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            warnOnce("api:" + exception.getClass().getName(),
                    "BetterStructures could not access the Slimefun registry for random chest loot: "
                            + exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage()));
            return null;
        }
    }

    private static List<String> getCoreEnabledItemIds(Plugin slimefun)
            throws ReflectiveOperationException {
        List<String> cached = cachedCoreEnabledItemIds;
        if (!cached.isEmpty()) {
            return cached;
        }

        synchronized (RandomSlimefunItemResolver.class) {
            cached = cachedCoreEnabledItemIds;
            if (!cached.isEmpty()) {
                return cached;
            }

            Object registry = getRegistryMethod.invoke(null);
            Object rawEnabledItems = getEnabledItemsMethod.invoke(registry);
            if (!(rawEnabledItems instanceof Collection<?> enabledItems)) {
                throw new IllegalStateException("Slimefun registry did not return an item collection");
            }

            List<String> ids = new ArrayList<>();
            for (Object slimefunItem : enabledItems) {
                if (slimefunItem == null) {
                    continue;
                }

                // Keep the pool to Slimefun Legacy core, not installed addons.
                Object addon = getAddonMethod.invoke(slimefunItem);
                if (addon != slimefun) {
                    continue;
                }

                if (Boolean.TRUE.equals(isHiddenMethod.invoke(slimefunItem))) {
                    continue;
                }

                // Slimefun registers a few vanilla compatibility wrappers; those are
                // not actual custom Slimefun loot and should not occupy a random roll.
                if ("VanillaItem".equals(slimefunItem.getClass().getSimpleName())) {
                    continue;
                }

                Object rawId = getIdMethod.invoke(slimefunItem);
                if (rawId instanceof String id && !id.isBlank()) {
                    ids.add(id);
                }
            }

            if (!ids.isEmpty()) {
                cachedCoreEnabledItemIds = Collections.unmodifiableList(ids);
                Logger.info("BetterStructures random Slimefun chest loot pool loaded " + ids.size()
                        + " enabled core Slimefun Legacy items.");
            }

            return ids;
        }
    }

    private static void initializeReflection(Plugin slimefun) throws ReflectiveOperationException {
        ClassLoader slimefunClassLoader = slimefun.getClass().getClassLoader();
        if (cachedClassLoader == slimefunClassLoader
                && getRegistryMethod != null
                && getEnabledItemsMethod != null
                && getByIdMethod != null
                && getIdMethod != null
                && getItemMethod != null
                && getAddonMethod != null
                && isHiddenMethod != null
                && isDisabledInMethod != null) {
            return;
        }

        synchronized (RandomSlimefunItemResolver.class) {
            if (cachedClassLoader == slimefunClassLoader
                    && getRegistryMethod != null
                    && getEnabledItemsMethod != null
                    && getByIdMethod != null
                    && getIdMethod != null
                    && getItemMethod != null
                    && getAddonMethod != null
                    && isHiddenMethod != null
                    && isDisabledInMethod != null) {
                return;
            }

            Class<?> slimefunClass = Class.forName(SLIMEFUN_CLASS, true, slimefunClassLoader);
            Class<?> slimefunItemClass = Class.forName(SLIMEFUN_ITEM_CLASS, true, slimefunClassLoader);
            Class<?> slimefunRegistryClass = Class.forName(SLIMEFUN_REGISTRY_CLASS, true, slimefunClassLoader);

            getRegistryMethod = slimefunClass.getMethod("getRegistry");
            getEnabledItemsMethod = slimefunRegistryClass.getMethod("getEnabledSlimefunItems");
            getByIdMethod = slimefunItemClass.getMethod("getById", String.class);
            getIdMethod = slimefunItemClass.getMethod("getId");
            getItemMethod = slimefunItemClass.getMethod("getItem");
            getAddonMethod = slimefunItemClass.getMethod("getAddon");
            isHiddenMethod = slimefunItemClass.getMethod("isHidden");
            isDisabledInMethod = slimefunItemClass.getMethod("isDisabledIn", World.class);

            cachedCoreEnabledItemIds = List.of();
            cachedClassLoader = slimefunClassLoader;
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED_KEYS.add(key)) {
            Logger.warn(message);
        }
    }
}
