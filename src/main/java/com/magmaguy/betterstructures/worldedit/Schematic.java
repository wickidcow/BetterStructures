package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.WorkloadRunnable;
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
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

public class Schematic {
    private static final Queue<PastePreparationOperation> preparationQueue = new ConcurrentLinkedQueue<>();
    private static final Queue<PasteBlockOperation> pasteQueue = new ConcurrentLinkedQueue<>();
    private static boolean erroredOnce = false;
    private static boolean isPreparingPaste = false;
    private static boolean isDistributedPasting = false;

    private Schematic() {
    }

    public static void shutdown() {
        preparationQueue.clear();
        pasteQueue.clear();
        isPreparingPaste = false;
        isDistributedPasting = false;
    }

    public static Clipboard load(File schematicFile) {
        Clipboard clipboard;
        ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);

        try (ClipboardReader reader = format.getReader(new FileInputStream(schematicFile))) {
            clipboard = reader.read();
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
                Logger.warn("Hiding stacktrace for this error, as it has already been printed once");
            }
            return null;
        }
        return clipboard;
    }

    /**
     * Direct WorldEdit/FAWE paste used by explicit command paths.
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

    private static boolean isSolidBlock(Clipboard schematicClipboard, BlockVector3 clipboardPosition) {
        return WorldEditUtils.isSolid(schematicClipboard.getBlock(clipboardPosition));
    }

    /**
     * Queues schematic preparation. Upstream 2.6.3 built the complete PasteBlock list in
     * one synchronous pass before the distributed paste limiter started. Large structures
     * could therefore consume a large part of one server tick while a player generated a
     * new resource-world chunk. The Albion fork gives preparation its own tick budget.
     */
    public static void pasteSchematic(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {

        preparationQueue.add(new PastePreparationOperation(
                schematicClipboard,
                location.clone(),
                schematicOffset.clone(),
                pedestalMaterialProvider,
                onComplete));

        if (!isPreparingPaste) {
            processNextPreparation();
        }
    }

    private static void processNextPreparation() {
        PastePreparationOperation operation = preparationQueue.poll();
        if (operation == null) {
            isPreparingPaste = false;
            return;
        }

        isPreparingPaste = true;
        new PastePreparationTask(operation).runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L);
    }

    private static final class PastePreparationTask extends BukkitRunnable {
        private final PastePreparationOperation operation;
        private final ArrayList<PasteBlock> blocks = new ArrayList<>();
        private final Location adjustedLocation;
        private final org.bukkit.World bukkitWorld;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int minimumX;
        private final int minimumY;
        private final int minimumZ;
        private int x;
        private int y;
        private int z;

        private PastePreparationTask(PastePreparationOperation operation) {
            this.operation = operation;
            this.adjustedLocation = operation.location().clone().add(operation.schematicOffset());
            this.bukkitWorld = adjustedLocation.getWorld();
            this.sizeX = operation.schematicClipboard().getDimensions().x();
            this.sizeY = operation.schematicClipboard().getDimensions().y();
            this.sizeZ = operation.schematicClipboard().getDimensions().z();
            this.minimumX = operation.schematicClipboard().getMinimumPoint().x();
            this.minimumY = operation.schematicClipboard().getMinimumPoint().y();
            this.minimumZ = operation.schematicClipboard().getMinimumPoint().z();

            long estimatedVolume = (long) sizeX * sizeY * sizeZ;
            if (estimatedVolume > 0 && estimatedVolume <= Integer.MAX_VALUE) {
                blocks.ensureCapacity((int) Math.min(estimatedVolume, 500_000L));
            }
        }

        @Override
        public void run() {
            if (shouldYieldForServerLoad()) return;

            double configuredPercentage = Math.max(0.005,
                    Math.min(0.25, DefaultConfig.getPercentageOfTickUsedForPastePreparation()));
            long budgetNanos = Math.max(250_000L, (long) (50_000_000L * configuredPercentage));
            long deadline = System.nanoTime() + budgetNanos;

            do {
                prepareCurrentBlock();
                if (!advance()) {
                    cancel();
                    pasteDistributed(blocks, operation.location(), () -> {
                        try {
                            if (operation.onComplete() != null) {
                                operation.onComplete().run();
                            }
                        } finally {
                            isPreparingPaste = false;
                            processNextPreparation();
                        }
                    });
                    return;
                }
            } while (System.nanoTime() < deadline);
        }

        private boolean shouldYieldForServerLoad() {
            if (!DefaultConfig.isPlayerGenerationThrottling()) return false;
            if (Bukkit.getAverageTickTime() >= DefaultConfig.getPlayerGenerationPauseMSPT()) return true;
            double[] tps = Bukkit.getTPS();
            return tps.length > 0 && tps[0] <= DefaultConfig.getPlayerGenerationPauseTPS();
        }

        private void prepareCurrentBlock() {
            Clipboard schematicClipboard = operation.schematicClipboard();
            BlockVector3 adjustedClipboardLocation = BlockVector3.at(
                    x + minimumX,
                    y + minimumY,
                    z + minimumZ);

            BaseBlock baseBlock = schematicClipboard.getFullBlock(adjustedClipboardLocation);
            BlockState blockState = baseBlock.toImmutableState();
            Material material = WorldEditUtils.adaptMaterial(blockState);
            Block worldBlock = bukkitWorld.getBlockAt(
                    adjustedLocation.getBlockX() + x,
                    adjustedLocation.getBlockY() + y,
                    adjustedLocation.getBlockZ() + z);

            boolean isGround = y + 1 >= sizeY || !isSolidBlock(schematicClipboard, BlockVector3.at(
                    adjustedClipboardLocation.x(),
                    adjustedClipboardLocation.y() + 1,
                    adjustedClipboardLocation.z()));

            if (material == Material.BARRIER) {
                return;
            }

            BlockData blockData = material == null ? null : WorldEditUtils.createBlockDataOrNull(baseBlock);
            if (blockData == null) {
                if (WorldEditUtils.isAir(blockState)) {
                    blocks.add(new PasteBlock(worldBlock, Material.AIR.createBlockData(), null));
                } else {
                    blocks.add(new PasteBlock(worldBlock, null,
                            WorldEditUtils.createSingleBlockClipboard(adjustedLocation, baseBlock, blockState)));
                }
                return;
            }

            String materialString = material.toString().toUpperCase(Locale.ROOT);
            if (requiresWorldEditMetadata(materialString)) {
                blocks.add(new PasteBlock(worldBlock, null,
                        WorldEditUtils.createSingleBlockClipboard(adjustedLocation, baseBlock, blockState)));
            } else if (material == Material.BEDROCK) {
                if (!worldBlock.getType().isSolid()) {
                    Material pedestalMaterial = operation.pedestalMaterialProvider().apply(isGround);
                    // Do not mutate the world during preparation. Upstream called setType here,
                    // bypassing its own distributed paste budget.
                    blocks.add(new PasteBlock(worldBlock, pedestalMaterial.createBlockData(), null));
                }
            } else {
                blocks.add(new PasteBlock(worldBlock, blockData, null));
            }
        }

        private boolean advance() {
            z++;
            if (z < sizeZ) return true;
            z = 0;
            y++;
            if (y < sizeY) return true;
            y = 0;
            x++;
            return x < sizeX;
        }
    }

    private static boolean requiresWorldEditMetadata(String materialString) {
        return materialString.endsWith("SIGN")
                || materialString.endsWith("STAIRS")
                || materialString.endsWith("BOX")
                || materialString.endsWith("CHEST_BOAT")
                || materialString.equals("BEACON")
                || materialString.endsWith("FURNACE")
                || materialString.equals("CALIBRATED_SCULK_SENSOR")
                || materialString.equals("CAMPFIRE")
                || materialString.equals("CARTOGRAPHY_TABLE")
                || materialString.equals("CAULDRON")
                || materialString.contains("COMMAND_BLOCK")
                || materialString.endsWith("ANVIL")
                || materialString.equals("CRAFTER")
                || materialString.equals("ITEM_FRAME")
                || materialString.equals("DISPENSER")
                || materialString.equals("DROPPER")
                || materialString.equals("ENCHANTING_TABLE")
                || materialString.equals("BARREL")
                || materialString.equals("CHEST")
                || materialString.equals("ENDER_CHEST")
                || materialString.equals("TRAPPED_CHEST")
                || materialString.equals("FLETCHING_TABLE")
                || materialString.equals("FURNACE_MINECART")
                || materialString.equals("GRINDSTONE")
                || materialString.equals("HOPPER")
                || materialString.equals("HOPPER_MINECART")
                || materialString.equals("JUKEBOX")
                || materialString.equals("LEVER")
                || materialString.equals("LOOM")
                || materialString.equals("LODESTONE")
                || materialString.startsWith("POTTED")
                || materialString.startsWith("SCULK")
                || materialString.equals("POWERED_RAIL")
                || materialString.equals("SMOKER")
                || materialString.equals("STONECUTTER")
                || materialString.equals("SOUL_CAMPFIRE")
                || materialString.contains("SPAWNER");
    }

    public static void pasteDistributed(List<PasteBlock> pasteBlocks, Location location, Runnable onComplete) {
        pasteQueue.add(new PasteBlockOperation(pasteBlocks, location, onComplete));
        if (!isDistributedPasting) {
            processNextPaste();
        }
    }

    private static void processNextPaste() {
        if (pasteQueue.isEmpty()) {
            isDistributedPasting = false;
            return;
        }

        isDistributedPasting = true;
        PasteBlockOperation operation = pasteQueue.poll();

        WorkloadRunnable workload = new WorkloadRunnable(DefaultConfig.getPercentageOfTickUsedForPasting(), () -> {
            if (operation.onComplete() != null) {
                operation.onComplete().run();
            }
            processNextPaste();
        });

        for (PasteBlock pasteBlock : operation.blocks()) {
            workload.addWorkload(() -> {
                if (pasteBlock.blockData() != null) {
                    pasteBlock.block().setBlockData(pasteBlock.blockData(), false);
                } else if (pasteBlock.clipboard() != null) {
                    try (EditSession editSession = WorldEdit.getInstance().newEditSession(
                            BukkitAdapter.adapt(pasteBlock.block().getWorld()))) {
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
            });
        }

        workload.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L);
    }

    private record PastePreparationOperation(
            Clipboard schematicClipboard,
            Location location,
            Vector schematicOffset,
            Function<Boolean, Material> pedestalMaterialProvider,
            Runnable onComplete) {
    }

    private record PasteBlockOperation(List<PasteBlock> blocks, Location location, Runnable onComplete) {
    }

    public record PasteBlock(Block block, BlockData blockData, Clipboard clipboard) {
    }
}
