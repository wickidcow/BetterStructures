package com.magmaguy.betterstructures.modules;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.config.modules.ModulesConfigFields;
import com.magmaguy.betterstructures.config.treasures.TreasureConfig;
import com.magmaguy.betterstructures.config.treasures.TreasureConfigFields;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.betterstructures.worldedit.FaweEditQueue;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FAWE-native module/dungeon placement for the Albion fork.
 *
 * <p>Upstream split module blocks between NMS palette writes, Bukkit slow-block writes,
 * and separate WorldEdit NBT repair. Albion 1.1.1 instead sends the complete block plan
 * through one serialized asynchronous FAWE edit using BaseBlock data, then returns to
 * the primary thread only for Bukkit-required loot/events/entity spawning.</p>
 */
public final class ModulePasting {
    private final List<InterpretedSign> interpretedSigns = new ArrayList<>();
    private final List<ChestPlacement> chestsToFill = new ArrayList<>();
    private final List<BarrelPlacement> barrelsToFill = new ArrayList<>();
    private final List<EntitySpawn> entitiesToSpawn = new ArrayList<>();
    private final String spawnPoolSuffix;
    private final Location startLocation;
    private final boolean createModularWorld;
    private ModularWorld modularWorld;
    private final World world;
    private final File worldFolder;
    private final ModuleGeneratorsConfigFields moduleGeneratorsConfigFields;

    public ModulePasting(World world,
                         File worldFolder,
                         Deque<WFCNode> WFCNodeDeque,
                         String spawnPoolSuffix,
                         Location startLocation,
                         ModuleGeneratorsConfigFields moduleGeneratorsConfigFields) {
        this.spawnPoolSuffix = spawnPoolSuffix;
        this.startLocation = startLocation;
        this.world = world;
        this.worldFolder = worldFolder;
        this.moduleGeneratorsConfigFields = moduleGeneratorsConfigFields;

        WFCNode firstNode = WFCNodeDeque.peek();
        this.createModularWorld = firstNode != null
                && firstNode.getWfcGenerator() != null
                && firstNode.getWfcGenerator().getModuleGeneratorsConfigFields().isWorldGeneration();

        batchPaste(WFCNodeDeque, interpretedSigns);
        createModularWorld(world, worldFolder);
        notifyPlayers();
    }

    /**
     * Small explicit module paste. It still uses the FAWE provider and never falls back
     * to Bukkit/NMS block writes.
     */
    public static void paste(Clipboard clipboard, Location location, Integer rotation) {
        if (clipboard == null || rotation == null || location.getWorld() == null) return;

        AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));
        Clipboard transformedClipboard;
        try {
            transformedClipboard = clipboard.transform(transform);
        } catch (WorldEditException e) {
            Logger.warn("Failed to transform clipboard: " + e.getMessage());
            throw new RuntimeException(e);
        }

        BlockVector3 minPoint = transformedClipboard.getMinimumPoint();
        com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(location.getWorld());

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());

            for (BlockVector3 blockPos : transformedClipboard.getRegion()) {
                BaseBlock baseBlock = transformedClipboard.getFullBlock(blockPos);
                if (baseBlock.getBlockType().getMaterial().isAir()) continue;

                BlockVector3 worldPos = BlockVector3.at(
                        location.getBlockX() + (blockPos.x() - minPoint.x()),
                        location.getBlockY() + (blockPos.y() - minPoint.y()),
                        location.getBlockZ() + (blockPos.z() - minPoint.z()));
                editSession.setBlock(worldPos, baseBlock);
            }

            pasteArmorStands(transformedClipboard, location, rotation);
        } catch (Exception e) {
            Logger.warn("Failed to paste module through FAWE: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static int normalizeRotation(int rotation) {
        return (360 - rotation) % 360;
    }

    public static void pasteArmorStands(Clipboard clipboard, Location location, Integer rotation) {
        if (rotation == null) rotation = 0;

        AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));
        try {
            Clipboard transformedClipboard = clipboard.transform(transform);
            WorldEditUtils.pasteArmorStandsOnlyFromTransformed(transformedClipboard, location);
        } catch (WorldEditException e) {
            Logger.warn("Failed to transform clipboard for entities: " + e.getMessage());
        }
    }

    private List<FawePlacement> generatePlacementPlan(Clipboard clipboard,
                                                       Location worldPasteOriginLocation,
                                                       Integer rotation,
                                                       List<InterpretedSign> interpretedSigns,
                                                       ModulesConfigFields modulesConfigFields) {
        List<FawePlacement> placements = new ArrayList<>();
        AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));

        final Clipboard transformedClipboard;
        try {
            transformedClipboard = clipboard.transform(transform);
        } catch (WorldEditException e) {
            throw new RuntimeException(e);
        }

        BlockVector3 minPoint = transformedClipboard.getMinimumPoint();
        int baseX = worldPasteOriginLocation.getBlockX();
        int baseY = worldPasteOriginLocation.getBlockY();
        int baseZ = worldPasteOriginLocation.getBlockZ();

        for (BlockVector3 blockPos : transformedClipboard.getRegion()) {
            BaseBlock baseBlock = transformedClipboard.getFullBlock(blockPos);
            BlockState blockState = baseBlock.toImmutableState();

            if (createModularWorld && WorldEditUtils.isAir(blockState)) continue;

            int worldX = baseX + (blockPos.x() - minPoint.x());
            int worldY = baseY + (blockPos.y() - minPoint.y());
            int worldZ = baseZ + (blockPos.z() - minPoint.z());
            BlockVector3 worldPosition = BlockVector3.at(worldX, worldY, worldZ);
            Location pasteLocation = new Location(world, worldX, worldY, worldZ);
            Material material = WorldEditUtils.adaptMaterial(blockState);

            if (material == Material.BARRIER) continue;

            if (isControlSign(material)) {
                List<String> lines = getLines(baseBlock);
                interpretedSigns.add(new InterpretedSign(pasteLocation, lines));

                Material replacement = Material.AIR;
                for (String line : lines) {
                    if (line.contains("[spawn]") && lines.size() > 1) {
                        try {
                            EntityType entityType = EntityType.valueOf(lines.get(1).toUpperCase());
                            entitiesToSpawn.add(new EntitySpawn(pasteLocation, entityType));
                        } catch (Exception e) {
                            Logger.warn("Invalid entity type in sign: " + lines.get(1));
                        }
                    } else if (line.contains("[chest]")) {
                        replacement = Material.CHEST;
                        chestsToFill.add(new ChestPlacement(pasteLocation, Material.CHEST));
                    } else if (line.contains("[trapped_chest]")) {
                        replacement = Material.TRAPPED_CHEST;
                        chestsToFill.add(new ChestPlacement(pasteLocation, Material.TRAPPED_CHEST));
                    }
                }

                placements.add(FawePlacement.replacement(worldPosition, replacement));
                continue;
            }

            if (material == Material.BEDROCK) {
                placements.add(FawePlacement.bedrockFiller(worldPosition));
                continue;
            }

            if (material == Material.BARREL) {
                barrelsToFill.add(new BarrelPlacement(pasteLocation, modulesConfigFields));
            }

            // BaseBlock keeps all block state and NBT. No separate Bukkit slow path or
            // NBT repair pass is necessary with FAWE.
            placements.add(FawePlacement.baseBlock(worldPosition, baseBlock));
        }

        return placements;
    }

    private static boolean isControlSign(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") || name.endsWith("_HANGING_SIGN");
    }

    private List<String> getLines(BaseBlock baseBlock) {
        List<String> strings = new ArrayList<>();
        for (String line : WorldEditUtils.getLines(baseBlock)) {
            if (line != null && !line.isBlank() && line.contains("[pool:"))
                strings.add(line.replace("]", spawnPoolSuffix + "]"));
            else strings.add(line);
        }
        return strings;
    }

    public List<InterpretedSign> batchPaste(Deque<WFCNode> WFCNodeDeque, List<InterpretedSign> interpretedSigns) {
        List<FawePlacement> placements = new ArrayList<>();
        List<EntityPasteInfo> entityPasteInfos = new ArrayList<>();

        while (!WFCNodeDeque.isEmpty()) {
            WFCNode node = WFCNodeDeque.poll();
            if (node == null || node.getModulesContainer() == null) continue;

            Clipboard clipboard = node.getModulesContainer().getClipboard();
            if (clipboard == null) continue;

            ModulesConfigFields modulesConfigField = node.getModulesContainer().getModulesConfigField();
            Integer rotation = node.getModulesContainer().getRotation();
            Location realLocation = node.getRealLocation(startLocation);

            placements.addAll(generatePlacementPlan(
                    clipboard, realLocation, rotation, interpretedSigns, modulesConfigField));

            AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));
            try {
                Clipboard transformedClipboard = clipboard.transform(transform);
                entityPasteInfos.add(new EntityPasteInfo(transformedClipboard, realLocation));
            } catch (WorldEditException e) {
                Logger.warn("Failed to transform module clipboard for entities: " + e.getMessage());
            }
        }

        FaweEditQueue.submit(
                "module batch at " + startLocation.getBlockX() + ","
                        + startLocation.getBlockY() + "," + startLocation.getBlockZ(),
                () -> executeFaweBatch(placements, entityPasteInfos),
                failure -> {
                    if (failure != null) {
                        Logger.warn("Module FAWE batch failed: " + failure.getMessage());
                        failure.printStackTrace();
                        return;
                    }
                    postPasteProcessing();
                });

        return interpretedSigns;
    }

    private void executeFaweBatch(List<FawePlacement> placements,
                                  List<EntityPasteInfo> entityPasteInfos) throws Exception {
        com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(world);

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());

            for (FawePlacement placement : placements) {
                if (placement.bedrockFiller()) {
                    if (editSession.getBlock(placement.position())
                            .getBlockType().getMaterial().isSolid()) {
                        continue;
                    }
                    editSession.setBlock(placement.position(),
                            BukkitAdapter.adapt(Material.STONE.createBlockData()));
                } else if (placement.replacementMaterial() != null) {
                    editSession.setBlock(placement.position(),
                            BukkitAdapter.adapt(placement.replacementMaterial().createBlockData()));
                } else if (placement.baseBlock() != null) {
                    editSession.setBlock(placement.position(), placement.baseBlock());
                }
            }
        }

        // Entity clipboard operations remain in the same serialized asynchronous FAWE
        // lane instead of running during the Bukkit completion phase.
        for (EntityPasteInfo info : entityPasteInfos) {
            WorldEditUtils.pasteArmorStandsOnlyFromTransformed(info.clipboard(), info.location());
        }
    }

    /**
     * Bukkit-only completion phase: inventory APIs, plugin events, and entity spawns.
     * There are intentionally no block mutation calls here.
     */
    private void postPasteProcessing() {
        if (createModularWorld) {
            createModularWorld(world, worldFolder);
            modularWorld.spawnOtherEntities();
        }

        for (ChestPlacement chestPlacement : chestsToFill) {
            Block block = chestPlacement.location().getBlock();
            if (block.getType() != chestPlacement.material()) continue;
            if (!(block.getState() instanceof Container container)) continue;

            String treasureFilename = moduleGeneratorsConfigFields.getTreasureFile();
            TreasureConfigFields treasureConfigFields = TreasureConfig.getConfigFields(treasureFilename);
            if (treasureConfigFields == null) continue;

            ChestContents chestContents = new ChestContents(treasureConfigFields);
            chestContents.rollChestContents(container);
            ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
            Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
            if (!chestFillEvent.isCancelled()) container.update(true);
        }

        if (moduleGeneratorsConfigFields.isGenerateLootInBarrels() && !barrelsToFill.isEmpty()) {
            Map<String, ChestContents> contentsByTreasure = new HashMap<>();
            Set<String> warnedMissingTreasures = new HashSet<>();

            for (BarrelPlacement bp : barrelsToFill) {
                ModulesConfigFields modConfig = bp.modulesConfigFields();
                if (modConfig != null && !modConfig.isGenerateLootInBarrels()) continue;

                String treasureFilename = (modConfig != null
                        && modConfig.getBarrelTreasureFilename() != null
                        && !modConfig.getBarrelTreasureFilename().isEmpty())
                        ? modConfig.getBarrelTreasureFilename()
                        : moduleGeneratorsConfigFields.getBarrelTreasureFilename();
                if (treasureFilename == null || treasureFilename.isEmpty()) continue;

                ChestContents barrelContents = contentsByTreasure.get(treasureFilename);
                if (barrelContents == null && !contentsByTreasure.containsKey(treasureFilename)) {
                    TreasureConfigFields barrelTreasureFields = TreasureConfig.getConfigFields(treasureFilename);
                    barrelContents = barrelTreasureFields != null ? new ChestContents(barrelTreasureFields) : null;
                    contentsByTreasure.put(treasureFilename, barrelContents);
                }

                if (barrelContents == null) {
                    if (warnedMissingTreasures.add(treasureFilename)) {
                        Logger.warn("Module generator " + moduleGeneratorsConfigFields.getFilename()
                                + " has barrels referencing barrelTreasureFilename '" + treasureFilename
                                + "' but it did not resolve to a valid treasure config. Affected barrels will be empty.");
                    }
                    continue;
                }

                Block block = bp.location().getBlock();
                if (block.getType() != Material.BARREL) continue;
                if (!(block.getState() instanceof Container container)) continue;

                barrelContents.rollChestContents(container);
                ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
                Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
                if (!chestFillEvent.isCancelled()) container.update(true);
            }
        }

        for (EntitySpawn entitySpawn : entitiesToSpawn) {
            try {
                LivingEntity entity = (LivingEntity) world.spawnEntity(entitySpawn.location(), entitySpawn.entityType());
                entity.setRemoveWhenFarAway(false);
                entity.setPersistent(true);
            } catch (Exception e) {
                Logger.warn("Failed to spawn entity of type " + entitySpawn.entityType()
                        + " at " + entitySpawn.location());
            }
        }
    }

    private void notifyPlayers() {
        if (!DefaultConfig.isNewBuildingWarn()) return;

        Runnable notifier = () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission("betterstructures.warn")) continue;
                player.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage(
                                "[BetterStructures] New dungeon started generating! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                                "Click to teleport to " + startLocation.getWorld().getName() + ", "
                                        + startLocation.getBlockX() + ", " + startLocation.getBlockY() + ", " + startLocation.getBlockZ(),
                                "/betterstructures teleport " + startLocation.getWorld().getName() + " "
                                        + startLocation.getBlockX() + " " + startLocation.getBlockY() + " " + startLocation.getBlockZ())
                );
            }
        };

        if (Bukkit.isPrimaryThread()) notifier.run();
        else Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, notifier);
    }

    private void createModularWorld(World world, File worldFolder) {
        modularWorld = new ModularWorld(world, worldFolder, interpretedSigns);
    }

    private record EntityPasteInfo(Clipboard clipboard, Location location) {
    }

    private record ChestPlacement(Location location, Material material) {
    }

    private record BarrelPlacement(Location location, ModulesConfigFields modulesConfigFields) {
    }

    private record EntitySpawn(Location location, EntityType entityType) {
    }

    private record FawePlacement(
            BlockVector3 position,
            BaseBlock baseBlock,
            Material replacementMaterial,
            boolean bedrockFiller) {

        private static FawePlacement baseBlock(BlockVector3 position, BaseBlock baseBlock) {
            return new FawePlacement(position, baseBlock, null, false);
        }

        private static FawePlacement replacement(BlockVector3 position, Material material) {
            return new FawePlacement(position, null, material, false);
        }

        private static FawePlacement bedrockFiller(BlockVector3 position) {
            return new FawePlacement(position, null, null, true);
        }
    }

    public record InterpretedSign(Location location, List<String> text) {
    }
}
