package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.LegacySchematicSanitizer;
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
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

public class Schematic {
    private static final Queue<PasteOperation> pasteQueue = new ConcurrentLinkedQueue<>();
    private static boolean erroredOnce = false;
    private static boolean isDistributedPasting = false;
    private static boolean pastePausedForLoad = false;
    private static BukkitTask activePasteTask = null;
    private static PasteOperation activePasteOperation = null;

    private static final EnumSet<Material> NBT_PASTED_MATERIALS = EnumSet.noneOf(Material.class);

    static {
        for (Material material : Material.values()) {
            String materialString = material.toString().toUpperCase(Locale.ROOT);
            if (materialString.endsWith("SIGN") ||
                    materialString.endsWith("STAIRS") ||
                    materialString.endsWith("BOX") ||
                    materialString.endsWith("CHEST_BOAT") ||
                    materialString.equals("BEACON") ||
                    materialString.endsWith("FURNACE") ||
                    materialString.equals("CALIBRATED_SCULK_SENSOR") ||
                    materialString.equals("CAMPFIRE") ||
                    materialString.equals("CARTOGRAPHY_TABLE") ||
                    materialString.equals("CAULDRON") ||
                    materialString.contains("COMMAND_BLOCK") ||
                    materialString.endsWith("ANVIL") ||
                    materialString.equals("CRAFTER") ||
                    materialString.equals("ITEM_FRAME") ||
                    materialString.equals("DISPENSER") ||
                    materialString.equals("DROPPER") ||
                    materialString.equals("ENCHANTING_TABLE") ||
                    materialString.equals("BARREL") ||
                    materialString.equals("CHEST") ||
                    materialString.equals("ENDER_CHEST") ||
                    materialString.equals("TRAPPED_CHEST") ||
                    materialString.equals("FLETCHING_TABLE") ||
                    materialString.equals("FURNACE_MINECART") ||
                    materialString.equals("GRINDSTONE") ||
                    materialString.equals("HOPPER") ||
                    materialString.equals("HOPPER_MINECART") ||
                    materialString.equals("JUKEBOX") ||
                    materialString.equals("LEVER") ||
                    materialString.equals("LOOM") ||
                    materialString.equals("LODESTONE") ||
                    materialString.startsWith("POTTED") ||
                    materialString.startsWith("SCULK") ||
                    materialString.equals("POWERED_RAIL") ||
                    materialString.equals("SMOKER") ||
                    materialString.equals("STONECUTTER") ||
                    materialString.equals("SOUL_CAMPFIRE") ||
                    materialString.contains("SPAWNER")) {
                NBT_PASTED_MATERIALS.add(material);
            }
        }
    }

    private Schematic() {
    }

    public static Clipboard load(File schematicFile) {
        Clipboard clipboard;
        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
        if (format == null) {
            Logger.warn("Could not determine schematic format for " + schematicFile.getName());
            return null;
        }

        try (LegacySchematicSanitizer.SanitizedInput sanitizedInput = LegacySchematicSanitizer.open(schematicFile);
             ClipboardReader reader = format.getReader(sanitizedInput.inputStream())) {
            clipboard = reader.read();
            if (sanitizedInput.removedBedBlockEntities() > 0) {
                Logger.info("Removed " + sanitizedInput.removedBedBlockEntities()
                        + " obsolete minecraft:bed block-entity record(s) from "
                        + schematicFile.getName() + " for modern Minecraft compatibility.");
            }
            if (sanitizedInput.replacedBedPaletteEntries() > 0) {
                Logger.info("Replaced " + sanitizedInput.replacedBedPaletteEntries()
                        + " legacy minecraft:bed palette entr"
                        + (sanitizedInput.replacedBedPaletteEntries() == 1 ? "y" : "ies")
                        + " with minecraft:red_bed in " + schematicFile.getName() + ".");
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (NoSuchElementException e) {
            Logger.warn("Failed to get element from schematic " + schematicFile.getName());
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            Logger.warn("Failed to load schematic " + schematicFile.getName()
                    + "! This usually means the WorldEdit/FAWE build is not compatible with the server version.");
            boolean firstFailure = !erroredOnce;
            erroredOnce = true;
            if (firstFailure) e.printStackTrace();
            else Logger.warn("Hiding stacktrace for this error, as it has already been printed once");
            return null;
        }
        return clipboard;
    }

    /** Synchronous component paste for callers that require completion before returning. */
    public static void paste(Clipboard clipboard, Location location) {
        World world = BukkitAdapter.adapt(location.getWorld());
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(world)) {
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(location.getX(), location.getY(), location.getZ()))
                    .build();
            Operations.complete(operation);
        } catch (WorldEditException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isSolidBlock(Clipboard schematicClipboard, BlockVector3 clipboardPosition) {
        return WorldEditUtils.isSolid(schematicClipboard.getBlock(clipboardPosition));
    }

    private static void pasteClipboardBlock(
            Clipboard schematicClipboard,
            Location adjustedLocation,
            Function<Boolean, Material> pedestalMaterialProvider,
            PasteCoordinate coordinate) {
        int x = coordinate.x();
        int y = coordinate.y();
        int z = coordinate.z();
        BlockVector3 min = schematicClipboard.getMinimumPoint();
        BlockVector3 adjustedClipboardLocation = BlockVector3.at(
                x + min.x(),
                y + min.y(),
                z + min.z());
        BaseBlock baseBlock = schematicClipboard.getFullBlock(adjustedClipboardLocation);
        BlockState blockState = baseBlock.toImmutableState();
        Material material = WorldEditUtils.adaptMaterial(blockState);
        Block worldBlock = adjustedLocation.clone().add(new Vector(x, y, z)).getBlock();

        if (material == Material.BARRIER) return;
        if (WorldEditUtils.isAir(blockState) && worldBlock.getType().isAir()) return;

        BlockData blockData = material == null ? null : WorldEditUtils.createBlockDataOrNull(baseBlock);
        if (blockData == null) {
            if (WorldEditUtils.isAir(blockState)) {
                pasteBlock(new PasteBlock(worldBlock, Material.AIR.createBlockData(), null));
            } else {
                pasteBlock(new PasteBlock(worldBlock, null,
                        WorldEditUtils.createSingleBlockClipboard(baseBlock, blockState)));
            }
            return;
        }

        if (NBT_PASTED_MATERIALS.contains(material)) {
            pasteBlock(new PasteBlock(worldBlock, null,
                    WorldEditUtils.createSingleBlockClipboard(baseBlock, blockState)));
        } else if (material == Material.BEDROCK) {
            if (!worldBlock.getType().isSolid()) {
                boolean isGround = !isSolidBlock(schematicClipboard, BlockVector3.at(
                        adjustedClipboardLocation.x(),
                        adjustedClipboardLocation.y() + 1,
                        adjustedClipboardLocation.z()));
                Material pedestalMaterial = pedestalMaterialProvider.apply(isGround);
                if (pedestalMaterial != null) {
                    pasteBlock(new PasteBlock(worldBlock, pedestalMaterial.createBlockData(), null));
                }
            }
        } else {
            pasteBlock(new PasteBlock(worldBlock, blockData, null));
        }
    }

    public static void pasteSchematic(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {
        enqueue(new ClipboardPasteOperation(
                schematicClipboard,
                location.clone().add(schematicOffset),
                pedestalMaterialProvider,
                onComplete));
    }

    /** Allows modular generation and other producers to share the same serialized paste lane. */
    public static void enqueue(PasteOperation operation) {
        pasteQueue.add(operation);
        startQueueIfIdle();
    }

    public static boolean isBusy() {
        return isDistributedPasting || activePasteOperation != null || !pasteQueue.isEmpty();
    }

    private static void startQueueIfIdle() {
        if (!isDistributedPasting) processNextPaste();
    }

    private static void processNextPaste() {
        long maxNanosPerTick = maxNanosPerTick(DefaultConfig.getPercentageOfTickUsedForPasting());

        RuntimeException firstFailure = null;
        int abandoned = 0;
        while (true) {
            activePasteOperation = pasteQueue.poll();
            if (activePasteOperation == null) {
                isDistributedPasting = false;
                activePasteTask = null;
                break;
            }

            isDistributedPasting = true;
            try {
                activePasteTask = new BukkitRunnable() {
                    @Override
                    public void run() {
                        PasteOperation operation = activePasteOperation;
                        if (operation == null) {
                            cancel();
                            return;
                        }

                        try {
                            if (shouldPauseForServerLoad()) return;

                            long stopTime = System.nanoTime() + maxNanosPerTick;
                            boolean processedAtLeastOne = false;
                            int steps = 0;
                            while (operation.hasNext()
                                    && steps++ < 4096
                                    && (!processedAtLeastOne || System.nanoTime() < stopTime)) {
                                if (!operation.ready()) break;
                                operation.pasteNext();
                                processedAtLeastOne = true;
                            }

                            if (!operation.hasNext()) {
                                completeActivePaste(operation);
                                cancel();
                                processNextPaste();
                            }
                        } catch (Throwable throwable) {
                            Logger.warn("Failed while pasting a BetterStructures schematic: " + throwable.getMessage());
                            throwable.printStackTrace();
                            abortActivePaste();
                            cancel();
                            processNextPaste();
                        }
                    }
                }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L);
                break;
            } catch (RuntimeException exception) {
                abortActivePaste();
                if (firstFailure == null) firstFailure = exception;
                abandoned++;
            }
        }

        if (firstFailure != null) {
            Logger.warn("Abandoned " + abandoned
                    + " queued BetterStructures paste(s) because their paste task could not be scheduled.");
            throw firstFailure;
        }
    }

    /** Converts the configured tick share to a bounded per-tick work budget. */
    static long maxNanosPerTick(double percentage) {
        return PasteBudget.nanosPerTick(percentage);
    }

    private static boolean shouldPauseForServerLoad() {
        if (!DefaultConfig.isPlayerGenerationThrottling()) {
            pastePausedForLoad = false;
            return false;
        }

        double mspt = Bukkit.getAverageTickTime();
        double[] samples = Bukkit.getTPS();
        double tps = samples.length == 0 ? 20.0 : samples[0];

        if (pastePausedForLoad) {
            if (mspt <= DefaultConfig.getPlayerGenerationResumeMSPT()
                    && tps >= DefaultConfig.getPlayerGenerationResumeTPS()) {
                pastePausedForLoad = false;
                Logger.info("BetterStructures paste queue resumed at "
                        + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps) + ".");
                return false;
            }
            return true;
        }

        if (mspt >= DefaultConfig.getPlayerGenerationPauseMSPT()
                || tps <= DefaultConfig.getPlayerGenerationPauseTPS()) {
            pastePausedForLoad = true;
            Logger.warn("BetterStructures paste queue paused to protect TPS at "
                    + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps) + ".");
            return true;
        }
        return false;
    }

    private static void pasteBlock(PasteBlock pasteBlock) {
        if (pasteBlock.blockData() != null) {
            pasteBlock.block().setBlockData(pasteBlock.blockData());
        } else if (pasteBlock.clipboard() != null) {
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(
                    BukkitAdapter.adapt(pasteBlock.block().getLocation().getWorld()))) {
                Operation worldeditPaste = new ClipboardHolder(pasteBlock.clipboard())
                        .createPaste(editSession)
                        .to(BlockVector3.at(
                                pasteBlock.block().getX(),
                                pasteBlock.block().getY(),
                                pasteBlock.block().getZ()))
                        .build();
                Operations.complete(worldeditPaste);
            } catch (WorldEditException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void completeActivePaste(PasteOperation operation) {
        activePasteOperation = null;
        activePasteTask = null;
        try {
            operation.onComplete();
        } catch (Throwable throwable) {
            Logger.warn("A BetterStructures paste completion callback failed: " + throwable.getMessage());
            throwable.printStackTrace();
        } finally {
            try {
                operation.close();
            } catch (Throwable throwable) {
                Logger.warn("A BetterStructures paste cleanup callback failed: " + throwable.getMessage());
            }
        }
    }

    private static void abortActivePaste() {
        if (activePasteOperation != null) {
            try {
                activePasteOperation.close();
            } catch (Throwable throwable) {
                Logger.warn("Failed to clean up an aborted BetterStructures paste: " + throwable.getMessage());
            }
        }
        activePasteOperation = null;
        activePasteTask = null;
    }

    public static void shutdown() {
        for (PasteOperation operation : pasteQueue) {
            try {
                operation.close();
            } catch (Throwable ignored) {
            }
        }
        pasteQueue.clear();
        if (activePasteTask != null) activePasteTask.cancel();
        activePasteTask = null;
        abortActivePaste();
        isDistributedPasting = false;
        pastePausedForLoad = false;
    }

    public interface PasteOperation {
        boolean hasNext();

        default boolean ready() {
            return true;
        }

        default void close() {
        }

        void pasteNext();

        void onComplete();
    }

    private static final class ClipboardPasteOperation implements PasteOperation {
        private final Clipboard clipboard;
        private final Location adjustedLocation;
        private final Function<Boolean, Material> pedestalMaterialProvider;
        private final PasteCursor cursor;
        private final Runnable onComplete;
        private PasteChunkReadiness chunks;
        private PasteCoordinate nextCoordinate;

        private ClipboardPasteOperation(
                Clipboard clipboard,
                Location adjustedLocation,
                Function<Boolean, Material> pedestalMaterialProvider,
                Runnable onComplete) {
            this.clipboard = clipboard;
            this.adjustedLocation = adjustedLocation;
            this.pedestalMaterialProvider = pedestalMaterialProvider;
            this.cursor = new PasteCursor(
                    clipboard.getDimensions().x(),
                    clipboard.getDimensions().y(),
                    clipboard.getDimensions().z());
            this.onComplete = onComplete;
        }

        @Override
        public boolean hasNext() {
            return nextCoordinate != null || cursor.hasNext();
        }

        @Override
        public boolean ready() {
            if (nextCoordinate == null) {
                if (!cursor.hasNext()) return true;
                nextCoordinate = cursor.next();
            }
            org.bukkit.World world = adjustedLocation.getWorld();
            if (world == null) throw new IllegalStateException("Paste world is unavailable");
            if (chunks == null) chunks = new PasteChunkReadiness(world);
            Location target = adjustedLocation.clone().add(
                    nextCoordinate.x(), nextCoordinate.y(), nextCoordinate.z());
            return chunks.ready(target);
        }

        @Override
        public void pasteNext() {
            if (nextCoordinate == null) nextCoordinate = cursor.next();
            pasteClipboardBlock(clipboard, adjustedLocation, pedestalMaterialProvider, nextCoordinate);
            nextCoordinate = null;
        }

        @Override
        public void onComplete() {
            if (onComplete != null) onComplete.run();
        }

        @Override
        public void close() {
            if (chunks != null) chunks.close();
            chunks = null;
            nextCoordinate = null;
        }
    }

    private static final class PasteCursor implements Iterator<PasteCoordinate> {
        private final int width;
        private final int height;
        private final int depth;
        private int x;
        private int y;
        private int z;
        private boolean hasNext;

        PasteCursor(int width, int height, int depth) {
            if (width < 0 || height < 0 || depth < 0) {
                throw new IllegalArgumentException("Paste dimensions cannot be negative");
            }
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.hasNext = width > 0 && height > 0 && depth > 0;
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public PasteCoordinate next() {
            if (!hasNext) throw new NoSuchElementException("Paste cursor is exhausted");

            PasteCoordinate coordinate = new PasteCoordinate(x, y, z);
            z++;
            if (z == depth) {
                z = 0;
                y++;
                if (y == height) {
                    y = 0;
                    x++;
                    if (x == width) hasNext = false;
                }
            }
            return coordinate;
        }
    }

    private record PasteCoordinate(int x, int y, int z) {
    }

    public record PasteBlock(Block block, BlockData blockData, Clipboard clipboard) {
    }
}
