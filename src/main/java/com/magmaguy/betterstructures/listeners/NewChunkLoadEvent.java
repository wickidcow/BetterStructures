package com.magmaguy.betterstructures.listeners;

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
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.worldedit.Schematic;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class NewChunkLoadEvent implements Listener {

    private static final Set<ChunkKey> loadingChunks = new HashSet<>();

    public NewChunkLoadEvent() {
        GenerationScheduler.start();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        // BetterStructures may need to generate neighboring chunks before an already
        // selected structure can be pasted. Those internal loads must never recursively
        // qualify for more BetterStructures generation.
        if (Schematic.isInternalChunkLoad(event.getChunk())) return;
        if (!ValidWorldsConfig.isValidWorld(event.getWorld())) return;

        Chunk chunk = event.getChunk();
        ChunkKey chunkKey = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (!loadingChunks.add(chunkKey)) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                loadingChunks.remove(chunkKey);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 20L);

        // Position checks are deterministic and cheap. Only chunks that can actually
        // contain a BetterStructures build enter the expensive generation queue.
        List<Runnable> jobs = new ArrayList<>(2);

        if (!SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SURFACE).isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SURFACE,
                DefaultConfig.getDistanceSurface(), DefaultConfig.getMaxOffsetSurface())) {
            jobs.add(() -> new FitSurfaceBuilding(chunk));
        }

        if (!SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW).isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_SHALLOW,
                DefaultConfig.getDistanceShallow(), DefaultConfig.getMaxOffsetShallow())) {
            jobs.add(() -> FitUndergroundShallowBuilding.fit(chunk));
        }

        if (!SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.UNDERGROUND_DEEP).isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.UNDERGROUND_DEEP,
                DefaultConfig.getDistanceDeep(), DefaultConfig.getMaxOffsetDeep())) {
            jobs.add(() -> FitUndergroundDeepBuilding.fit(chunk));
        }

        if (!SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.SKY).isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.SKY,
                DefaultConfig.getDistanceSky(), DefaultConfig.getMaxOffsetSky())) {
            jobs.add(() -> new FitAirBuilding(chunk));
        }

        if (!SchematicContainer.getSchematics().get(GeneratorConfigFields.StructureType.LIQUID_SURFACE).isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.LIQUID_SURFACE,
                DefaultConfig.getDistanceLiquid(), DefaultConfig.getMaxOffsetLiquid())) {
            jobs.add(() -> new FitLiquidBuilding(chunk));
        }

        if (!ModuleGeneratorsConfig.getModuleGenerators().isEmpty()
                && isValidStructurePosition(chunk, GeneratorConfigFields.StructureType.DUNGEON,
                DefaultConfig.getDistanceDungeon(), DefaultConfig.getMaxOffsetDungeon())) {
            jobs.add(() -> generateDungeon(chunk));
        }

        GenerationScheduler.enqueue(chunk, jobs);
    }

    /**
     * Determines if the given chunk is a valid structure position based on
     * a diamond grid pattern with seeded random offsets.
     */
    private boolean isValidStructurePosition(Chunk chunk, GeneratorConfigFields.StructureType structureType,
                                             int gridDistance, int maxOffset) {
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

        for (int gridX = (x - maxOffset) / gridDistance - 1;
             gridX <= (x + maxOffset) / gridDistance + 1; gridX++) {
            for (int gridZ = (z - maxOffset) / gridDistance - 1;
                 gridZ <= (z + maxOffset) / gridDistance + 1; gridZ++) {
                int baseX = gridX * gridDistance;
                int baseZ = gridZ * gridDistance;

                if (gridZ % 2 != 0) {
                    baseX += gridDistance / 2;
                }

                Random cellRandom = new Random(typeSeed ^ (((long) baseX << 32) | (baseZ & 0xFFFFFFFFL)));
                int offsetX = maxOffset > 0 ? cellRandom.nextInt(maxOffset * 2 + 1) - maxOffset : 0;
                int offsetZ = maxOffset > 0 ? cellRandom.nextInt(maxOffset * 2 + 1) - maxOffset : 0;

                if (x == baseX + offsetX && z == baseZ + offsetZ) {
                    return true;
                }
            }
        }

        return false;
    }

    private void generateDungeon(Chunk chunk) {
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
        new WFCGenerator(fields, chunk.getBlock(8, fields.getCenterModuleAltitude(), 8).getLocation());
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
