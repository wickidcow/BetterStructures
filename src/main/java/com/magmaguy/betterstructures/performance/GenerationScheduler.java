package com.magmaguy.betterstructures.performance;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.worldedit.Schematic;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Serializes the expensive fit/generation jobs selected by ordinary player exploration.
 * Cheap deterministic position checks can still be performed in small batches, but only one
 * qualifying fit is admitted at a time and no new fit begins while a schematic paste is active.
 */
public final class GenerationScheduler {
    private static final Deque<GenerationJob> JOBS = new ArrayDeque<>();
    private static final Map<ChunkKey, Chunk> TICKETED_CHUNKS = new HashMap<>();
    private static BukkitTask task;
    private static boolean pausedForLoad;
    private static int cooldownTicks;

    private GenerationScheduler() {
    }

    public static void start() {
        if (task != null || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) return;
        task = Bukkit.getScheduler().runTaskTimer(MetadataHandler.PLUGIN, GenerationScheduler::tick, 1L, 1L);
    }

    public static void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (Chunk chunk : TICKETED_CHUNKS.values()) {
            try {
                chunk.removePluginChunkTicket(MetadataHandler.PLUGIN);
            } catch (Throwable ignored) {
            }
        }
        TICKETED_CHUNKS.clear();
        JOBS.clear();
        pausedForLoad = false;
        cooldownTicks = 0;
    }

    public static void enqueue(Chunk chunk, List<Runnable> jobs) {
        if (jobs == null || jobs.isEmpty()) return;

        if (!DefaultConfig.isPlayerGenerationThrottling()) {
            jobs.forEach(Runnable::run);
            return;
        }

        start();
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        if (!TICKETED_CHUNKS.containsKey(key)) {
            chunk.addPluginChunkTicket(MetadataHandler.PLUGIN);
            TICKETED_CHUNKS.put(key, chunk);
        }

        int totalJobs = jobs.size();
        for (int i = 0; i < totalJobs; i++) {
            JOBS.addLast(new GenerationJob(key, jobs.get(i), i == totalJobs - 1));
        }
    }

    public static int queuedJobs() {
        return JOBS.size();
    }

    private static void tick() {
        if (JOBS.isEmpty()) return;

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // Keep the entire expensive path serialized: fit -> chunk preparation -> paste.
        if (Schematic.isBusy()) return;

        double mspt = Bukkit.getAverageTickTime();
        double[] tpsSamples = Bukkit.getTPS();
        double tps = tpsSamples.length == 0 ? 20.0 : tpsSamples[0];

        if (pausedForLoad) {
            if (mspt <= DefaultConfig.getPlayerGenerationResumeMSPT()
                    && tps >= DefaultConfig.getPlayerGenerationResumeTPS()) {
                pausedForLoad = false;
                Bukkit.getLogger().info("[BetterStructures] Player-generation queue resumed at "
                        + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps) + ".");
            } else {
                return;
            }
        }

        if (mspt >= DefaultConfig.getPlayerGenerationPauseMSPT()
                || tps <= DefaultConfig.getPlayerGenerationPauseTPS()) {
            pausedForLoad = true;
            Bukkit.getLogger().warning("[BetterStructures] Player-generation queue paused to protect TPS at "
                    + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps)
                    + ". Queued jobs: " + JOBS.size());
            return;
        }

        GenerationJob job = JOBS.pollFirst();
        if (job == null) return;

        try {
            job.work().run();
        } catch (Throwable throwable) {
            Bukkit.getLogger().severe("[BetterStructures] A queued structure-generation job failed in chunk "
                    + job.key().x() + "," + job.key().z() + ".");
            throwable.printStackTrace();
        } finally {
            if (job.releaseTicketAfter()) releaseTicket(job.key());
        }

        cooldownTicks = Math.max(0, DefaultConfig.getPlayerGenerationTicksBetweenJobs());
    }

    private static void releaseTicket(ChunkKey key) {
        Chunk chunk = TICKETED_CHUNKS.remove(key);
        if (chunk == null) return;
        try {
            chunk.removePluginChunkTicket(MetadataHandler.PLUGIN);
        } catch (Throwable ignored) {
        }
    }

    private record GenerationJob(ChunkKey key, Runnable work, boolean releaseTicketAfter) {
        private GenerationJob {
            Objects.requireNonNull(key);
            Objects.requireNonNull(work);
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
