package com.magmaguy.betterstructures.config;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.util.WorldFolderResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class ValidWorldsConfig extends ConfigurationFile {
    private static final String VALID_WORLDS_KEY = "Valid worlds";
    private static final String ELITEMOBS_EXCLUSION_MIGRATION_KEY = "EliteMobs worlds excluded by default";
    private static final long UNLOAD_PRUNE_DELAY_TICKS = 20L * 10L;
    private static final HashMap<String, Boolean> validWorlds = new HashMap<>();
    private static boolean whitelistNewWorlds;
    private static ValidWorldsConfig instance;

    public ValidWorldsConfig() {
        super("ValidWorlds.yml");
        instance = this;
    }

    public static void registerNewWorld(World world) {
        if (world == null || instance == null) return;
        registerWorldName(world.getName(), defaultValidityForNewWorld(world.getName()), true);
    }

    private static void registerWorldName(String worldName, boolean defaultValue, boolean save) {
        ConfigurationSection validWorldsSection = getOrCreateValidWorldsSection();
        if (!validWorldsSection.contains(worldName)) {
            instance.fileConfiguration.set(validWorldsPath(worldName), defaultValue);
            if (save)
                ConfigurationEngine.fileSaverCustomValues(instance.fileConfiguration, instance.file);
        }

        // Never overwrite an existing choice here. In particular, an EliteMobs
        // world deliberately re-enabled after the one-time migration must stay
        // enabled across reloads and restarts.
        validWorlds.put(worldName, instance.fileConfiguration.getBoolean(validWorldsPath(worldName)));
    }

    private static ConfigurationSection getOrCreateValidWorldsSection() {
        ConfigurationSection validWorldsSection = instance.fileConfiguration.getConfigurationSection(VALID_WORLDS_KEY);
        if (validWorldsSection != null) return validWorldsSection;
        return instance.fileConfiguration.createSection(VALID_WORLDS_KEY);
    }

    private static String validWorldsPath(String worldName) {
        return VALID_WORLDS_KEY + "." + worldName;
    }

    public static void setWorldValidity(World world, boolean valid) {
        if (world == null) return;
        setWorldValidity(world.getName(), valid);
    }

    public static void setWorldValidity(String worldName, boolean valid) {
        if (instance == null || worldName == null || worldName.isBlank()) return;
        getOrCreateValidWorldsSection();
        instance.fileConfiguration.set(validWorldsPath(worldName), valid);
        validWorlds.put(worldName, valid);
        ConfigurationEngine.fileSaverCustomValues(instance.fileConfiguration, instance.file);
    }

    public static void unregisterWorld(World world) {
        if (world == null) return;
        validWorlds.remove(world.getName());
    }

    private static void pruneMissingWorldEntry(String worldName) {
        if (instance == null || worldName == null) return;
        if (Bukkit.getWorld(worldName) != null || WorldFolderResolver.folderExists(worldName)) return;

        ConfigurationSection validWorldsSection = instance.fileConfiguration.getConfigurationSection(VALID_WORLDS_KEY);
        if (validWorldsSection == null || !validWorldsSection.contains(worldName)) return;

        instance.fileConfiguration.set(validWorldsPath(worldName), null);
        validWorlds.remove(worldName);
        ConfigurationEngine.fileSaverCustomValues(instance.fileConfiguration, instance.file);
    }

    private void pruneMissingWorldEntries() {
        ConfigurationSection validWorldsSection = fileConfiguration.getConfigurationSection(VALID_WORLDS_KEY);
        if (validWorldsSection == null) return;

        for (String worldName : new ArrayList<>(validWorldsSection.getKeys(false))) {
            if (Bukkit.getWorld(worldName) != null || WorldFolderResolver.folderExists(worldName)) continue;
            fileConfiguration.set(validWorldsPath(worldName), null);
            validWorlds.remove(worldName);
        }
    }

    private static boolean eliteMobsIsEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("EliteMobs");
    }

    static boolean isEliteMobsManagedWorldName(String worldName) {
        return worldName != null && worldName.toLowerCase(Locale.ROOT).startsWith("em_");
    }

    private static boolean defaultValidityForNewWorld(String worldName) {
        if (eliteMobsIsEnabled() && isEliteMobsManagedWorldName(worldName)) return false;
        return whitelistNewWorlds;
    }

    /**
     * 2.7.1 migration: older builds registered every newly observed world with
     * the global default, including EliteMobs' adventurers-guild and instanced
     * dungeon worlds. Flip legacy em_* entries once, then persist a marker so a
     * server owner can deliberately re-enable one later without us undoing it.
     */
    private void migrateEliteMobsWorldDefaultsOnce() {
        if (!eliteMobsIsEnabled()) return;
        if (fileConfiguration.getBoolean(ELITEMOBS_EXCLUSION_MIGRATION_KEY, false)) return;

        ConfigurationSection validWorldsSection = getOrCreateValidWorldsSection();
        for (String worldName : new ArrayList<>(validWorldsSection.getKeys(false))) {
            if (isEliteMobsManagedWorldName(worldName)) {
                fileConfiguration.set(validWorldsPath(worldName), false);
                validWorlds.put(worldName, false);
            }
        }
        fileConfiguration.set(ELITEMOBS_EXCLUSION_MIGRATION_KEY, true);
    }

    public static boolean isValidWorld(World world) {
        if (world == null) return false;
        if (validWorlds.get(world.getName()) != null)
            return validWorlds.get(world.getName());
        registerNewWorld(world);
        if (validWorlds.get(world.getName()) != null)
            return validWorlds.get(world.getName());
        return false;
    }

    @Override
    public void initializeValues() {
        instance = this;
        validWorlds.clear();
        whitelistNewWorlds = ConfigurationEngine.setBoolean(fileConfiguration, "New worlds spawn structures", true);
        fileConfiguration.addDefault(VALID_WORLDS_KEY, new HashMap<String, Boolean>());

        pruneMissingWorldEntries();
        migrateEliteMobsWorldDefaultsOnce();

        for (World world : Bukkit.getWorlds())
            registerWorldName(world.getName(), defaultValidityForNewWorld(world.getName()), false);

        ConfigurationSection validWorldsSection = fileConfiguration.getConfigurationSection(VALID_WORLDS_KEY);
        if (validWorldsSection == null) return;

        for (String key : validWorldsSection.getKeys(false))
            validWorlds.put(key, validWorldsSection.getBoolean(key));

        ConfigurationEngine.fileSaverCustomValues(fileConfiguration, file);
    }

    public static class ValidWorldsConfigEvents implements Listener {
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onWorldLoad(WorldLoadEvent event) {
            registerNewWorld(event.getWorld());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onWorldUnload(WorldUnloadEvent event) {
            String worldName = event.getWorld().getName();
            unregisterWorld(event.getWorld());
            if (MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled())
                return;
            new BukkitRunnable() {
                @Override
                public void run() {
                    pruneMissingWorldEntry(worldName);
                }
            }.runTaskLater(MetadataHandler.PLUGIN, UNLOAD_PRUNE_DELAY_TICKS);
        }
    }
}
