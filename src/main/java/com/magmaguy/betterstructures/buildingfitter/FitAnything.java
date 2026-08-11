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
import com.sk89q.worldedit.extent.clipboard.Clipboard;
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
import org.bukkit.scheduler.BukkitRunnable;
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

        Function<Boolean, Material> pedestalMaterialProvider = this::getPedestalMaterial;
        Schematic.pasteSchematic(
                schematicClipboard,
                location,
                schematicOffset,
                pedestalMaterialProvider,
                onPasteComplete(fitAnything, location)
        );
    }

    private BukkitRunnable onPasteComplete(FitAnything fitAnything, Location location) {
        return new BukkitRunnable() {
            @Override
            public void run() {
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

                if (!(fitAnything instanceof FitAirBuilding)) {
                    try {
                        addPedestal(location);
                    } catch (Exception exception) {
                        Logger.warn("Failed to correctly assign pedestal material!");
                        exception.printStackTrace();
                    }
                    try {
                        if (fitAnything instanceof FitSurfaceBuilding)
                            clearTrees(location);
                    } catch (Exception exception) {
                        Logger.warn("Failed to correctly clear trees!");
                        exception.printStackTrace();
                    }
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
                try {
                    spawnProps(fitAnything.schematicClipboard);
                } catch (Exception exception) {
                    Logger.warn("Failed to correctly spawn props!");
                    exception.printStackTrace();
                }
            }
        };
    }

    private void spawnProps(Clipboard clipboard) {
        WorldEditUtils.pasteArmorStandsOnlyFromTransformed(clipboard, location.clone().add(schematicOffset));
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

        // Cap synchronous world reads on large schematics. Small structures retain
        // the original exact step=1 scan; large structures use a representative sample.
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

    private void addPedestal(Location location) {
        if (this instanceof FitAirBuilding || this instanceof FitLiquidBuilding) return;
        Location lowestCorner = location.clone().add(schematicOffset);
        for (int x = 0; x < schematicClipboard.getDimensions().x(); x++)
            for (int z = 0; z < schematicClipboard.getDimensions().z(); z++) {
                Block groundBlock = lowestCorner.clone().add(new Vector(x, 0, z)).getBlock();
                if (groundBlock.getType().isAir()) continue;
                for (int y = -1; y > -11; y--) {
                    Block block = lowestCorner.clone().add(new Vector(x, y, z)).getBlock();
                    if (SurfaceMaterials.ignorable(block.getType()))
                        block.setType(getPedestalMaterial(!block.getRelative(BlockFace.UP).getType().isSolid()), false);
                    else break;
                }
            }
    }

    private void clearTrees(Location location) {
        Location highestCorner = location.clone().add(schematicOffset).add(new Vector(0, schematicClipboard.getDimensions().y() + 1, 0));
        boolean detectedTreeElement = true;
        for (int x = 0; x < schematicClipboard.getDimensions().x(); x++)
            for (int z = 0; z < schematicClipboard.getDimensions().z(); z++) {
                for (int y = 0; y < 31; y++) {
                    if (!detectedTreeElement) break;
                    detectedTreeElement = false;
                    Block block = highestCorner.clone().add(new Vector(x, y, z)).getBlock();
                    if (SurfaceMaterials.ignorable(block.getType()) && !block.getType().isAir()) {
                        detectedTreeElement = true;
                        block.setType(Material.AIR, false);
                    }
                }
            }
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
            signLocation.getBlock().setType(Material.AIR);
            signLocation.add(new Vector(0.5, 0, 0.5));
            signLocation.getChunk().load();
            Entity entity = signLocation.getWorld().spawnEntity(signLocation, schematicContainer.getVanillaSpawns().get(entityPosition));
            entity.setPersistent(true);
            if (entity instanceof LivingEntity) ((LivingEntity) entity).setRemoveWhenFarAway(false);

            if (!VersionChecker.serverVersionOlderThan(21, 0) && entity.getType().equals(EntityType.END_CRYSTAL)) {
                EnderCrystal enderCrystal = (EnderCrystal) entity;
                enderCrystal.setShowingBottom(false);
            }
        }

        for (Vector elitePosition : schematicContainer.getEliteMobsSpawns().keySet()) {
            Location eliteLocation = LocationProjector.project(location, schematicOffset, elitePosition).clone();
            eliteLocation.getBlock().setType(Material.AIR);
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
            mobLocation.getBlock().setType(Material.AIR);
            if (!MythicMobs.Spawn(mobLocation, entry.getValue())) return;
        }
    }
}
