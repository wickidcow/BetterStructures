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
import com.sk89q.worldedit.util.SideEffectSet;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
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

    @FunctionalInterface
    public interface FawePostProcessor {
        void run(EditSession editSession, Location adjustedLocation) throws Exception;
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
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());
            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
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
        pasteSchematic(
                schematicClipboard,
                location,
                schematicOffset,
                null,
                pedestalMaterialProvider,
                null,
                onComplete);
    }

    /**
     * Natural-structure paste with Albion's chunk-preparation and FAWE post-processing hooks.
     * Every horizontal chunk in the structure footprint is asynchronously prepared and ticketed
     * before terrain sampling begins, so the pre-paste callback cannot synchronously generate a
     * neighboring chunk while inspecting pedestal material.
     */
    public static void pasteSchematic(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Runnable prePasteCallback,
            Function<Boolean, Material> pedestalMaterialProvider,
            FawePostProcessor fawePostProcessor,
            Runnable onComplete) {
        enqueue(new ClipboardPasteOperation(
                schematicClipboard,
                location.clone().add(schematicOffset),
                prePasteCallback,
                pedestalMaterialProvider,
                fawePostProcessor,
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

    static long maxNanosPerTick(double percentage) {
        return PasteBudget.nanosPerTick(percentage);
    }

    private static boolean shouldPauseForServerLoad() {
        if (!DefaultConfig.isPlayerGenerationThrottling()) {
            pastePausedForLoad = false;
            pasteHealthyRecoveryTicks = 0;
            return false;
        }

        double mspt = Bukkit.getAverageTickTime();
        double[] samples = Bukkit.getTPS();
        double tps = samples.length == 0 ? 20.0 : samples[0];

        if (pastePausedForLoad) {
            if (mspt <= DefaultConfig.getPlayerGenerationResumeMSPT()
                    && tps >= DefaultConfig.getPlayerGenerationResumeTPS()) {
                pasteHealthyRecoveryTicks++;
                int requiredTicks = Math.max(1, DefaultConfig.getPlayerGenerationResumeStableTicks());
                if (pasteHealthyRecoveryTicks >= requiredTicks) {
                    pastePausedForLoad = false;
                    pasteHealthyRecoveryTicks = 0;
                    Logger.info("BetterStructures paste queue resumed after " + requiredTicks
                            + " healthy ticks at "
                            + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps) + ".");
                    return false;
                }
            } else {
                pasteHealthyRecoveryTicks = 0;
            }
            return true;
        }

        if (mspt >= DefaultConfig.getPlayerGenerationPauseMSPT()
                || tps <= DefaultConfig.getPlayerGenerationPauseTPS()) {
            pastePausedForLoad = true;
            pasteHealthyRecoveryTicks = 0;
            Logger.warn("BetterStructures paste queue paused to protect TPS at "
                    + String.format(Locale.ROOT, "%.1f MSPT / %.2f TPS", mspt, tps) + ".");
            return true;
        }
        return false;
    }

    private static void pasteBlock(PasteBlock pasteBlock) {
        if (pasteBlock.blockData() != null) {
            // Natural structure placement should not trigger thousands of individual physics
            // updates while a schematic is being assembled.
            pasteBlock.block().setBlockData(pasteBlock.blockData(), false);
        } else if (pasteBlock.clipboard() != null) {
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(
                    BukkitAdapter.adapt(pasteBlock.block().getLocation().getWorld()))) {
                editSession.setTrackingHistory(false);
                editSession.setSideEffectApplier(SideEffectSet.none());
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
        private static final int PHASE_PREPARE_CHUNKS = 0;
        private static final int PHASE_PRE_PASTE = 1;
        private static final int PHASE_BLOCKS = 2;
        private static final int PHASE_FAWE_POST = 3;
        private static final int PHASE_DONE = 4;

        private final Clipboard clipboard;
        private final Location adjustedLocation;
        private final Runnable prePasteCallback;
        private final Function<Boolean, Material> pedestalMaterialProvider;
        private final FawePostProcessor fawePostProcessor;
        private final PasteCursor cursor;
        private final Runnable onComplete;
        private final PasteChunkReadiness chunks;
        private final List<Location> chunkAnchors;

        private int phase = PHASE_PREPARE_CHUNKS;
        private int chunkIndex;
        private PasteCoordinate nextCoordinate;
        private volatile boolean postProcessingDone;
        private volatile Throwable postProcessingFailure;
        private boolean postProcessingStarted;
        private BukkitTask postProcessingTask;
        private boolean closed;

        private ClipboardPasteOperation(
                Clipboard clipboard,
                Location adjustedLocation,
                Runnable prePasteCallback,
                Function<Boolean, Material> pedestalMaterialProvider,
                FawePostProcessor fawePostProcessor,
                Runnable onComplete) {
            this.clipboard = clipboard;
            this.adjustedLocation = adjustedLocation;
            this.prePasteCallback = prePasteCallback;
            this.pedestalMaterialProvider = pedestalMaterialProvider;
            this.fawePostProcessor = fawePostProcessor;
            this.cursor = new PasteCursor(
                    clipboard.getDimensions().x(),
                    clipboard.getDimensions().y(),
                    clipboard.getDimensions().z());
            this.onComplete = onComplete;
            org.bukkit.World world = adjustedLocation.getWorld();
            if (world == null) throw new IllegalStateException("Paste world is unavailable");
            this.chunks = new PasteChunkReadiness(world);
            this.chunkAnchors = buildChunkAnchors(adjustedLocation, clipboard);
        }

        @Override
        public boolean hasNext() {
            return !closed && phase < PHASE_DONE;
        }

        @Override
        public boolean ready() {
            return switch (phase) {
                case PHASE_PREPARE_CHUNKS ->
                        chunkIndex >= chunkAnchors.size() || chunks.ready(chunkAnchors.get(chunkIndex));
                case PHASE_PRE_PASTE -> true;
                case PHASE_BLOCKS -> blockReady();
                case PHASE_FAWE_POST -> !postProcessingStarted || postProcessingDone;
                default -> false;
            };
        }

        private boolean blockReady() {
            if (nextCoordinate == null) {
                if (!cursor.hasNext()) return true;
                nextCoordinate = cursor.next();
            }
            Location target = adjustedLocation.clone().add(
                    nextCoordinate.x(), nextCoordinate.y(), nextCoordinate.z());
            return chunks.ready(target);
        }

        @Override
        public void pasteNext() {
            switch (phase) {
                case PHASE_PREPARE_CHUNKS -> {
                    if (chunkIndex < chunkAnchors.size()) {
                        chunkIndex++;
                    } else {
                        phase = PHASE_PRE_PASTE;
                    }
                }
                case PHASE_PRE_PASTE -> {
                    if (prePasteCallback != null) prePasteCallback.run();
                    phase = PHASE_BLOCKS;
                }
                case PHASE_BLOCKS -> {
                    if (nextCoordinate == null) {
                        if (!cursor.hasNext()) {
                            phase = PHASE_FAWE_POST;
                            return;
                        }
                        nextCoordinate = cursor.next();
                    }
                    pasteClipboardBlock(clipboard, adjustedLocation, pedestalMaterialProvider, nextCoordinate);
                    nextCoordinate = null;
                }
                case PHASE_FAWE_POST -> advanceFawePostProcessing();
                default -> throw new IllegalStateException("Invalid natural paste phase " + phase);
            }
        }

        private void advanceFawePostProcessing() {
            if (fawePostProcessor == null) {
                phase = PHASE_DONE;
                return;
            }
            if (!postProcessingStarted) {
                postProcessingStarted = true;
                try {
                    postProcessingTask = Bukkit.getScheduler().runTaskAsynchronously(MetadataHandler.PLUGIN, () -> {
                        try (EditSession editSession = WorldEdit.getInstance().newEditSession(
                                BukkitAdapter.adapt(adjustedLocation.getWorld()))) {
                            editSession.setTrackingHistory(false);
                            editSession.setSideEffectApplier(SideEffectSet.none());
                            if (!closed) fawePostProcessor.run(editSession, adjustedLocation);
                        } catch (Throwable failure) {
                            postProcessingFailure = failure;
                        } finally {
                            postProcessingDone = true;
                        }
                    });
                } catch (Throwable failure) {
                    postProcessingFailure = failure;
                    postProcessingDone = true;
                }
                return;
            }
            if (!postProcessingDone) return;
            if (postProcessingFailure != null) {
                throw new IllegalStateException("FAWE natural-structure post-processing failed", postProcessingFailure);
            }
            phase = PHASE_DONE;
        }

        @Override
        public void onComplete() {
            if (onComplete != null) onComplete.run();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (postProcessingTask != null && !postProcessingDone) postProcessingTask.cancel();
            chunks.close();
            nextCoordinate = null;
        }

        private static List<Location> buildChunkAnchors(Location adjustedLocation, Clipboard clipboard) {
            List<Location> anchors = new ArrayList<>();
            int minX = adjustedLocation.getBlockX();
            int minZ = adjustedLocation.getBlockZ();
            int maxX = minX + Math.max(0, clipboard.getDimensions().x() - 1);
            int maxZ = minZ + Math.max(0, clipboard.getDimensions().z() - 1);
            for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
                for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                    anchors.add(new Location(
                            adjustedLocation.getWorld(),
                            chunkX * 16 + 8,
                            adjustedLocation.getY(),
                            chunkZ * 16 + 8));
                }
            }
            return anchors;
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
