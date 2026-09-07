package com.magmaguy.betterstructures.buildingfitter.util;

import com.magmaguy.betterstructures.util.SurfaceMaterials;
import com.magmaguy.betterstructures.util.WorldEditUtils;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

public class TerrainAdequacy {
    // A large schematic previously performed a full 3D sample every three
    // blocks for every candidate fit. Cap the sample count while preserving the
    // original density for normal-sized structures.
    private static final long MAX_TERRAIN_SAMPLES = 1024L;

    public enum ScanType {
        SURFACE,
        UNDERGROUND,
        AIR,
        LIQUID
    }

    public static double scan(int scanStep, Clipboard schematicClipboard, Location iteratedLocation, Vector schematicOffset, ScanType scanType) {
        int width = schematicClipboard.getDimensions().x();
        int depth = schematicClipboard.getDimensions().z();
        int height = schematicClipboard.getDimensions().y();
        int effectiveScanStep = effectiveScanStep(scanStep, width, height, depth);

        //Clipboard reads are absolute: the region spans [minimumPoint, maximumPoint], and reads
        //outside it silently return air. Zero-based coordinates must be offset by the minimum
        //point, the same way SchematicContainer and Schematic read clipboards.
        BlockVector3 minimumPoint = schematicClipboard.getMinimumPoint();

        int totalCount = 0;
        int negativeCount = 0;

        for (int x = 0; x < width; x += effectiveScanStep) {
            for (int y = 0; y < height; y += effectiveScanStep) {
                for (int z = 0; z < depth; z += effectiveScanStep) {
                    BlockState schematicBlockStateAtPosition = schematicClipboard.getBlock(BlockVector3.at(x, y, z).add(minimumPoint));
                    Material schematicMaterialAtPosition = WorldEditUtils.adaptMaterial(schematicBlockStateAtPosition);
                    boolean schematicBlockIsAir = WorldEditUtils.isAir(schematicBlockStateAtPosition);
                    boolean schematicBlockIsLiquid = schematicMaterialAtPosition == Material.WATER || schematicMaterialAtPosition == Material.LAVA;
                    Location projectedLocation = LocationProjector.project(iteratedLocation, schematicOffset, new Vector(x, y, z));
                    if (!isBlockAdequate(projectedLocation, schematicBlockIsAir, schematicBlockIsLiquid, iteratedLocation.getBlockY() - 1, scanType))
                        negativeCount++;
                    totalCount++;
                }
            }
        }

        if (totalCount == 0) return 0;
        return 100 - negativeCount * 100D / (double) totalCount;
    }

    static int effectiveScanStep(int requestedScanStep, int width, int height, int depth) {
        int step = Math.max(1, requestedScanStep);
        int largestDimension = Math.max(width, Math.max(height, depth));
        while (step < largestDimension && estimatedSampleCount(width, height, depth, step) > MAX_TERRAIN_SAMPLES) {
            step++;
        }
        return step;
    }

    private static long estimatedSampleCount(int width, int height, int depth, int step) {
        return ceilDiv(width, step) * ceilDiv(height, step) * ceilDiv(depth, step);
    }

    private static long ceilDiv(int value, int divisor) {
        if (value <= 0) return 0L;
        return ((long) value + divisor - 1L) / divisor;
    }

    private static boolean isBlockAdequate(Location projectedWorldLocation, boolean schematicBlockIsAir, boolean schematicBlockIsLiquid, int floorHeight, ScanType scanType) {
        int floorYValue = projectedWorldLocation.getBlockY();
        if (projectedWorldLocation.getBlock().getType().equals(Material.VOID_AIR)) return false;
        switch (scanType) {
            case SURFACE:
                if (floorYValue > floorHeight)
                    //for air level
                    return SurfaceMaterials.ignorable(projectedWorldLocation.getBlock().getType()) || !schematicBlockIsAir;
                else
                    //for underground level
                    return !projectedWorldLocation.getBlock().getType().isAir();
            case AIR:
                return projectedWorldLocation.getBlock().getType().isAir();
            case UNDERGROUND:
                return projectedWorldLocation.getBlock().getType().isSolid();
            case LIQUID:
                if (floorYValue > floorHeight) {
                    //for air level
                    return projectedWorldLocation.getBlock().getType().isAir();
                } else {
                    //for underwater level
                    if (schematicBlockIsLiquid)
                        return projectedWorldLocation.getBlock().isLiquid();
                    else
                        return true;
                }
            default:
                return false;
        }
    }
}
