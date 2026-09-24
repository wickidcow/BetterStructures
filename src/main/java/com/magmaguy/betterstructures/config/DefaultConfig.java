package com.magmaguy.betterstructures.config;

import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.config.ConfigurationFile;
import com.magmaguy.magmacore.nightbreak.NightbreakPluginUpdater;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.List;

public class DefaultConfig extends ConfigurationFile {
    private static final int DEFAULT_DISTANCE_SURFACE = 27;
    private static final int DEFAULT_DISTANCE_SHALLOW = 22;
    private static final int DEFAULT_DISTANCE_DEEP = 22;
    private static final int DEFAULT_DISTANCE_SKY = 90;
    private static final int DEFAULT_DISTANCE_LIQUID = 60;
    private static final int DEFAULT_DISTANCE_DUNGEON = 80;
    private static final int DEFAULT_MAX_OFFSET = 5;
    private static final int DEFAULT_MAX_OFFSET_DUNGEON = 18;
    private static final int MAX_SAFE_OFFSET = (Integer.MAX_VALUE - 1) / 2;

    @Getter
    private static int lowestYNormalCustom;
    @Getter
    private static int highestYNormalCustom;
    @Getter
    private static int lowestYNether;
    @Getter
    private static int highestYNether;
    @Getter
    private static int lowestYEnd;
    @Getter
    private static int highestYEnd;
    @Getter
    private static int normalCustomAirBuildingMinAltitude;
    @Getter
    private static int normalCustomAirBuildingMaxAltitude;
    @Getter
    private static int endAirBuildMinAltitude;
    @Getter
    private static int endAirBuildMaxAltitude;
    @Getter
    private static boolean newBuildingWarn;
    @Getter
    private static String regionProtectedMessage;
    @Getter
    private static boolean protectEliteMobsRegions;
    private static DefaultConfig instance;
    @Getter
    private static boolean setupDone;
    @Getter
    private static int modularChunkPastingSpeed = 10;
    @Getter
    private static double percentageOfTickUsedForPasting = 0.08;

    // Albion resource-world load protection. The defaults intentionally leave headroom for Paper's
    // own chunk generation and the rest of the server's plugin stack while players are exploring.
    @Getter
    private static boolean playerGenerationThrottling = true;
    @Getter
    private static double playerGenerationPauseMSPT = 42.0;
    @Getter
    private static double playerGenerationResumeMSPT = 32.0;
    @Getter
    private static double playerGenerationPauseTPS = 18.5;
    @Getter
    private static double playerGenerationResumeTPS = 19.5;
    @Getter
    private static int playerGenerationResumeStableTicks = 100;
    @Getter
    private static int playerGenerationTicksBetweenJobs = 2;

    @Getter
    private static double percentageOfTickUsedForPregeneration = 0.1;
    @Getter
    private static double pregenerationTPSPauseThreshold = 18.5;
    @Getter
    private static double pregenerationTPSResumeThreshold = 19.5;
    @Getter
    private static int pregenerationTPSResumeStableChecks = 3;

    @Getter
    private static int distanceSurface;
    @Getter
    private static int distanceShallow;
    @Getter
    private static int distanceDeep;
    @Getter
    private static int distanceSky;
    @Getter
    private static int distanceLiquid;
    @Getter
    private static int distanceDungeon;

    @Getter
    private static int maxOffsetSurface;
    @Getter
    private static int maxOffsetShallow;
    @Getter
    private static int maxOffsetDeep;
    @Getter
    private static int maxOffsetSky;
    @Getter
    private static int maxOffsetLiquid;
    @Getter
    private static int maxOffsetDungeon;

    @Getter
    private static int spawnProtectionRadius;

    public DefaultConfig() {
        super("config.yml");
        instance = this;
    }

    public static void toggleSetupDone() {
        setupDone = !setupDone;
        ConfigurationEngine.writeValue(setupDone, instance.file, instance.getFileConfiguration(), "setupDone");
    }

    public static void toggleSetupDone(boolean value) {
        setupDone = value;
        ConfigurationEngine.writeValue(setupDone, instance.file, instance.getFileConfiguration(), "setupDone");
    }

    public static boolean toggleWarnings() {
        newBuildingWarn = !newBuildingWarn;
        ConfigurationEngine.writeValue(
                newBuildingWarn,
                instance.file,
                instance.fileConfiguration,
                "warnAdminsAboutNewBuildings");
        return newBuildingWarn;
    }

    @Override
    public void initializeValues() {
        lowestYNormalCustom = ConfigurationEngine.setInt(fileConfiguration, "lowestYNormalCustom", -60);
        highestYNormalCustom = ConfigurationEngine.setInt(fileConfiguration, "highestYNormalCustom", 320);
        lowestYNether = ConfigurationEngine.setInt(fileConfiguration, "lowestYNether", 4);
        highestYNether = ConfigurationEngine.setInt(fileConfiguration, "highestYNether", 120);
        lowestYEnd = ConfigurationEngine.setInt(fileConfiguration, "lowestYEnd", 0);
        highestYEnd = ConfigurationEngine.setInt(fileConfiguration, "highestYEnd", 320);
        normalCustomAirBuildingMinAltitude = ConfigurationEngine.setInt(
                fileConfiguration, "normalCustomAirBuildingMinAltitude", 80);
        normalCustomAirBuildingMaxAltitude = ConfigurationEngine.setInt(
                fileConfiguration, "normalCustomAirBuildingMaxAltitude", 120);
        endAirBuildMinAltitude = ConfigurationEngine.setInt(fileConfiguration, "endAirBuildMinAltitude", 80);
        endAirBuildMaxAltitude = ConfigurationEngine.setInt(fileConfiguration, "endAirBuildMaxAltitude", 120);
        newBuildingWarn = ConfigurationEngine.setBoolean(fileConfiguration, "warnAdminsAboutNewBuildings", true);
        regionProtectedMessage = ConfigurationEngine.setString(
                fileConfiguration,
                "regionProtectedMessage",
                "&8[BetterStructures] &cDefeat the zone's bosses to edit blocks!");
        protectEliteMobsRegions = ConfigurationEngine.setBoolean(
                fileConfiguration, "protectEliteMobsRegions", true);
        setupDone = ConfigurationEngine.setBoolean(fileConfiguration, "setupDone", false);
        modularChunkPastingSpeed = ConfigurationEngine.setInt(
                fileConfiguration, "modularChunkPastingSpeed", 10);

        percentageOfTickUsedForPasting = ConfigurationEngine.setDouble(
                List.of(
                        "Sets the maximum percentage of a 50ms tick that BetterStructures will spend in its distributed paste lane.",
                        "Ranges from 0.01 to 1. Albion's default is 0.08, or about 4ms of a healthy tick.",
                        "Existing configured values are preserved; lower this if exploration still produces visible MSPT spikes."),
                fileConfiguration,
                "percentageOfTickUsedForPasting",
                0.08);

        playerGenerationThrottling = ConfigurationEngine.setBoolean(
                fileConfiguration,
                "playerGenerationThrottling",
                true);
        playerGenerationPauseMSPT = ConfigurationEngine.setDouble(
                List.of(
                        "Pause player-driven BetterStructures fitting and pasting when average MSPT reaches this value.",
                        "Queued work is kept and resumes after the server recovers."),
                fileConfiguration,
                "playerGenerationPauseMSPT",
                42.0);
        playerGenerationResumeMSPT = ConfigurationEngine.setDouble(
                List.of(
                        "Resume paused BetterStructures player-generation work when average MSPT falls to or below this value.",
                        "Keep this lower than playerGenerationPauseMSPT to prevent rapid pause/resume oscillation."),
                fileConfiguration,
                "playerGenerationResumeMSPT",
                32.0);
        playerGenerationPauseTPS = ConfigurationEngine.setDouble(
                List.of(
                        "Pause player-driven BetterStructures work when TPS reaches the 18.5 TPS protection threshold.",
                        "Default: 18.5. TPS is a trailing average, so short dips can still occur before the pause is observed."),
                fileConfiguration,
                "playerGenerationPauseTPS",
                18.5);
        if (Math.abs(playerGenerationPauseTPS - 19.0) < 0.0001) {
            playerGenerationPauseTPS = 18.5;
            ConfigurationEngine.writeValue(
                    playerGenerationPauseTPS,
                    file,
                    fileConfiguration,
                    "playerGenerationPauseTPS");
        }
        playerGenerationResumeTPS = ConfigurationEngine.setDouble(
                List.of(
                        "TPS required before paused BetterStructures player-generation work can begin recovering.",
                        "Default: 19.5. Keep this above playerGenerationPauseTPS to provide hysteresis."),
                fileConfiguration,
                "playerGenerationResumeTPS",
                19.5);
        if (Math.abs(playerGenerationResumeTPS - 19.6) < 0.0001) {
            playerGenerationResumeTPS = 19.5;
            ConfigurationEngine.writeValue(
                    playerGenerationResumeTPS,
                    file,
                    fileConfiguration,
                    "playerGenerationResumeTPS");
        }
        playerGenerationResumeStableTicks = ConfigurationEngine.setInt(
                List.of(
                        "Number of consecutive healthy server ticks required before player-generation work resumes.",
                        "Default: 100 ticks (about 5 seconds). This prevents rapid pause/resume flapping."),
                fileConfiguration,
                "playerGenerationResumeStableTicks",
                100);
        playerGenerationTicksBetweenJobs = ConfigurationEngine.setInt(
                List.of(
                        "Minimum server ticks between expensive structure-fit jobs selected during player exploration.",
                        "The default of 2 prevents several qualifying chunks from performing terrain fits in one tick."),
                fileConfiguration,
                "playerGenerationTicksBetweenJobs",
                2);

        percentageOfTickUsedForPregeneration = ConfigurationEngine.setDouble(
                List.of(
                        "Sets the maximum percentage of a tick that BetterStructures will use for world pregeneration when using the pregenerate command.",
                        "Ranges from 0.01 to 1, where 0.01 is 1% and 1 is 100%.",
                        "Lower values generate chunks more slowly but reduce server load."),
                fileConfiguration,
                "percentageOfTickUsedForPregeneration",
                0.1);
        pregenerationTPSPauseThreshold = ConfigurationEngine.setDouble(
                List.of(
                        "The TPS threshold at which chunk pregeneration will pause to protect server performance.",
                        "Default: 18.5 so pregeneration yields before the server falls into the 18 TPS range."),
                fileConfiguration,
                "pregenerationTPSPauseThreshold",
                18.5);
        pregenerationTPSResumeThreshold = ConfigurationEngine.setDouble(
                List.of(
                        "The TPS threshold at which chunk pregeneration can begin recovering after being paused.",
                        "Default: 19.5. Keep this higher than the pause threshold to prevent rapid cycling."),
                fileConfiguration,
                "pregenerationTPSResumeThreshold",
                19.5);
        pregenerationTPSResumeStableChecks = ConfigurationEngine.setInt(
                List.of(
                        "Number of consecutive healthy TPS monitor checks required before pregeneration resumes.",
                        "The monitor checks every 2 seconds; default 3 means roughly 6 seconds of stable recovery."),
                fileConfiguration,
                "pregenerationTPSResumeStableChecks",
                3);
        NightbreakPluginUpdater.setAutoDownloadConfigDefault(fileConfiguration);

        distanceSurface = validatedDistance(
                "distanceSurface",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between structures in the surface of a world.",
                                "Shorter distances between structures will result in more structures overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_SURFACE + "."),
                        fileConfiguration,
                        "distanceSurface",
                        DEFAULT_DISTANCE_SURFACE),
                DEFAULT_DISTANCE_SURFACE);
        distanceShallow = validatedDistance(
                "distanceShallow",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between structures in shallow underground structure generation.",
                                "Shorter distances between structures will result in more structures overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_SHALLOW + "."),
                        fileConfiguration,
                        "distanceShallow",
                        DEFAULT_DISTANCE_SHALLOW),
                DEFAULT_DISTANCE_SHALLOW);
        distanceDeep = validatedDistance(
                "distanceDeep",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between structures in deep underground structure generation.",
                                "Shorter distances between structures will result in more structures overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_DEEP + "."),
                        fileConfiguration,
                        "distanceDeep",
                        DEFAULT_DISTANCE_DEEP),
                DEFAULT_DISTANCE_DEEP);
        distanceSky = validatedDistance(
                "distanceSky",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between structures placed in the air.",
                                "Shorter distances between structures will result in more structures overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_SKY + "."),
                        fileConfiguration,
                        "distanceSky",
                        DEFAULT_DISTANCE_SKY),
                DEFAULT_DISTANCE_SKY);
        distanceLiquid = validatedDistance(
                "distanceLiquid",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between structures on liquid surfaces such as oceans.",
                                "Shorter distances between structures will result in more structures overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_LIQUID + "."),
                        fileConfiguration,
                        "distanceLiquid",
                        DEFAULT_DISTANCE_LIQUID),
                DEFAULT_DISTANCE_LIQUID);
        distanceDungeon = validatedDistance(
                "distanceDungeonV2",
                ConfigurationEngine.setInt(
                        List.of(
                                "Sets the distance between dungeons.",
                                "Shorter distances between dungeons will result in more dungeons overall.",
                                "Must be at least 1. Invalid values use the default of "
                                        + DEFAULT_DISTANCE_DUNGEON + "."),
                        fileConfiguration,
                        "distanceDungeonV2",
                        DEFAULT_DISTANCE_DUNGEON),
                DEFAULT_DISTANCE_DUNGEON);

        maxOffsetSurface = validatedOffset(
                "maxOffsetSurface",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize surface structure distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                        fileConfiguration,
                        "maxOffsetSurface",
                        DEFAULT_MAX_OFFSET),
                DEFAULT_MAX_OFFSET);
        maxOffsetShallow = validatedOffset(
                "maxOffsetShallow",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize shallow underground structure distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                        fileConfiguration,
                        "maxOffsetShallow",
                        DEFAULT_MAX_OFFSET),
                DEFAULT_MAX_OFFSET);
        maxOffsetDeep = validatedOffset(
                "maxOffsetDeep",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize deep underground structure distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                        fileConfiguration,
                        "maxOffsetDeep",
                        DEFAULT_MAX_OFFSET),
                DEFAULT_MAX_OFFSET);
        maxOffsetSky = validatedOffset(
                "maxOffsetSky",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize sky structure distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                        fileConfiguration,
                        "maxOffsetSky",
                        DEFAULT_MAX_OFFSET),
                DEFAULT_MAX_OFFSET);
        maxOffsetLiquid = validatedOffset(
                "maxOffsetLiquid",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize ocean/liquid structure distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET)),
                        fileConfiguration,
                        "maxOffsetLiquid",
                        DEFAULT_MAX_OFFSET),
                DEFAULT_MAX_OFFSET);
        maxOffsetDungeon = validatedOffset(
                "maxOffsetDungeonV2",
                ConfigurationEngine.setInt(
                        List.of(
                                "Used to randomize dungeon distance.",
                                "Smaller values are more grid-like; larger values are less predictable.",
                                offsetValidationDescription(DEFAULT_MAX_OFFSET_DUNGEON)),
                        fileConfiguration,
                        "maxOffsetDungeonV2",
                        DEFAULT_MAX_OFFSET_DUNGEON),
                DEFAULT_MAX_OFFSET_DUNGEON);

        spawnProtectionRadius = ConfigurationEngine.setInt(
                List.of(
                        "Sets the minimum distance (in blocks) from world spawn (coordinates 0, 0) within which no structures will be placed.",
                        "This applies to all worlds. Set to 0 to disable spawn protection."),
                fileConfiguration,
                "spawnProtectionRadius",
                100);

        ConfigurationEngine.fileSaverOnlyDefaults(fileConfiguration, file);
    }

    private int validatedDistance(String configKey, int configuredValue, int defaultValue) {
        if (configuredValue >= 1) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default "
                + defaultValue + ". Distances must be at least 1.");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private int validatedOffset(String configKey, int configuredValue, int defaultValue) {
        if (configuredValue >= 0 && configuredValue <= MAX_SAFE_OFFSET) return configuredValue;
        Logger.warn("Invalid " + configKey + " value " + configuredValue + "; using default "
                + defaultValue + ". Offsets must be between 0 and " + MAX_SAFE_OFFSET + ".");
        fileConfiguration.set(configKey, defaultValue);
        return defaultValue;
    }

    private static String offsetValidationDescription(int defaultValue) {
        return "Must be between 0 and " + MAX_SAFE_OFFSET
                + ". Invalid values use the default of " + defaultValue + ".";
    }
}
