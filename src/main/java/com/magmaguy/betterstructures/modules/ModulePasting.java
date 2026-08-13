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
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import com.magmaguy.magmacore.util.WorkloadRunnable;
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
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ModulePasting {
    private static final EnumSet<Material> SIGN_MATERIALS = EnumSet.noneOf(Material.class);

    static {
        for (Material m : Material.values())
            if (m.toString().toUpperCase(Locale.ROOT).contains("SIGN")) SIGN_MATERIALS.add(m);
    }

    private final List<InterpretedSign> interpretedSigns = new ArrayList<>();
    private final List<ChestPlacement> chestsToPlace = new ArrayList<>();
    private final List<BarrelPlacement> barrelsToFill = new ArrayList<>();
    private final List<EntitySpawn> entitiesToSpawn = new ArrayList<>();
    private final String spawnPoolSuffix;
    private final Location startLocation;
    private final boolean createModularWorld;
    private final List<NbtPlacement> nbtToPlace = new ArrayList<>();
    private ModularWorld modularWorld;
    private final World world;
    private final File worldFolder;
    private final ModuleGeneratorsConfigFields moduleGeneratorsConfigFields;

    public ModulePasting(World world, File worldFolder, Deque<WFCNode> WFCNodeDeque, String spawnPoolSuffix, Location startLocation, ModuleGeneratorsConfigFields moduleGeneratorsConfigFields) {
        this.spawnPoolSuffix = spawnPoolSuffix;
        this.startLocation = startLocation;
        this.world = world;
        this.worldFolder = worldFolder;
        this.moduleGeneratorsConfigFields = moduleGeneratorsConfigFields;

        WFCNode firstNode = WFCNodeDeque.peek();
        this.createModularWorld = firstNode != null && firstNode.getWfcGenerator() != null &&
                firstNode.getWfcGenerator().getModuleGeneratorsConfigFields().isWorldGeneration();

        batchPaste(WFCNodeDeque, interpretedSigns);

        if (DefaultConfig.isNewBuildingWarn()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("betterstructures.warn")) {
                    player.spigot().sendMessage(
                            SpigotMessage.commandHoverMessage(
                                    "[BetterStructures] New dungeon started generating! Do not stop your server now. Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                                    "Click to teleport to " + startLocation.getWorld().getName() + ", " +
                                            startLocation.getBlockX() + ", " + startLocation.getBlockY() + ", " + startLocation.getBlockZ(),
                                    "/betterstructures teleport " + startLocation.getWorld().getName() + " " +
                                            startLocation.getBlockX() + " " + startLocation.getBlockY() + " " + startLocation.getBlockZ())
                    );
                }
            }
        }
    }

    private static boolean isNbtRichMaterial(Material m) {
        if (m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL) return false;
        if (m.name().endsWith("_SIGN") || m.name().endsWith("_WALL_SIGN") || m.name().endsWith("_HANGING_SIGN"))
            return false;

        return switch (m) {
            case SPAWNER,
                 DISPENSER, DROPPER, HOPPER,
                 BEACON, LECTERN, JUKEBOX,
                 COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, CHAIN_COMMAND_BLOCK,
                 PLAYER_HEAD, PLAYER_WALL_HEAD,
                 SCULK_CATALYST, SCULK_SHRIEKER -> true;
            default -> false;
        };
    }

    public static void paste(Clipboard clipboard, Location location, Integer rotation) {
        if (rotation == null) return;

        AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));
        Clipboard transformedClipboard;
        try {
            transformedClipboard = clipboard.transform(transform);
        } catch (WorldEditException e) {
            Logger.warn("Failed to transform clipboard: " + e.getMessage());
            throw new RuntimeException(e);
        }

        BlockVector3 minPoint = transformedClipboard.getMinimumPoint();
        World world = location.getWorld();
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(world);

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());

            transformedClipboard.getRegion().forEach(blockPos -> {
                try {
                    BaseBlock baseBlock = transformedClipboard.getFullBlock(blockPos);
                    if (baseBlock.getBlockType().getMaterial().isAir()) return;

                    int worldX = baseX + (blockPos.x() - minPoint.x());
                    int worldY = baseY + (blockPos.y() - minPoint.y());
                    int worldZ = baseZ + (blockPos.z() - minPoint.z());
                    editSession.setBlock(BlockVector3.at(worldX, worldY, worldZ), baseBlock);
                } catch (WorldEditException e) {
                    Logger.warn("Failed to place block at " + blockPos + ": " + e.getMessage());
                }
            });

            WorldEditUtils.pasteArmorStandsOnlyFromTransformed(transformedClipboard, location);
        } catch (Exception e) {
            Logger.warn("Failed to paste structure: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static int normalizeRotation(int rotation) {
        return (360 - rotation) % 360;
    }

    public static void pasteArmorStands(Clipboard clipboard, Location location, Integer rotation) {
        if (rotation == null) rotation = 0;

        AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(rotation));
        Clipboard transformedClipboard;
        try {
            transformedClipboard = clipboard.transform(transform);
        } catch (WorldEditException e) {
            Logger.warn("Failed to transform clipboard for entities: " + e.getMessage());
            return;
        }

        WorldEditUtils.pasteArmorStandsOnlyFromTransformed(transformedClipboard, location);
    }

    private List<Pasteable> generatePasteMeList(Clipboard transformedClipboard,
                                                 Location worldPasteOriginLocation,
                                                 List<InterpretedSign> interpretedSigns,
                                                 ModulesConfigFields modulesConfigFields) {
        List<Pasteable> pasteableList = new ArrayList<>();
        BlockVector3 minPoint = transformedClipboard.getMinimumPoint();

        World world = worldPasteOriginLocation.getWorld();
        int baseX = worldPasteOriginLocation.getBlockX();
        int baseY = worldPasteOriginLocation.getBlockY();
        int baseZ = worldPasteOriginLocation.getBlockZ();

        transformedClipboard.getRegion().forEach(blockPos -> {
            BaseBlock baseBlock = transformedClipboard.getFullBlock(blockPos);
            BlockState blockState = baseBlock.toImmutableState();
            if (createModularWorld && WorldEditUtils.isAir(blockState)) return;

            int worldX = baseX + (blockPos.x() - minPoint.x());
            int worldY = baseY + (blockPos.y() - minPoint.y());
            int worldZ = baseZ + (blockPos.z() - minPoint.z());

            Location pasteLocation = new Location(world, worldX, worldY, worldZ);
            Material material = WorldEditUtils.adaptMaterial(blockState);
            if (material == Material.BARRIER) return;

            BlockData blockData = material == null ? null : WorldEditUtils.createBlockDataOrNull(baseBlock);
            if (blockData == null) {
                nbtToPlace.add(new NbtPlacement(pasteLocation, baseBlock));
                return;
            }

            if (SIGN_MATERIALS.contains(blockData.getMaterial())) {
                List<String> lines = getLines(baseBlock);
                interpretedSigns.add(new InterpretedSign(pasteLocation, lines));

                for (String line : lines) {
                    if (line.contains("[spawn]") && lines.size() > 1) {
                        try {
                            EntityType entityType = EntityType.valueOf(lines.get(1).toUpperCase());
                            entitiesToSpawn.add(new EntitySpawn(pasteLocation, entityType));
                        } catch (Exception e) {
                            Logger.warn("Invalid entity type in sign: " + lines.get(1));
                        }
                    } else if (line.contains("[chest]")) {
                        chestsToPlace.add(new ChestPlacement(pasteLocation, Material.CHEST));
                    } else if (line.contains("[trapped_chest]")) {
                        chestsToPlace.add(new ChestPlacement(pasteLocation, Material.TRAPPED_CHEST));
                    }
                }

                blockData = Material.AIR.createBlockData();
            }

            if (blockData.getMaterial().equals(Material.BEDROCK)) {
                if (pasteLocation.getBlock().getType().isSolid()) return;
                blockData = Material.STONE.createBlockData();
            }

            if (isNbtRichMaterial(blockData.getMaterial())) {
                nbtToPlace.add(new NbtPlacement(pasteLocation, baseBlock));
                return;
            }

            if (blockData.getMaterial() == Material.BARREL) {
                barrelsToFill.add(new BarrelPlacement(pasteLocation, modulesConfigFields));
            }

            pasteableList.add(new Pasteable(pasteLocation, blockData));
        });

        return pasteableList;
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

    private void batchPaste(Deque<WFCNode> WFCNodeDeque, List<InterpretedSign> interpretedSigns) {
        List<Pasteable> pasteableList = new ArrayList<>();
        List<EntityPasteInfo> entityPasteInfos = new ArrayList<>();

        while (!WFCNodeDeque.isEmpty()) {
            WFCNode WFCNode = WFCNodeDeque.poll();
            if (WFCNode == null || WFCNode.getModulesContainer() == null) continue;
            Clipboard clipboard = WFCNode.getModulesContainer().getClipboard();
            if (clipboard == null) continue;

            AffineTransform transform = new AffineTransform().rotateY(normalizeRotation(WFCNode.getModulesContainer().getRotation()));
            Clipboard transformedClipboard;
            try {
                transformedClipboard = clipboard.transform(transform);
            } catch (WorldEditException e) {
                throw new RuntimeException(e);
            }

            ModulesConfigFields modulesConfigField = WFCNode.getModulesContainer().getModulesConfigField();
            pasteableList.addAll(generatePasteMeList(transformedClipboard, WFCNode.getRealLocation(startLocation),
                    interpretedSigns, modulesConfigField));
            entityPasteInfos.add(new EntityPasteInfo(transformedClipboard, WFCNode.getRealLocation(startLocation)));
        }

        // Use one WorldEdit edit session for the normal world-based block phase. At runtime
        // FastAsyncWorldEdit provides the implementation and optimized queued placement engine.
        // Never interleave Bukkit block writes with an active FAWE session: on Paper 26.2 that
        // can expose a pending block entity while its matching base block is still observed as air.
        final EditSession fastEditSession;
        if (this.createModularWorld) {
            fastEditSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world));
            fastEditSession.setTrackingHistory(false);
            fastEditSession.setSideEffectApplier(SideEffectSet.none());
        } else {
            fastEditSession = null;
        }

        List<Pasteable> fallbackBlocks = new ArrayList<>();
        WorkloadRunnable pasteMeRunnable = new WorkloadRunnable(.1, () -> {
            if (fastEditSession != null) {
                try {
                    fastEditSession.close();
                } catch (Exception e) {
                    Logger.warn("Failed to close FAWE structure paste session cleanly: " + e.getMessage());
                }
            }

            if (fallbackBlocks.isEmpty()) {
                Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN,
                        () -> postPasteProcessing(entityPasteInfos));
                return;
            }

            WorkloadRunnable fallbackPlacementRunnable = new WorkloadRunnable(.1, () ->
                    postPasteProcessing(entityPasteInfos));
            for (Pasteable fallbackBlock : fallbackBlocks) {
                fallbackPlacementRunnable.addWorkload(() -> {
                    try {
                        fallbackBlock.location.getBlock().setBlockData(fallbackBlock.blockData, false);
                    } catch (Exception e) {
                        Logger.warn("Bukkit fallback placement failed at " + fallbackBlock.location + ": " + e.getMessage());
                    }
                });
            }
            fallbackPlacementRunnable.runTaskTimer(MetadataHandler.PLUGIN, 1, 1);
        });

        for (Pasteable pasteable : pasteableList) {
            if (fastEditSession == null) {
                fallbackBlocks.add(pasteable);
                continue;
            }

            final BlockState worldEditState;
            try {
                worldEditState = BukkitAdapter.adapt(pasteable.blockData);
            } catch (RuntimeException e) {
                Logger.warn("Could not adapt block data for FAWE at " + pasteable.location + ": " + e.getMessage());
                fallbackBlocks.add(pasteable);
                continue;
            }

            if (worldEditState == null) {
                fallbackBlocks.add(pasteable);
                continue;
            }

            pasteMeRunnable.addWorkload(() -> {
                try {
                    fastEditSession.setBlock(
                            BlockVector3.at(
                                    pasteable.location.getBlockX(),
                                    pasteable.location.getBlockY(),
                                    pasteable.location.getBlockZ()),
                            worldEditState);
                } catch (WorldEditException | RuntimeException e) {
                    Logger.warn("FAWE placement failed at " + pasteable.location + ": " + e.getMessage());
                    fallbackBlocks.add(pasteable);
                }
            });
        }

        pasteMeRunnable.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    private void postPasteProcessing(List<EntityPasteInfo> entityPasteInfos) {
        if (!nbtToPlace.isEmpty()) {
            com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(world);
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
                editSession.setTrackingHistory(false);
                editSession.setSideEffectApplier(SideEffectSet.none());

                for (NbtPlacement np : nbtToPlace) {
                    BlockVector3 wp = BlockVector3.at(
                            np.location().getBlockX(),
                            np.location().getBlockY(),
                            np.location().getBlockZ());
                    try {
                        editSession.setBlock(wp, np.baseBlock());
                    } catch (WorldEditException e) {
                        Logger.warn("Failed to set NBT block at " + np.location() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Logger.warn("Failed NBT post-paste session: " + e.getMessage());
            }

            // FAWE may finish queued block-entity work as the edit session closes. Continue one
            // server tick later before Bukkit reads/updates containers or other block state.
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN,
                    () -> finishPostPasteProcessing(entityPasteInfos));
            return;
        }

        finishPostPasteProcessing(entityPasteInfos);
    }

    private void finishPostPasteProcessing(List<EntityPasteInfo> entityPasteInfos) {
        if (createModularWorld) {
            createModularWorld(world, worldFolder);
            modularWorld.spawnOtherEntities();
        }

        pasteArmorStandsForBatch(entityPasteInfos);

        if (!chestsToPlace.isEmpty()) {
            String treasureFilename = moduleGeneratorsConfigFields.getTreasureFile();
            TreasureConfigFields treasureConfigFields = TreasureConfig.getConfigFields(treasureFilename);
            ChestContents chestContents = treasureConfigFields == null ? null : treasureConfigFields.getChestContents();
            for (ChestPlacement chestPlacement : chestsToPlace) {
                Block block = chestPlacement.location.getBlock();
                block.setType(chestPlacement.material);

                if (block.getBlockData() instanceof Chest chest) {
                    block.setBlockData(chest, false);

                    if (chestContents != null) {
                        Container container = (Container) block.getState();
                        chestContents.rollChestContents(container);
                        ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
                        Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
                        if (!chestFillEvent.isCancelled())
                            container.update(true);
                    }
                }
            }
        }

        if (moduleGeneratorsConfigFields.isGenerateLootInBarrels() && !barrelsToFill.isEmpty()) {
            Map<String, ChestContents> contentsByTreasure = new HashMap<>();
            Set<String> warnedMissingTreasures = new HashSet<>();
            for (BarrelPlacement bp : barrelsToFill) {
                ModulesConfigFields modConfig = bp.modulesConfigFields();
                if (modConfig != null && !modConfig.isGenerateLootInBarrels()) continue;

                String treasureFilename = (modConfig != null && modConfig.getBarrelTreasureFilename() != null && !modConfig.getBarrelTreasureFilename().isEmpty())
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
                        Logger.warn("Module generator " + moduleGeneratorsConfigFields.getFilename() + " has barrels referencing barrelTreasureFilename '" + treasureFilename + "' but it did not resolve to a valid treasure config. Affected barrels will be empty.");
                    }
                    continue;
                }

                Block block = bp.location().getBlock();
                if (block.getType() != Material.BARREL) continue;
                if (!(block.getState() instanceof Container container)) continue;

                barrelContents.rollChestContents(container);
                ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
                Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
                if (!chestFillEvent.isCancelled()) {
                    container.update(true);
                }
            }
        }

        for (EntitySpawn entitySpawn : entitiesToSpawn) {
            try {
                LivingEntity entity = (LivingEntity) world.spawnEntity(entitySpawn.location, entitySpawn.entityType);
                entity.setRemoveWhenFarAway(false);
                entity.setPersistent(true);
            } catch (Exception e) {
                Logger.warn("Failed to spawn entity of type " + entitySpawn.entityType + " at " + entitySpawn.location);
            }
        }
    }

    private void pasteArmorStandsForBatch(List<EntityPasteInfo> entityPasteInfos) {
        for (EntityPasteInfo info : entityPasteInfos) {
            try {
                WorldEditUtils.pasteArmorStandsOnlyFromTransformed(info.clipboard, info.location);
            } catch (Exception e) {
                Logger.warn("Failed to paste entities for batch operation at " + info.location + ": " + e.getMessage());
            }
        }
    }

    private void createModularWorld(World world, File worldFolder) {
        modularWorld = new ModularWorld(world, worldFolder, interpretedSigns);
    }

    private record NbtPlacement(Location location, BaseBlock baseBlock) {
    }

    private record EntityPasteInfo(Clipboard clipboard, Location location) {
    }

    private record ChestPlacement(Location location, Material material) {
    }

    private record BarrelPlacement(Location location, ModulesConfigFields modulesConfigFields) {
    }

    private record EntitySpawn(Location location, EntityType entityType) {
    }

    public record InterpretedSign(Location location, List<String> text) {
    }

    private record Pasteable(Location location, BlockData blockData) {
    }
}
