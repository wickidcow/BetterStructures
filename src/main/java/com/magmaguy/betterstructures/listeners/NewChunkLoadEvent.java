package com.magmaguy.betterstructures.listeners;

import com.magmaguy.betterstructures.BetterStructures;
import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.buildingfitter.FitAirBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitLiquidBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitSurfaceBuilding;
import com.magmaguy.betterstructures.buildingfitter.FitUndergroundShallowBuilding;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.ValidWorldsConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.betterstructures.performance.GenerationScheduler;
import com.magmaguy.betterstructures.performance.ServerLoadThrottle;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.worldedit.PasteChunkReadiness;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class NewChunkLoadEvent implements Listener {

    private static final Set<LoadingChunkKey> loadingChunks = new HashSet<>();
    private static final Set<LoadingChunkKey> deferredNewChunks = new LinkedHashSet<>();
    private static final ChunkScanReentrancyGuard chunkScanReentrancyGuard = new ChunkScanReentrancyGuard();
    // The deterministic position checks are cheap, but a large exploration burst should still be
    // spread out. Qualifying expensive fit jobs are separately serialized by GenerationScheduler.
    private static final int MAX_DEFERRED_SCANS_PER_DRAIN = 8;
    private static BukkitTask deferredDrainTask;

    public NewChunkLoadEvent() {
        GenerationScheduler.start();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Chunks loaded only because an already-selected structure needs them must never recursively
        // become candidates for another BetterStructures structure.
        if (PasteChunkReadiness.isInternalChunkLoad(event.getChunk())) return;

        LoadingChunkKey loadingChunkKey = LoadingChunkKey.from(event.getChunk());
        boolean alreadyPending = deferredNewChunks.contains(loadingChunkKey);

        if (!event.isNewChunk() && !alreadyPending) return;

        deferredNewChunks.add(loadingChunkKey);
        if (BetterStructures.isReloading()) return;
        scheduleDeferredDrain();
    }

    private static void scanNewChunk(Chunk chunk, LoadingChunkKey loadingChunkKey) {
        if (!ValidWorldsConfig.isValidWorld(chunk.getWorld())) return;
        if (loadingChunks.contains(loadingChunkKey)) return;
        loadingChunks.add(loadingChunkKey);
        new BukkitRunnable() {
            @Override
            public void run() {
                loadingChunks.remove(loadingChunkKey);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 20L);

        List<Runnable> jobs = new ArrayList<>(6);
        surfaceScanner(chunk, jobs);
        shallowUndergroundScanner(chunk, jobs);
        deepUndergroundScanner(chunk, jobs);
        skyScanner(chunk, jobs);
        liquidSurfaceScanner(chunk, jobs);
        dungeonScanner(chunk, jobs);
        GenerationScheduler.enqueue(chunk, jobs);
    }

    public static void prepareForContentReload() {
        cancelDeferredDrain();
        loadingChunks.clear();
        GenerationScheduler.shutdown();
    }

    public static void replayDeferredNewChunks() {
        GenerationScheduler.start();
        scheduleDeferredDrain();
    }

    private static void scheduleDeferredDrain() {
        scheduleDeferredDrain(1L);
    }

    private static void scheduleDeferredDrain(long delayTicks) {
        if (BetterStructures.isReloading() || deferredNewChunks.isEmpty()
                || deferredDrainTask != null || MetadataHandler.PLUGIN == null
                || !MetadataHandler.PLUGIN.isEnabled()) return;

        deferredDrainTask = Bukkit.getScheduler().runTaskLater(
                MetadataHandler.PLUGIN,
                () -> {
                    deferredDrainTask = null;
                    drainDeferredNewChunks();
                },
                Math.max(1L, delayTicks));
    }

    private static void drainDeferredNewChunks() {
        if (BetterStructures.isReloading() || deferredNewChunks.isEmpty()
                || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) return;

        ServerLoadThrottle.LoadSnapshot load = ServerLoadThrottle.snapshot();
        int scanLimit = Math.min(
                MAX_DEFERRED_SCANS_PER_DRAIN,
                ServerLoadThrottle.deferredChunkScanLimit(load.band()));
        long nextDrainDelay = ServerLoadThrottle.deferredChunkDrainDelayTicks(load.band());
        if (scanLimit <= 0) {
            scheduleDeferredDrain(nextDrainDelay);
            return;
        }

        Set<LoadingChunkKey> attempted = new HashSet<>();
        int attempts = 0;
        for (LoadingChunkKey loadingChunkKey : new ArrayList<>(deferredNewChunks)) {
            if (attempts >= scanLimit) break;
            World world = Bukkit.getWorld(loadingChunkKey.worldId());
            if (world == null || !world.isChunkLoaded(loadingChunkKey.x(), loadingChunkKey.z())) continue;

            attempted.add(loadingChunkKey);
            attempts++;
            Chunk chunk = world.getChunkAt(loadingChunkKey.x(), loadingChunkKey.z());
            try {
                boolean scanned = chunkScanReentrancyGuard.runIfIdle(
                        () -> scanNewChunk(chunk, loadingChunkKey));
                if (scanned) {
                    deferredNewChunks.remove(loadingChunkKey);
                } else {
                    scheduleDeferredDrain(nextDrainDelay);
                    return;
                }
            } catch (Throwable throwable) {
                MetadataHandler.PLUGIN.getLogger().warning(
                        "Failed to replay deferred new-chunk scan for "
                                + world.getName() + " " + loadingChunkKey.x()
                                + "," + loadingChunkKey.z() + ": "
                                + throwable.getMessage());
                throwable.printStackTrace();
            }
        }

        for (LoadingChunkKey loadingChunkKey : deferredNewChunks) {
            if (attempted.contains(loadingChunkKey)) continue;
            World world = Bukkit.getWorld(loadingChunkKey.worldId());
            if (world != null && world.isChunkLoaded(loadingChunkKey.x(), loadingChunkKey.z())) {
                scheduleDeferredDrain(nextDrainDelay);
                return;
            }
        }
    }

    private static void cancelDeferredDrain() {
        if (deferredDrainTask == null) return;
        deferredDrainTask.cancel();
        deferredDrainTask = null;
    }

    public static void discardDeferredNewChunks() {
        cancelDeferredDrain();
        deferredNewChunks.clear();
    }

    public static void shutdown() {
        cancelDeferredDrain();
        loadingChunks.clear();
        deferredNewChunks.clear();
        GenerationScheduler.shutdown();
    }

    private record LoadingChunkKey(UUID worldId, int x, int z) {
        private static LoadingChunkKey from(Chunk chunk) {
            return new LoadingChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private static boolean isValidStructurePosition(
            Chunk chunk,
            GeneratorConfigFields.StructureType structureType,
            int gridDistance,
            int maxOffset) {
        int x = chunk.getX();
        int z = chunk.getZ();

        int spawnProtectionRadius = DefaultConfig.getSpawnProtectionRadius();
        if (spawnProtectionRadius > 0) {
            int blockX = x * 16 + 8;
            int blockZ = z * 16 + 8;
            if ((long) blockX * blockX + (long) blockZ * blockZ
                    < (long) spawnProtectionRadius * spawnProtectionRadius) {
                return false;
            }
        }

        long worldSeed = chunk.getWorld().getSeed();
        long typeSeed = worldSeed + structureType.name().hashCode() * 7919L;

        long minimumGridX = ((long) x - maxOffset) / gridDistance - 1;
        long maximumGridX = ((long) x + maxOffset) / gridDistance + 1;
        long minimumGridZ = ((long) z - maxOffset) / gridDistance - 1;
        long maximumGridZ = ((long) z + maxOffset) / gridDistance + 1;
        int offsetBound = (int) (2L * maxOffset + 1L);
        for (long gridX = minimumGridX; gridX <= maximumGridX; gridX++) {
            for (long gridZ = minimumGridZ; gridZ <= maximumGridZ; gridZ++) {
                long baseX = gridX * gridDistance;
                long baseZ = gridZ * gridDistance;

                if (gridZ % 2L != 0) baseX += gridDistance / 2;

                Random cellRandom = new Random(
                        typeSeed ^ ((baseX << 32) | (baseZ & 0xFFFFFFFFL)));
                int offsetX = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;
                int offsetZ = maxOffset > 0 ? cellRandom.nextInt(offsetBound) - maxOffset : 0;

                if (x == baseX + offsetX && z == baseZ + offsetZ) return true;
            }
        }

        return false;
    }

    private static void surfaceScanner(Chunk chunk, List<Runnable> jobs) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SURFACE).isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.SURFACE,
                DefaultConfig.getDistanceSurface(),
                DefaultConfig.getMaxOffsetSurface())) return;
        jobs.add(() -> new FitSurfaceBuilding(chunk));
    }

    private static void shallowUndergroundScanner(Chunk chunk, List<Runnable> jobs) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW).isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW,
                DefaultConfig.getDistanceShallow(),
                DefaultConfig.getMaxOffsetShallow())) return;
        jobs.add(() -> FitUndergroundShallowBuilding.fit(chunk));
    }

    private static void deepUndergroundScanner(Chunk chunk, List<Runnable> jobs) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_DEEP).isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.UNDERGROUND_DEEP,
                DefaultConfig.getDistanceDeep(),
                DefaultConfig.getMaxOffsetDeep())) return;
        jobs.add(() -> FitUndergroundDeepBuilding.fit(chunk));
    }

    private static void skyScanner(Chunk chunk, List<Runnable> jobs) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SKY).isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.SKY,
                DefaultConfig.getDistanceSky(),
                DefaultConfig.getMaxOffsetSky())) return;
        jobs.add(() -> new FitAirBuilding(chunk));
    }

    private static void liquidSurfaceScanner(Chunk chunk, List<Runnable> jobs) {
        if (SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.LIQUID_SURFACE).isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.LIQUID_SURFACE,
                DefaultConfig.getDistanceLiquid(),
                DefaultConfig.getMaxOffsetLiquid())) return;
        jobs.add(() -> new FitLiquidBuilding(chunk));
    }

    private static void dungeonScanner(Chunk chunk, List<Runnable> jobs) {
        if (ModuleGeneratorsConfig.getModuleGenerators().isEmpty()) return;
        if (!isValidStructurePosition(
                chunk,
                GeneratorConfigFields.StructureType.DUNGEON,
                DefaultConfig.getDistanceDungeon(),
                DefaultConfig.getMaxOffsetDungeon())) return;

        List<ModuleGeneratorsConfigFields> validatedGenerators = new ArrayList<>();
        for (ModuleGeneratorsConfigFields fields : ModuleGeneratorsConfig.getModuleGenerators().values()) {
            if (fields.getValidWorlds() != null && !fields.getValidWorlds().isEmpty()
                    && !fields.getValidWorlds().contains(chunk.getWorld().getName())) continue;
            if (fields.getValidWorldEnvironments() != null && !fields.getValidWorldEnvironments().isEmpty()
                    && !fields.getValidWorldEnvironments().contains(chunk.getWorld().getEnvironment())) continue;
            validatedGenerators.add(fields);
        }
        if (validatedGenerators.isEmpty()) return;

        ModuleGeneratorsConfigFields fields = validatedGenerators.get(
                ThreadLocalRandom.current().nextInt(validatedGenerators.size()));
        jobs.add(() -> WFCGenerator.generateNaturally(
                fields,
                chunk.getBlock(8, fields.getCenterModuleAltitude(), 8).getLocation(),
                chunk.getX(),
                chunk.getZ()));
    }
}
