package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prepares chunks for a budgeted paste without synchronously generating them from the paste loop.
 *
 * <p>The Albion fork keeps every touched chunk ticketed until the paste closes. This is slightly
 * more conservative than upstream's single-ticket implementation, but it prevents FAWE from
 * finishing queued work in a chunk that BetterStructures has already allowed to unload.</p>
 */
public final class PasteChunkReadiness implements AutoCloseable {
    private static final Set<ChunkKey> INTERNAL_CHUNK_LOADS = ConcurrentHashMap.newKeySet();

    private final World world;
    private final Method asyncLoad;
    private final Map<Long, Chunk> heldChunks = new HashMap<>();
    private CompletableFuture<Chunk> pending;
    private ChunkKey pendingKey;
    private boolean closed;

    public PasteChunkReadiness(World world) {
        this.world = world;
        Method method;
        try {
            method = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            method = null;
        }
        asyncLoad = method;
    }

    /** True while this helper is responsible for loading the supplied chunk. */
    public static boolean isInternalChunkLoad(Chunk chunk) {
        return INTERNAL_CHUNK_LOADS.contains(new ChunkKey(
                chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    public boolean ready(Location location) {
        if (closed) return false;
        if (Bukkit.getWorld(world.getUID()) != world) {
            throw new IllegalStateException("Paste world was unloaded");
        }

        int x = location.getBlockX() >> 4;
        int z = location.getBlockZ() >> 4;
        long key = chunkKey(x, z);
        if (heldChunks.containsKey(key)) return true;

        if (pending != null) {
            if (!pending.isDone()) return false;
            Chunk loaded = pending.join(); // isDone() above means this never blocks the server thread.
            pending = null;
            pendingKey = null;
            if (closed || Bukkit.getWorld(world.getUID()) != world) {
                throw new IllegalStateException("Paste world was unloaded while loading a chunk");
            }
            if (loaded == null || !loaded.isLoaded()) return false;
            hold(loaded);
            return loaded.getX() == x && loaded.getZ() == z;
        }

        if (world.isChunkLoaded(x, z)) {
            hold(world.getChunkAt(x, z));
            return true;
        }

        requestChunk(x, z);
        return false;
    }

    private void requestChunk(int x, int z) {
        ChunkKey requestKey = new ChunkKey(world.getUID(), x, z);
        INTERNAL_CHUNK_LOADS.add(requestKey);
        pendingKey = requestKey;

        if (asyncLoad != null) {
            try {
                @SuppressWarnings("unchecked")
                CompletableFuture<Chunk> request =
                        (CompletableFuture<Chunk>) asyncLoad.invoke(world, x, z, true);
                pending = request;
                request.whenComplete((chunk, failure) -> INTERNAL_CHUNK_LOADS.remove(requestKey));
                return;
            } catch (ReflectiveOperationException failure) {
                INTERNAL_CHUNK_LOADS.remove(requestKey);
                pendingKey = null;
                throw new IllegalStateException("Could not request a Paper chunk for pasting", failure);
            }
        }

        // Compatibility fallback. Paper normally takes the async branch above. A Spigot-like
        // provider only exposes synchronous loading, so isolate exactly one load on a later tick.
        CompletableFuture<Chunk> request = new CompletableFuture<>();
        pending = request;
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            try {
                if (closed || Bukkit.getWorld(world.getUID()) != world) {
                    request.cancel(false);
                    return;
                }
                request.complete(world.getChunkAt(x, z));
            } catch (Throwable failure) {
                request.completeExceptionally(failure);
            } finally {
                INTERNAL_CHUNK_LOADS.remove(requestKey);
            }
        });
    }

    private void hold(Chunk chunk) {
        long key = chunkKey(chunk.getX(), chunk.getZ());
        if (heldChunks.containsKey(key)) return;
        chunk.addPluginChunkTicket(MetadataHandler.PLUGIN);
        heldChunks.put(key, chunk);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (pendingKey != null) INTERNAL_CHUNK_LOADS.remove(pendingKey);
        pendingKey = null;
        pending = null;
        for (Chunk chunk : heldChunks.values()) {
            try {
                chunk.removePluginChunkTicket(MetadataHandler.PLUGIN);
            } catch (Throwable ignored) {
                // The world may already be shutting down. Tickets are best-effort during teardown.
            }
        }
        heldChunks.clear();
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
