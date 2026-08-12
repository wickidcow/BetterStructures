package com.magmaguy.betterstructures.buildingfitter;

import com.magmaguy.betterstructures.api.BuildPlaceEvent;
import com.magmaguy.betterstructures.api.ChestFillEvent;
import com.magmaguy.betterstructures.buildingfitter.util.FitUndergroundDeepBuilding;
import com.magmaguy.betterstructures.buildingfitter.util.LocationProjector;
import com.magmaguy.betterstructures.buildingfitter.util.SchematicPicker;
import com.magmaguy.betterstructures.chests.ChestContents;
import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.config.generators.GeneratorConfigFields;
import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.magmaguy.betterstructures.thirdparty.EliteMobs;
import com.magmaguy.betterstructures.thirdparty.MythicMobs;
import com.magmaguy.betterstructures.thirdparty.WorldGuard;
import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.magmaguy.betterstructures.worldedit.Schematic;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import com.magmaguy.magmacore.util.VersionChecker;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

public class FitAnything {
    public static boolean worldGuardWarn = false;
    protected final int searchRadius = 1;
    protected final int scanStep = 3;
    private final HashMap<Material, Integer> undergroundPedestalMaterials = new HashMap<>();
    private final HashMap<Material, Integer> surfacePedestalMaterials = new HashMap<>();
    @Getter
    protected SchematicContainer schematicContainer;
    protected double startingScore = 100;
    @Getter
    protected Clipboard schematicClipboard = null;
    @Getter
    protected Vector schematicOffset;
    protected int verticalOffset = 0;
    protected double highestScore = 10;
    @Getter
    protected Location location = null;
    protected GeneratorConfigFields.StructureType structureType;
    private Material pedestalMaterial = null;

    public FitAnything(SchematicContainer schematicContainer) {
        this.schematicContainer = schematicContainer;
        this.verticalOffset = schematicContainer.getClipboard().getMinimumPoint().y() - schematicContainer.getClipboard().getOrigin().y();
    }

    public FitAnything() {
    }

    public static void commandBasedCreation(Chunk chunk, GeneratorConfigFields.StructureType structureType, SchematicContainer container) {
        switch (structureType) {
            case SKY:
                new FitAirBuilding(chunk, container);
                break;
            case SURFACE:
                new FitSurfaceBuilding(chunk, container);
                break;
            case LIQUID_SURFACE:
                new FitLiquidBuilding(chunk, container);
                break;
            case UNDERGROUND_DEEP:
                FitUndergroundDeepBuilding.fit(chunk, container);
                break;
            case UNDERGROUND_SHALLOW:
                FitUndergroundShallowBuilding.fit(chunk, container);
                break;
            default:
        }
    }

    protected void randomizeSchematicContainer(Location location, GeneratorConfigFields.StructureType structureType) {
        if (schematicClipboard != null) return;
        schematicContainer = SchematicPicker.pick(location, structureType);
        if (schematicContainer != null) {
            schematicClipboard = schematicContainer.getClipboard();
            verticalOffset = schematicContainer.getClipboard().getMinimumPoint().y() - schematicContainer.getClipboard().getOrigin().y();
        }
    }

    protected void paste(Location location) {
        BuildPlaceEvent buildPlaceEvent = new BuildPlaceEvent(this);
        Bukkit.getServer().getPluginManager().callEvent(buildPlaceEvent);
        if (buildPlaceEvent.isCancelled()) return;

        FitAnything fitAnything = this;

        // Terrain sampling is a Bukkit read and therefore remains on the primary thread,
        // after every structure chunk has been prepared/ticketed by Schematic.
        Runnable prePasteCallback = () -> {
            assignPedestalMaterial(location);
            if (pedestalMaterial == null)
                switch (location.getWorld().getEnvironment()) {
                    case NETHER:
                        pedestalMaterial = Material.NETHERRACK;
                        break;
                    case THE_END:
                        pedestalMaterial = Material.END_STONE;
                        break;
                    default:
                        pedestalMaterial = Material.STONE;
                }
        };

        Function<Boolean, Material> pedestalMaterialProvider = this::getPedestalMaterial;
        Schematic.FawePostProcessor fawePostProcessor = this::applyFawePostProcessing;

        Schematic.pasteSchematic(
                schematicClipboard,
                location,
                schematicOffset,
                prePasteCallback,
                pedestalMaterialProvider,
                fawePostProcessor,
                onPasteComplete(fitAnything, location)
        );
    }

    /**
     * Runs after the schematic blocks are in the same async FAWE EditSession, while the
     * structure's chunks are still ticketed. No Bukkit block mutations are allowed here.
     */
    private void applyFawePostProcessing(EditSession editSession, Location adjustedLocation) throws Exception {
        if (!(this instanceof FitAirBuilding)) {
            if (!(this instanceof FitLiquidBuilding)) {
                addPedestalFawe(editSession, adjustedLocation);
            }
            if (this instanceof FitSurfaceBuilding) {
                clearTreesFawe(editSession, adjustedLocation);
            }
        }

        clearEntityMarkersFawe(editSession, adjustedLocation);

        // Entity clipboard placement is also a FAWE operation and no longer consumes the
        // primary-thread completion phase.
        WorldEditUtils.pasteArmorStandsOnlyFromTransformed(schematicClipboard, adjustedLocation);
    }

    private Runnable onPasteComplete(FitAnything fitAnything, Location location) {
        return () -> {
            if (DefaultConfig.isNewBuildingWarn()) {
                String structureTypeString = fitAnything.structureType.toString().toLowerCase(Locale.ROOT).replace("_", " ");
                for (Player player : Bukkit.getOnlinePlayers())
                    if (player.hasPermission("betterstructures.warn"))
                        player.spigot().sendMessage(
                                SpigotMessage.commandHoverMessage("[BetterStructures] New " + structureTypeString + " building generated! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                                        "Click to teleport to " + location.getWorld().getName() + ", " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + "\n Schem name: " + schematicContainer.getConfigFilename(),
                                        "/betterstructures teleport " + location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ())
                        );
            }

            try {
                fillChests();
            } catch (Exception exception) {
                Logger.warn("Failed to correctly fill chests!");
                exception.printStackTrace();
            }
            try {
                spawnEntities();
            } catch (Exception exception) {
                Logger.warn("Failed to correctly spawn entities!");
                exception.printStackTrace();
            }
        };
    }

    private void assignPedestalMaterial(Location location) {
        if (this instanceof FitAirBuilding) return;
        pedestalMaterial = schematicContainer.getSchematicConfigField().getPedestalMaterial();
        Location lowestCorner = location.clone().add(schematicOffset);

        int sizeX = schematicClipboard.getDimensions().x();
        int sizeY = schematicClipboard.getDimensions().y();
        int sizeZ = schematicClipboard.getDimensions().z();
        int baseX = lowestCorner.getBlockX();
        int baseY = lowestCorner.getBlockY();
        int baseZ = lowestCorner.getBlockZ();
        World world = lowestCorner.getWorld();

        int xStep = Math.max(1, Math.ceilDiv(sizeX, 24));
        int yStep = Math.max(1, Math.ceilDiv(sizeY, 12));
        int zStep = Math.max(1, Math.ceilDiv(sizeZ, 24));

        for (int x = 0; x < sizeX; x += xStep)
            for (int z = 0; z < sizeZ; z += zStep)
                for (int y = 0; y < sizeY; y += yStep) {
                    Block groundBlock = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
                    Block aboveBlock = groundBlock.getRelative(BlockFace.UP);
                    if (aboveBlock.getType().isSolid()
                            && groundBlock.getType().isSolid()
                            && !SurfaceMaterials.ignorable(groundBlock.getType())) {
                        undergroundPedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                    }
                }

        int maxSurfaceHeightScan = 20;
        for (int x = 0; x < sizeX; x += xStep)
            for (int z = 0; z < sizeZ; z += zStep) {
                boolean scanUp = world.getBlockAt(baseX + x, baseY + sizeY, baseZ + z).getType().isSolid();
                for (int y = 0; y < maxSurfaceHeightScan; y++) {
                    Block groundBlock = world.getBlockAt(baseX + x, baseY + (scanUp ? y : -y), baseZ + z);
                    Block aboveBlock = groundBlock.getRelative(BlockFace.UP);
                    if (!aboveBlock.getType().isSolid() && groundBlock.getType().isSolid()) {
                        surfacePedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                        break;
                    }
                }
            }
    }

    private Material getPedestalMaterial(boolean isPedestalSurface) {
        if (isPedestalSurface) {
            if (surfacePedestalMaterials.isEmpty()) return pedestalMaterial;
            return getRandomMaterialBasedOnWeight(surfacePedestalMaterials);
        } else {
            if (undergroundPedestalMaterials.isEmpty()) return pedestalMaterial;
            return getRandomMaterialBasedOnWeight(undergroundPedestalMaterials);
        }
    }

    public Material getRandomMaterialBasedOnWeight(HashMap<Material, Integer> weightedMaterials) {
        int totalWeight = weightedMaterials.values().stream().mapToInt(Integer::intValue).sum();
        int randomNumber = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulativeWeight = 0;
        for (Map.Entry<Material, Integer> entry : weightedMaterials.entrySet()) {
            cumulativeWeight += entry.getValue();
            if (randomNumber < cumulativeWeight) return entry.getKey();
        }
        throw new IllegalStateException("Weighted random selection failed.");
    }

    private void addPedestalFawe(EditSession editSession, Location adjustedLocation) throws Exception {
        int sizeX = schematicClipboard.getDimensions().x();
        int sizeZ = schematicClipboard.getDimensions().z();
        int baseX = adjustedLocation.getBlockX();
        int baseY = adjustedLocation.getBlockY();
        int baseZ = adjustedLocation.getBlockZ();

        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                BlockVector3 ground = BlockVector3.at(baseX + x, baseY, baseZ + z);
                if (editSession.getBlock(ground).getBlockType().getMaterial().isAir()) continue;

                for (int y = -1; y > -11; y--) {
                    BlockVector3 position = BlockVector3.at(baseX + x, baseY + y, baseZ + z);
                    Material existing = WorldEditUtils.adaptMaterial(editSession.getBlock(position));
                    if (existing == null || !SurfaceMaterials.ignorable(existing)) break;

                    boolean surface = !editSession.getBlock(position.add(0, 1, 0))
                            .getBlockType().getMaterial().isSolid();
                    Material replacement = getPedestalMaterial(surface);
                    if (replacement != null) {
                        editSession.setBlock(position, BukkitAdapter.adapt(replacement.createBlockData()));
                    }
                }
            }
        }
    }

    private void clearTreesFawe(EditSession editSession, Location adjustedLocation) throws Exception {
        int sizeX = schematicClipboard.getDimensions().x();
        int sizeZ = schematicClipboard.getDimensions().z();
        int baseX = adjustedLocation.getBlockX();
        int baseY = adjustedLocation.getBlockY() + schematicClipboard.getDimensions().y() + 1;
        int baseZ = adjustedLocation.getBlockZ();

        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int y = 0; y < 31; y++) {
                    BlockVector3 position = BlockVector3.at(baseX + x, baseY + y, baseZ + z);
                    Material existing = WorldEditUtils.adaptMaterial(editSession.getBlock(position));
                    if (existing != null && !existing.isAir() && SurfaceMaterials.ignorable(existing)) {
                        editSession.setBlock(position, BukkitAdapter.adapt(Material.AIR.createBlockData()));
                    } else {
                        break;
                    }
                }
            }
        }
    }

    private void clearEntityMarkersFawe(EditSession editSession, Location adjustedLocation) throws Exception {
        for (Vector position : schematicContainer.getVanillaSpawns().keySet()) {
            clearMarker(editSession, adjustedLocation, position);
        }
        for (Vector position : schematicContainer.getEliteMobsSpawns().keySet()) {
            clearMarker(editSession, adjustedLocation, position);
        }
        for (Vector position : schematicContainer.getMythicMobsSpawns().keySet()) {
            clearMarker(editSession, adjustedLocation, position);
        }
    }

    private void clearMarker(EditSession editSession, Location adjustedLocation, Vector relative) throws Exception {
        BlockVector3 worldPosition = BlockVector3.at(
                adjustedLocation.getBlockX() + relative.getBlockX(),
                adjustedLocation.getBlockY() + relative.getBlockY(),
                adjustedLocation.getBlockZ() + relative.getBlockZ());
        editSession.setBlock(worldPosition, BukkitAdapter.adapt(Material.AIR.createBlockData()));
    }

    private void fillChests() {
        GeneratorConfigFields gen = schematicContainer.getGeneratorConfigFields();
        boolean barrelsEnabled = gen.isGenerateLootInBarrels() && gen.getBarrelContents() != null;
        boolean chestsEnabled = gen.getChestContents() != null;
        if (!barrelsEnabled && !chestsEnabled) return;

        for (Vector chestPosition : schematicContainer.getChestLocations()) {
            Location chestLocation = LocationProjector.project(location, schematicOffset, chestPosition);
            if (!(chestLocation.getBlock().getState() instanceof Container container)) {
                Logger.warn("Expected a container for " + chestLocation.getBlock().getType() + " but didn't get it. Skipping this loot!");
                continue;
            }

            boolean isBarrel = container.getBlock().getType() == Material.BARREL;
            if (isBarrel && !barrelsEnabled) continue;
            if (!isBarrel && !chestsEnabled) continue;

            ChestContents contents;
            String treasureFilename;
            if (isBarrel) {
                contents = schematicContainer.getBarrelContents();
                String schematicBarrelFile = schematicContainer.getSchematicConfigField().getBarrelTreasureFilename();
                treasureFilename = (schematicBarrelFile != null && !schematicBarrelFile.isEmpty())
                        ? schematicBarrelFile : gen.getBarrelTreasureFilename();
            } else {
                contents = schematicContainer.getChestContents();
                String schematicTreasureFile = schematicContainer.getSchematicConfigField().getTreasureFile();
                treasureFilename = (schematicTreasureFile != null && !schematicTreasureFile.isEmpty())
                        ? schematicTreasureFile : gen.getTreasureFilename();
            }

            if (contents == null) continue;
            contents.rollChestContents(container);
            ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
            Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
            if (!chestFillEvent.isCancelled()) container.update(true);
        }
    }

    private void spawnEntities() {
        for (Vector entityPosition : schematicContainer.getVanillaSpawns().keySet()) {
            Location signLocation = LocationProjector.project(location, schematicOffset, entityPosition).clone();
            signLocation.add(new Vector(0.5, 0, 0.5));
            Entity entity = signLocation.getWorld().spawnEntity(signLocation, schematicContainer.getVanillaSpawns().get(entityPosition));
            entity.setPersistent(true);
            if (entity instanceof LivingEntity livingEntity) livingEntity.setRemoveWhenFarAway(false);

            if (!VersionChecker.serverVersionOlderThan(21, 0) && entity.getType().equals(EntityType.END_CRYSTAL)) {
                EnderCrystal enderCrystal = (EnderCrystal) entity;
                enderCrystal.setShowingBottom(false);
            }
        }

        for (Vector elitePosition : schematicContainer.getEliteMobsSpawns().keySet()) {
            Location eliteLocation = LocationProjector.project(location, schematicOffset, elitePosition).clone();
            eliteLocation.add(new Vector(0.5, 0, 0.5));
            String bossFilename = schematicContainer.getEliteMobsSpawns().get(elitePosition);
            if (!EliteMobs.Spawn(eliteLocation, bossFilename)) return;
            Location lowestCorner = location.clone().add(schematicOffset);
            Location highestCorner = lowestCorner.clone().add(new Vector(schematicClipboard.getRegion().getWidth() - 1, schematicClipboard.getRegion().getHeight(), schematicClipboard.getRegion().getLength() - 1));
            if (DefaultConfig.isProtectEliteMobsRegions()
                    && Bukkit.getPluginManager().getPlugin("WorldGuard") != null
                    && Bukkit.getPluginManager().getPlugin("EliteMobs") != null) {
                WorldGuard.Protect(lowestCorner, highestCorner, bossFilename, eliteLocation);
            } else if (!worldGuardWarn) {
                worldGuardWarn = true;
                Logger.warn("You are not using WorldGuard, so BetterStructures could not protect a boss arena! Using WorldGuard is recommended to guarantee a fair combat experience.");
            }
        }

        for (Map.Entry<Vector, String> entry : schematicContainer.getMythicMobsSpawns().entrySet()) {
            Location mobLocation = LocationProjector.project(location, schematicOffset, entry.getKey()).clone();
            if (!MythicMobs.Spawn(mobLocation, entry.getValue())) return;
        }
    }
}
