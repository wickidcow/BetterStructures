package com.magmaguy.betterstructures.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves Slimefun items without creating a hard compile/runtime dependency on Slimefun.
 *
 * <p>The resolver intentionally looks items up at loot-roll time rather than during
 * BetterStructures startup. This means Slimefun addons that register their items after
 * BetterStructures has loaded can still be used in treasure files.</p>
 */
public final class SlimefunItemResolver {

    private static final String SLIMEFUN_ITEM_CLASS = "io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem";
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private static volatile ClassLoader cachedClassLoader;
    private static volatile Method getByIdMethod;
    private static volatile Method getItemMethod;

    private SlimefunItemResolver() {
    }

    public static ItemStack resolve(String itemId, String sourceFilename) {
        if (itemId == null || itemId.isBlank()) {
            warnOnce("blank:" + sourceFilename,
                    "Blank Slimefun item id in BetterStructures treasure file " + sourceFilename + ". Entry skipped.");
            return null;
        }

        Plugin slimefun = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (slimefun == null || !slimefun.isEnabled()) {
            warnOnce("missing-plugin:" + sourceFilename,
                    "Treasure file " + sourceFilename + " contains Slimefun loot, but Slimefun is not enabled. " +
                            "Those entries will be skipped until Slimefun is available.");
            return null;
        }

        String normalizedId = itemId.trim();
        try {
            initializeReflection(slimefun);

            Object slimefunItem = getByIdMethod.invoke(null, normalizedId);
            if (slimefunItem == null) {
                warnOnce("missing-item:" + normalizedId + ":" + sourceFilename,
                        "Unknown Slimefun item id '" + normalizedId + "' in BetterStructures treasure file " +
                                sourceFilename + ". Entry skipped.");
                return null;
            }

            Object rawItemStack = getItemMethod.invoke(slimefunItem);
            if (!(rawItemStack instanceof ItemStack itemStack)) {
                warnOnce("invalid-stack:" + normalizedId,
                        "Slimefun item '" + normalizedId + "' did not return a Bukkit ItemStack. Entry skipped.");
                return null;
            }

            return itemStack.clone();
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            warnOnce("invoke:" + normalizedId,
                    "Failed to resolve Slimefun item '" + normalizedId + "' for BetterStructures: " +
                            cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
            return null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            warnOnce("api:" + exception.getClass().getName(),
                    "BetterStructures could not access the Slimefun item API. Slimefun loot entries will be skipped: " +
                            exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage()));
            return null;
        }
    }

    private static void initializeReflection(Plugin slimefun) throws ReflectiveOperationException {
        ClassLoader slimefunClassLoader = slimefun.getClass().getClassLoader();
        if (getByIdMethod != null && getItemMethod != null && cachedClassLoader == slimefunClassLoader) {
            return;
        }

        synchronized (SlimefunItemResolver.class) {
            if (getByIdMethod != null && getItemMethod != null && cachedClassLoader == slimefunClassLoader) {
                return;
            }

            Class<?> slimefunItemClass = Class.forName(SLIMEFUN_ITEM_CLASS, true, slimefunClassLoader);
            getByIdMethod = slimefunItemClass.getMethod("getById", String.class);
            getItemMethod = slimefunItemClass.getMethod("getItem");
            cachedClassLoader = slimefunClassLoader;
        }
    }

    private static void warnOnce(String key, String message) {
        if (WARNED_KEYS.add(key)) {
            Logger.warn(message);
        }
    }
}
