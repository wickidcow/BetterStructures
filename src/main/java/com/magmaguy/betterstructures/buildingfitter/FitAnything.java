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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
    //At 10% it is assumed a fit is so bad it's better just to skip
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

        initializePedestalMaterial(location);

        Function<Boolean, Material> pedestalMaterialProvider = this::getPedestalMaterial;

        // Pedestal material sampling, the actual schematic paste, pedestal/tree
        // cleanup, loot and entity setup are now one serialized operation. This
        // keeps every expensive stage under Schematic's shared per-tick budget.
        Schematic.pasteSchematic(
                schematicClipboard,
                location,
                schematicOffset,
                pedestalMaterialProvider,
                new PedestalSamplingWork(location),
                new PostPasteWork(location)
        );
    }

    private void initializePedestalMaterial(Location location) {
        if (!(this instanceof FitAirBuilding)) {
            pedestalMaterial = schematicContainer.getSchematicConfigField().getPedestalMaterial();
        }

        if (pedestalMaterial != null) return;

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
            if (randomNumber < cumulativeWeight) {
                return entry.getKey();
            }
        }

        throw new IllegalStateException("Weighted random selection failed.");
    }

    private void warnAdmins(Location buildLocation) {
        if (!DefaultConfig.isNewBuildingWarn()) return;

        String structureTypeString = structureType.toString().toLowerCase(Locale.ROOT).replace("_", " ");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("betterstructures.warn")) continue;
            player.spigot().sendMessage(
                    SpigotMessage.commandHoverMessage(
                            "[BetterStructures] New " + structureTypeString + " building generated! Click to teleport. Do \"/betterstructures silent\" to stop getting warnings!",
                            "Click to teleport to " + buildLocation.getWorld().getName() + ", " + buildLocation.getBlockX() + ", " + buildLocation.getBlockY() + ", " + buildLocation.getBlockZ()
                                    + "\n Schem name: " + schematicContainer.getConfigFilename(),
                            "/betterstructures teleport " + buildLocation.getWorld().getName() + " " + buildLocation.getBlockX() + " " + buildLocation.getBlockY() + " " + buildLocation.getBlockZ())
            );
        }
    }

    private void spawnProps(Clipboard clipboard) {
        // Don't add schematicOffset here - let pasteArmorStandsOnlyFromTransformed handle the alignment
        WorldEditUtils.pasteArmorStandsOnlyFromTransformed(clipboard, location.clone().add(schematicOffset));
    }

    private void addPedestalColumn(Location lowestCorner, int x, int z) {
        Block groundBlock = lowestCorner.clone().add(new Vector(x, 0, z)).getBlock();
        if (groundBlock.getType().isAir()) return;

        for (int y = -1; y > -11; y--) {
            Block block = lowestCorner.clone().add(new Vector(x, y, z)).getBlock();
            if (SurfaceMaterials.ignorable(block.getType())) {
                block.setType(getPedestalMaterial(!block.getRelative(BlockFace.UP).getType().isSolid()), false);
            } else {
                break;
            }
        }
    }

    private void clearTreeColumn(Location highestCorner, int x, int z) {
        boolean detectedTreeElement = true;
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

    private void fillChest(Vector chestPosition) {
        GeneratorConfigFields gen = schematicContainer.getGeneratorConfigFields();
        boolean barrelsEnabled = gen.isGenerateLootInBarrels() && gen.getBarrelContents() != null;
        boolean chestsEnabled = gen.getChestContents() != null;
        if (!barrelsEnabled && !chestsEnabled) return;

        Location chestLocation = LocationProjector.project(location, schematicOffset, chestPosition);
        if (!(chestLocation.getBlock().getState() instanceof Container container)) {
            Logger.warn("Expected a container for " + chestLocation.getBlock().getType() + " but didn't get it. Skipping this loot!");
            return;
        }

        boolean isBarrel = container.getBlock().getType() == Material.BARREL;
        if (isBarrel && !barrelsEnabled) return;
        if (!isBarrel && !chestsEnabled) return;

        ChestContents contents;
        String treasureFilename;
        if (isBarrel) {
            contents = schematicContainer.getBarrelContents();
            String schematicBarrelFile = schematicContainer.getSchematicConfigField().getBarrelTreasureFilename();
            treasureFilename = (schematicBarrelFile != null && !schematicBarrelFile.isEmpty())
                    ? schematicBarrelFile
                    : gen.getBarrelTreasureFilename();
        } else {
            contents = schematicContainer.getChestContents();
            String schematicTreasureFile = schematicContainer.getSchematicConfigField().getTreasureFile();
            treasureFilename = (schematicTreasureFile != null && !schematicTreasureFile.isEmpty())
                    ? schematicTreasureFile
                    : gen.getTreasureFilename();
        }

        if (contents == null) return;
        contents.rollChestContents(container);

        ChestFillEvent chestFillEvent = new ChestFillEvent(container, treasureFilename);
        Bukkit.getServer().getPluginManager().callEvent(chestFillEvent);
        if (!chestFillEvent.isCancelled()) {
            container.update(true);
        }
    }

    private void spawnVanillaEntity(Vector entityPosition) {
        Location signLocation = LocationProjector.project(location, schematicOffset, entityPosition).clone();
        signLocation.getBlock().setType(Material.AIR, false);
        signLocation.add(new Vector(0.5, 0, 0.5));

        // The old code force-loaded the chunk here even though the structure
        // paste already touched it. Avoiding that redundant synchronous load is
        // important while a player is rapidly generating terrain.
        Entity entity = signLocation.getWorld().spawnEntity(
                signLocation,
                schematicContainer.getVanillaSpawns().get(entityPosition));
        entity.setPersistent(true);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.setRemoveWhenFarAway(false);
        }

        if (!VersionChecker.serverVersionOlderThan(21, 0)
                && entity.getType().equals(EntityType.END_CRYSTAL)) {
            ((EnderCrystal) entity).setShowingBottom(false);
        }
    }

    private boolean spawnEliteEntity(Vector elitePosition) {
        Location eliteLocation = LocationProjector.project(location, schematicOffset, elitePosition).clone();
        eliteLocation.getBlock().setType(Material.AIR, false);
        eliteLocation.add(new Vector(0.5, 0, 0.5));
        String bossFilename = schematicContainer.getEliteMobsSpawns().get(elitePosition);

        if (!EliteMobs.Spawn(eliteLocation, bossFilename)) return false;

        Location lowestCorner = location.clone().add(schematicOffset);
        Location highestCorner = lowestCorner.clone().add(new Vector(
                schematicClipboard.getRegion().getWidth() - 1,
                schematicClipboard.getRegion().getHeight() - 1,
                schematicClipboard.getRegion().getLength() - 1));
        if (DefaultConfig.isProtectEliteMobsRegions()
                && Bukkit.getPluginManager().getPlugin("WorldGuard") != null
                && Bukkit.getPluginManager().getPlugin("EliteMobs") != null) {
            WorldGuard.Protect(lowestCorner, highestCorner, bossFilename, eliteLocation);
        } else if (!worldGuardWarn) {
            worldGuardWarn = true;
            Logger.warn("You are not using WorldGuard, so BetterStructures could not protect a boss arena! Using WorldGuard is recommended to guarantee a fair combat experience.");
        }
        return true;
    }

    private boolean spawnMythicEntity(Vector mythicPosition) {
        Location mobLocation = LocationProjector.project(location, schematicOffset, mythicPosition).clone();
        mobLocation.getBlock().setType(Material.AIR, false);
        return MythicMobs.Spawn(
                mobLocation,
                schematicContainer.getMythicMobsSpawns().get(mythicPosition));
    }

    private void logPostFailure(String message, Exception exception) {
        Logger.warn(message);
        exception.printStackTrace();
    }

    /**
     * Samples terrain one schematic X/Z column at a time before block placement.
     * The old implementation performed the entire 3D sampling pass synchronously
     * before the distributed paste even started.
     */
    private final class PedestalSamplingWork implements Schematic.IncrementalWork {
        private enum Phase {
            UNDERGROUND,
            SURFACE,
            DONE
        }

        private final Location lowestCorner;
        private final int width;
        private final int height;
        private final int depth;
        private Phase phase;
        private int x;
        private int z;

        private PedestalSamplingWork(Location buildLocation) {
            this.lowestCorner = buildLocation.clone().add(schematicOffset);
            this.width = schematicClipboard.getDimensions().x();
            this.height = schematicClipboard.getDimensions().y();
            this.depth = schematicClipboard.getDimensions().z();
            this.phase = FitAnything.this instanceof FitAirBuilding
                    ? Phase.DONE
                    : Phase.UNDERGROUND;
        }

        @Override
        public boolean hasNext() {
            return phase != Phase.DONE;
        }

        @Override
        public void runNext() {
            if (phase == Phase.UNDERGROUND) {
                sampleUndergroundColumn();
                advanceSampleColumn();
                if (x >= width) {
                    x = 0;
                    z = 0;
                    phase = Phase.SURFACE;
                }
                return;
            }

            if (phase == Phase.SURFACE) {
                sampleSurfaceColumn();
                advanceSampleColumn();
                if (x >= width) phase = Phase.DONE;
            }
        }

        private void sampleUndergroundColumn() {
            for (int y = 0; y < height; y += scanStep) {
                Block groundBlock = lowestCorner.clone().add(new Vector(x, y, z)).getBlock();
                Block aboveBlock = groundBlock.getRelative(BlockFace.UP);
                if (aboveBlock.getType().isSolid()
                        && groundBlock.getType().isSolid()
                        && !SurfaceMaterials.ignorable(groundBlock.getType())) {
                    undergroundPedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                }
            }
        }

        private void sampleSurfaceColumn() {
            boolean scanUp = lowestCorner.clone()
                    .add(new Vector(x, height, z))
                    .getBlock()
                    .getType()
                    .isSolid();
            for (int y = 0; y < 20; y++) {
                Block groundBlock = lowestCorner.clone()
                        .add(new Vector(x, scanUp ? y : -y, z))
                        .getBlock();
                Block aboveBlock = groundBlock.getRelative(BlockFace.UP);
                if (!aboveBlock.getType().isSolid() && groundBlock.getType().isSolid()) {
                    surfacePedestalMaterials.merge(groundBlock.getType(), 1, Integer::sum);
                    break;
                }
            }
        }

        private void advanceSampleColumn() {
            z += scanStep;
            if (z >= depth) {
                z = 0;
                x += scanStep;
            }
        }
    }

    /**
     * Incremental completion work. Each call performs at most one structure
     * column, one container, one mob, or the final entity-only FAWE paste.
     */
    private final class PostPasteWork implements Schematic.IncrementalWork {
        private enum Phase {
            WARN,
            PEDESTAL,
            TREES,
            CHESTS,
            VANILLA_ENTITIES,
            ELITE_ENTITIES,
            MYTHIC_ENTITIES,
            PROPS,
            DONE
        }

        private final Location buildLocation;
        private final Location lowestCorner;
        private final Location highestCorner;
        private final int width;
        private final int depth;
        private final Iterator<Vector> chestIterator;
        private final Iterator<Vector> vanillaIterator;
        private final Iterator<Vector> eliteIterator;
        private final Iterator<Vector> mythicIterator;

        private Phase phase = Phase.WARN;
        private int x;
        private int z;

        private PostPasteWork(Location buildLocation) {
            this.buildLocation = buildLocation;
            this.lowestCorner = buildLocation.clone().add(schematicOffset);
            this.highestCorner = buildLocation.clone()
                    .add(schematicOffset)
                    .add(new Vector(0, schematicClipboard.getDimensions().y() + 1, 0));
            this.width = schematicClipboard.getDimensions().x();
            this.depth = schematicClipboard.getDimensions().z();
            this.chestIterator = new ArrayList<>(schematicContainer.getChestLocations()).iterator();
            this.vanillaIterator = new ArrayList<>(schematicContainer.getVanillaSpawns().keySet()).iterator();
            this.eliteIterator = new ArrayList<>(schematicContainer.getEliteMobsSpawns().keySet()).iterator();
            this.mythicIterator = new ArrayList<>(schematicContainer.getMythicMobsSpawns().keySet()).iterator();
        }

        @Override
        public boolean hasNext() {
            return phase != Phase.DONE;
        }

        @Override
        public void runNext() {
            switch (phase) {
                case WARN:
                    try {
                        warnAdmins(buildLocation);
                    } catch (Exception exception) {
                        logPostFailure("Failed to send BetterStructures generation warning!", exception);
                    }
                    phase = Phase.PEDESTAL;
                    break;
                case PEDESTAL:
                    runPedestalStep();
                    break;
                case TREES:
                    runTreeStep();
                    break;
                case CHESTS:
                    runChestStep();
                    break;
                case VANILLA_ENTITIES:
                    runVanillaEntityStep();
                    break;
                case ELITE_ENTITIES:
                    runEliteEntityStep();
                    break;
                case MYTHIC_ENTITIES:
                    runMythicEntityStep();
                    break;
                case PROPS:
                    try {
                        spawnProps(schematicClipboard);
                    } catch (Exception exception) {
                        logPostFailure("Failed to correctly spawn props!", exception);
                    }
                    phase = Phase.DONE;
                    break;
                case DONE:
                    break;
            }
        }

        private void runPedestalStep() {
            if (FitAnything.this instanceof FitAirBuilding
                    || FitAnything.this instanceof FitLiquidBuilding
                    || width <= 0
                    || depth <= 0) {
                resetColumns();
                phase = Phase.TREES;
                return;
            }

            int columnX = x;
            int columnZ = z;
            advanceFullColumn();
            try {
                addPedestalColumn(lowestCorner, columnX, columnZ);
            } catch (Exception exception) {
                logPostFailure("Failed to correctly assign pedestal material!", exception);
            }

            if (x >= width) {
                resetColumns();
                phase = Phase.TREES;
            }
        }

        private void runTreeStep() {
            if (!(FitAnything.this instanceof FitSurfaceBuilding)
                    || width <= 0
                    || depth <= 0) {
                resetColumns();
                phase = Phase.CHESTS;
                return;
            }

            int columnX = x;
            int columnZ = z;
            advanceFullColumn();
            try {
                clearTreeColumn(highestCorner, columnX, columnZ);
            } catch (Exception exception) {
                logPostFailure("Failed to correctly clear trees!", exception);
            }

            if (x >= width) {
                resetColumns();
                phase = Phase.CHESTS;
            }
        }

        private void runChestStep() {
            if (!chestIterator.hasNext()) {
                phase = Phase.VANILLA_ENTITIES;
                return;
            }

            Vector chestPosition = chestIterator.next();
            try {
                fillChest(chestPosition);
            } catch (Exception exception) {
                logPostFailure("Failed to correctly fill chest!", exception);
            }
        }

        private void runVanillaEntityStep() {
            if (!vanillaIterator.hasNext()) {
                phase = Phase.ELITE_ENTITIES;
                return;
            }

            Vector entityPosition = vanillaIterator.next();
            try {
                spawnVanillaEntity(entityPosition);
            } catch (Exception exception) {
                logPostFailure("Failed to correctly spawn vanilla entity!", exception);
            }
        }

        private void runEliteEntityStep() {
            if (!eliteIterator.hasNext()) {
                phase = Phase.MYTHIC_ENTITIES;
                return;
            }

            Vector elitePosition = eliteIterator.next();
            try {
                if (!spawnEliteEntity(elitePosition)) {
                    phase = Phase.PROPS;
                }
            } catch (Exception exception) {
                logPostFailure("Failed to correctly spawn EliteMobs entity!", exception);
            }
        }

        private void runMythicEntityStep() {
            if (!mythicIterator.hasNext()) {
                phase = Phase.PROPS;
                return;
            }

            Vector mythicPosition = mythicIterator.next();
            try {
                if (!spawnMythicEntity(mythicPosition)) {
                    phase = Phase.PROPS;
                }
            } catch (Exception exception) {
                logPostFailure("Failed to correctly spawn MythicMobs entity!", exception);
            }
        }

        private void advanceFullColumn() {
            z++;
            if (z >= depth) {
                z = 0;
                x++;
            }
        }

        private void resetColumns() {
            x = 0;
            z = 0;
        }
    }
}
