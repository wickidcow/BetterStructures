package com.magmaguy.betterstructures.performance;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.worldedit.Schematic;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serializes expensive structure-fitting work created by normal player exploration.
 *
 * <p>ChunkLoadEvent still performs the cheap deterministic position check immediately,
 * but qualifying structures are admitted through this queue. This prevents several
 * structure fits from landing in the same server tick when a player moves quickly
 * through a resource world.</p>
 */
public final class GenerationScheduler {

    private static final Deque<GenerationJob> JOBS = new ArrayDeque<>();
    private static final Set<Chunk> TICKETED_CHUNKS = new HashSet<>();
    private static boolean started = false;
    private static boolean pausedForLoad = false;
    private static int cooldownTicks = 0;

    private GenerationScheduler() {
    }

    public static void start() {
        if (started) return;
        started = true;

        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 1L, 1L);
    }

    public static void shutdown() {
        for (Chunk chunk : TICKETED_CHUNKS) {
            chunk.removePluginChunkTicket(MetadataHandler.PLUGIN);
        }
        TICKETED_CHUNKS.clear();
        JOBS.clear();
        pausedForLoad = false;
        cooldownTicks = 0;
        started = false;
    }

    public static void enqueue(Chunk chunk, List<Runnable> jobs) {
        if (jobs == null || jobs.isEmpty()) return;

        if (!DefaultConfig.isPlayerGenerationThrottling()) {
            jobs.forEach(Runnable::run);
            return;
        }

        // A qualifying structure chunk is rare. Holding its center chunk while queued
        // is much cheaper than forcing a synchronous reload later when the player has
        // already flown away from it.
        chunk.addPluginChunkTicket(MetadataHandler.PLUGIN);
        TICKETED_CHUNKS.add(chunk);
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());

        int totalJobs = jobs.size();
        for (int i = 0; i < totalJobs; i++) {
            JOBS.addLast(new GenerationJob(key, chunk, jobs.get(i), i == totalJobs - 1));
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

        // Do not start another terrain fit while a structure is loading chunks, being
        // pasted by FAWE, or waiting in the paste queue. This makes the full expensive
        // path serialized, not just the final block placement.
        if (Schematic.isBusy()) return;

        double mspt = Bukkit.getAverageTickTime();
        double[] tpsSamples = Bukkit.getTPS();
        double tps = tpsSamples.length == 0 ? 20.0 : tpsSamples[0];

        if (pausedForLoad) {
            if (mspt <= DefaultConfig.getPlayerGenerationResumeMSPT()
                    && tps >= DefaultConfig.getPlayerGenerationResumeTPS()) {
                pausedForLoad = false;
                Bukkit.getLogger().info("[BetterStructures] Player-generation queue resumed at "
                        + String.format("%.1f", mspt) + " MSPT / " + String.format("%.2f", tps) + " TPS.");
            } else {
                return;
            }
        }

        if (mspt >= DefaultConfig.getPlayerGenerationPauseMSPT()
                || tps <= DefaultConfig.getPlayerGenerationPauseTPS()) {
            pausedForLoad = true;
            Bukkit.getLogger().warning("[BetterStructures] Player-generation queue paused to protect TPS at "
                    + String.format("%.1f", mspt) + " MSPT / " + String.format("%.2f", tps)
                    + " TPS. Queued jobs: " + JOBS.size());
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
            if (job.releaseTicketAfter()) {
                // Keep the center chunk around briefly while schematic chunk preparation
                // and the FAWE paste get underway. Schematic itself tickets every chunk
                // touched by the structure for the duration of the actual edit.
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        job.chunk().removePluginChunkTicket(MetadataHandler.PLUGIN);
                        TICKETED_CHUNKS.remove(job.chunk());
                    }
                }.runTaskLater(MetadataHandler.PLUGIN, 200L);
            }
        }

        cooldownTicks = Math.max(0, DefaultConfig.getPlayerGenerationTicksBetweenJobs());
    }

    private record GenerationJob(ChunkKey key, Chunk chunk, Runnable work, boolean releaseTicketAfter) {
        private GenerationJob {
            Objects.requireNonNull(key);
            Objects.requireNonNull(chunk);
            Objects.requireNonNull(work);
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
