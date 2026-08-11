package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.magmacore.util.Logger;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * BetterStructures schematic I/O and the Albion FAWE paste pipeline.
 *
 * <p>Natural structure placement is serialized, required chunks are prepared through
 * Paper's async chunk API in small batches, and the actual block loop runs through one
 * FAWE EditSession off the server thread. This preserves BetterStructures' barrier and
 * bedrock/pedestal semantics without the upstream per-block Bukkit paste workload.</p>
 */
public final class Schematic {

    private static final int CHUNK_LOAD_BATCH_SIZE = 2;
    private static final long CHUNK_LOAD_BATCH_DELAY_TICKS = 1L;
    private static final long PRESSURE_RETRY_TICKS = 10L;

    private static final Queue<PasteRequest> PASTE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<ChunkKey> INTERNAL_CHUNK_LOADS = ConcurrentHashMap.newKeySet();

    private static boolean erroredOnce = false;
    private static boolean pasteInProgress = false;

    private Schematic() {
    }

    public static void shutdown() {
        PASTE_QUEUE.clear();
        INTERNAL_CHUNK_LOADS.clear();
        pasteInProgress = false;
    }

    public static boolean isBusy() {
        return pasteInProgress || !PASTE_QUEUE.isEmpty();
    }

    /**
     * Lets the new-chunk listener distinguish a player-generated chunk from a chunk
     * BetterStructures itself had to load to fit an already-selected structure.
     */
    public static boolean isInternalChunkLoad(Chunk chunk) {
        return INTERNAL_CHUNK_LOADS.contains(new ChunkKey(
                chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    public static Clipboard load(File schematicFile) {
        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            Logger.warn("Could not determine schematic format for " + schematicFile.getName());
            return null;
        }

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematicFile))) {
            return reader.read();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (NoSuchElementException e) {
            Logger.warn("Failed to get element from schematic " + schematicFile.getName());
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            Logger.warn("Failed to load schematic " + schematicFile.getName()
                    + ". This Albion fork requires a compatible FastAsyncWorldEdit build for your server version.");
            if (!erroredOnce) {
                erroredOnce = true;
                e.printStackTrace();
            } else {
                Logger.warn("Hiding stacktrace for this error because one has already been printed.");
            }
            return null;
        }
    }

    /**
     * Direct FAWE/WorldEdit paste retained for explicit command and modular-world paths.
     * Natural structure generation uses {@link #pasteSchematic} instead.
     */
    public static void paste(Clipboard clipboard, Location location) {
        World world = BukkitAdapter.adapt(location.getWorld());
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                    .build();
            Operations.complete(operation);
        } catch (WorldEditException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Queue one natural structure paste. Only one BetterStructures FAWE structure edit
     * runs at a time, which avoids several resource-world discoveries competing for
     * chunk generation and FAWE queues simultaneously.
     */
    public static void pasteSchematic(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Runnable prePasteCallback,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {

        PASTE_QUEUE.add(new PasteRequest(
                schematicClipboard,
                location.clone(),
                schematicOffset.clone(),
                prePasteCallback,
                pedestalMaterialProvider,
                onComplete));

        if (Bukkit.isPrimaryThread()) {
            processNextPaste();
        } else {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, Schematic::processNextPaste);
        }
    }

    /**
     * Compatibility overload for call sites that do not need a pre-paste callback.
     */
    public static void pasteSchematic(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {
        pasteSchematic(schematicClipboard, location, schematicOffset, null,
                pedestalMaterialProvider, onComplete);
    }

    private static void processNextPaste() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, Schematic::processNextPaste);
            return;
        }
        if (pasteInProgress) return;

        PasteRequest request = PASTE_QUEUE.poll();
        if (request == null) return;

        pasteInProgress = true;
        attemptStartPaste(request);
    }

    private static void attemptStartPaste(PasteRequest request) {
        if (serverUnderPressure()) {
            Bukkit.getScheduler().runTaskLater(
                    MetadataHandler.PLUGIN,
                    () -> attemptStartPaste(request),
                    PRESSURE_RETRY_TICKS);
            return;
        }

        org.bukkit.World world = request.location().getWorld();
        if (world == null) {
            failRequest(request, Set.of(), "world is unavailable");
            return;
        }

        List<Long> requiredChunks = new ArrayList<>(calculateRequiredChunks(
                request.schematicClipboard(), request.location(), request.schematicOffset()));
        loadChunkBatch(request, world, requiredChunks, 0, new LinkedHashSet<>());
    }

    private static boolean serverUnderPressure() {
        if (!DefaultConfig.isPlayerGenerationThrottling()) return false;
        if (Bukkit.getAverageTickTime() >= DefaultConfig.getPlayerGenerationPauseMSPT()) return true;
        double[] tps = Bukkit.getTPS();
        return tps.length > 0 && tps[0] <= DefaultConfig.getPlayerGenerationPauseTPS();
    }

    private static Set<Long> calculateRequiredChunks(
            Clipboard clipboard,
            Location location,
            Vector schematicOffset) {

        LinkedHashSet<Long> chunks = new LinkedHashSet<>();
        Location adjusted = location.clone().add(schematicOffset);

        int minX = adjusted.getBlockX();
        int minZ = adjusted.getBlockZ();
        int maxX = minX + Math.max(0, clipboard.getDimensions().x() - 1);
        int maxZ = minZ + Math.max(0, clipboard.getDimensions().z() - 1);

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                chunks.add(chunkKey(chunkX, chunkZ));
            }
        }
        return chunks;
    }

    /**
     * Loads/generates only a couple of structure chunks at a time. This is deliberately
     * more conservative than issuing every getChunkAtAsync request at once because the
     * Albion resource world already has significant generator/I/O load.
     */
    private static void loadChunkBatch(
            PasteRequest request,
            org.bukkit.World world,
            List<Long> requiredChunks,
            int startIndex,
            Set<Long> ticketedChunks) {

        if (startIndex >= requiredChunks.size()) {
            startFawePaste(request, world, ticketedChunks);
            return;
        }

        int endIndex = Math.min(requiredChunks.size(), startIndex + CHUNK_LOAD_BATCH_SIZE);
        List<Long> batch = requiredChunks.subList(startIndex, endIndex);
        List<CompletableFuture<Chunk>> futures = new ArrayList<>(batch.size());

        for (long key : batch) {
            int chunkX = chunkX(key);
            int chunkZ = chunkZ(key);
            ChunkKey chunkKey = new ChunkKey(world.getUID(), chunkX, chunkZ);
            INTERNAL_CHUNK_LOADS.add(chunkKey);
            futures.add(world.getChunkAtAsync(chunkX, chunkZ, true));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(
                        MetadataHandler.PLUGIN,
                        () -> {
                            for (long key : batch) {
                                INTERNAL_CHUNK_LOADS.remove(new ChunkKey(
                                        world.getUID(), chunkX(key), chunkZ(key)));
                            }

                            if (throwable != null) {
                                failRequest(request, ticketedChunks,
                                        "async chunk preparation failed: " + throwable.getClass().getSimpleName());
                                return;
                            }

                            for (long key : batch) {
                                int chunkX = chunkX(key);
                                int chunkZ = chunkZ(key);
                                world.addPluginChunkTicket(chunkX, chunkZ, MetadataHandler.PLUGIN);
                                ticketedChunks.add(key);
                            }

                            int nextIndex = endIndex;
                            Bukkit.getScheduler().runTaskLater(
                                    MetadataHandler.PLUGIN,
                                    () -> loadChunkBatch(request, world, requiredChunks,
                                            nextIndex, ticketedChunks),
                                    CHUNK_LOAD_BATCH_DELAY_TICKS);
                        }));
    }

    private static void startFawePaste(
            PasteRequest request,
            org.bukkit.World world,
            Set<Long> ticketedChunks) {

        try {
            if (request.prePasteCallback() != null) {
                request.prePasteCallback().run();
            }
        } catch (Throwable throwable) {
            Logger.warn("BetterStructures pre-paste preparation failed: " + throwable.getMessage());
            throwable.printStackTrace();
            failRequest(request, ticketedChunks, "pre-paste callback failed");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(MetadataHandler.PLUGIN, () -> {
            Throwable failure = null;
            try {
                executeFawePaste(request, world);
            } catch (Throwable throwable) {
                failure = throwable;
            }

            Throwable finalFailure = failure;
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                try {
                    if (finalFailure == null) {
                        if (request.onComplete() != null) {
                            request.onComplete().run();
                        }
                    } else {
                        Logger.warn("FAWE structure paste failed at "
                                + request.location().getBlockX() + ","
                                + request.location().getBlockY() + ","
                                + request.location().getBlockZ() + ": "
                                + finalFailure.getMessage());
                        finalFailure.printStackTrace();
                    }
                } finally {
                    releaseTickets(world, ticketedChunks);
                    pasteInProgress = false;
                    processNextPaste();
                }
            });
        });
    }

    /**
     * Executes the full block loop on an async FAWE EditSession. BaseBlock is used for
     * normal and NBT-rich blocks so chests, spawners, signs, etc. keep their schematic
     * data. Barrier markers remain no-op, while bedrock retains BetterStructures'
     * pedestal/filler semantics.
     */
    private static void executeFawePaste(PasteRequest request, org.bukkit.World bukkitWorld)
            throws WorldEditException {

        Clipboard clipboard = request.schematicClipboard();
        Location adjustedLocation = request.location().clone().add(request.schematicOffset());
        World world = BukkitAdapter.adapt(bukkitWorld);

        int sizeX = clipboard.getDimensions().x();
        int sizeY = clipboard.getDimensions().y();
        int sizeZ = clipboard.getDimensions().z();
        int minimumX = clipboard.getMinimumPoint().x();
        int minimumY = clipboard.getMinimumPoint().y();
        int minimumZ = clipboard.getMinimumPoint().z();

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());

            for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    for (int z = 0; z < sizeZ; z++) {
                        BlockVector3 clipboardPosition = BlockVector3.at(
                                x + minimumX,
                                y + minimumY,
                                z + minimumZ);

                        BaseBlock baseBlock = clipboard.getFullBlock(clipboardPosition);
                        Material material = WorldEditUtils.adaptMaterial(baseBlock);
                        if (material == Material.BARRIER) continue;

                        BlockVector3 worldPosition = BlockVector3.at(
                                adjustedLocation.getBlockX() + x,
                                adjustedLocation.getBlockY() + y,
                                adjustedLocation.getBlockZ() + z);

                        if (material == Material.BEDROCK) {
                            if (editSession.getBlock(worldPosition)
                                    .getBlockType().getMaterial().isSolid()) {
                                continue;
                            }

                            boolean isGround = y + 1 >= sizeY || !WorldEditUtils.isSolid(
                                    clipboard.getBlock(BlockVector3.at(
                                            clipboardPosition.x(),
                                            clipboardPosition.y() + 1,
                                            clipboardPosition.z())));

                            Material pedestalMaterial = request.pedestalMaterialProvider().apply(isGround);
                            if (pedestalMaterial != null) {
                                editSession.setBlock(worldPosition,
                                        BukkitAdapter.adapt(pedestalMaterial.createBlockData()));
                            }
                        } else {
                            editSession.setBlock(worldPosition, baseBlock);
                        }
                    }
                }
            }
        }
    }

    private static void failRequest(PasteRequest request, Set<Long> ticketedChunks, String reason) {
        org.bukkit.World world = request.location().getWorld();
        if (world != null) {
            releaseTickets(world, ticketedChunks);
        }
        Logger.warn("Skipping BetterStructures paste: " + reason);
        pasteInProgress = false;
        processNextPaste();
    }

    private static void releaseTickets(org.bukkit.World world, Set<Long> ticketedChunks) {
        for (long key : ticketedChunks) {
            world.removePluginChunkTicket(chunkX(key), chunkZ(key), MetadataHandler.PLUGIN);
        }
        ticketedChunks.clear();
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private record PasteRequest(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Runnable prePasteCallback,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }
}
