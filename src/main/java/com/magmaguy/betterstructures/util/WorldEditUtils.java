package com.magmaguy.betterstructures.util;

import com.magmaguy.betterstructures.schematics.SchematicContainer;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;
import javax.annotation.Positive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorldEditUtils {

    public static Vector getSchematicOffset(Clipboard schematicClipboard) {
        return new Vector(
                schematicClipboard.getMinimumPoint().x() - schematicClipboard.getOrigin().x(),
                schematicClipboard.getMinimumPoint().y() - schematicClipboard.getOrigin().y(),
                schematicClipboard.getMinimumPoint().z() - schematicClipboard.getOrigin().z());
    }

    public static Material adaptMaterial(BlockState blockState) {
        try {
            return Material.matchMaterial(blockState.getBlockType().id().replace("minecraft:", ""));
        } catch (Exception ex) {
            return null;
        }
    }

    public static boolean isAir(BlockState blockState) {
        String id = blockState.getBlockType().id().toLowerCase(Locale.ROOT);
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    public static boolean isSolid(BlockState blockState) {
        Material material = adaptMaterial(blockState);
        return material != null && material.isSolid();
    }

    public static BlockData createBlockDataOrNull(BaseBlock baseBlock) {
        Material material = adaptMaterial(baseBlock.toImmutableState());
        if (material == null) return null;
        try {
            return Bukkit.createBlockData(baseBlock.toString());
        } catch (Exception ignored) {
            try {
                return material.createBlockData();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    public static String getBossFilename(CompoundTag data) {
        for (int line = 1; line <= 4; line++) {
            String lineString = getSignLine(data, line);
            if (lineString == null) continue;
            if (lineString.endsWith(".yml")) return lineString;
        }
        return null;
    }

    public static String getSignLine(@NotNull CompoundTag data, @Positive int line) {
        if (data.getValue().containsKey("Text" + line)) return getLegacyWEFormat(data, line);
        else return getNewWEFormat(data, line);
    }

    private static String getLegacyWEFormat(@NotNull CompoundTag data, @Positive int line) {
        try {
            String text = ((StringTag) data.getValue().get("Text" + line)).getValue();

            Pattern pattern = Pattern.compile("\\{\"text\":\"(.*?)\"\\}");
            Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                return matcher.group(1);
            } else {
                throw new Exception();
            }
        } catch (Exception ex) {
            Bukkit.getLogger().warning("Unexpected sign format in legacy read!\n" + data);
        }
        return null;
    }

    private static String getNewWEFormat(@NotNull CompoundTag data, @Positive int line) {
        try {
            CompoundTag frontText = (CompoundTag) data.getValue().get("front_text");
            ListTag messages = (ListTag) frontText.getValue().get("messages");
            String text = messages.getString(line - 1);

            if (text.contains("\"text\":")) text = text.split("text\":\"")[1].split("\"")[0];
            text = text.replaceAll("\"", "");
            if (text.contains("test")) Bukkit.getLogger().warning("boss name:" + text);
            return text;

        } catch (Exception ex) {
            Bukkit.getLogger().warning("Unexpected sign format in new read!\n" + data);
        }
        return null;
    }

    public static Clipboard createSingleBlockClipboard(Location location, BaseBlock baseBlock, BlockState blockState) {
        return new Clipboard() {
            @Override
            public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
                return false;
            }

            @Nullable
            @Override
            public Operation commit() {
                return null;
            }

            @Override
            public BlockState getBlock(BlockVector3 position) {
                return blockState;
            }

            @Override
            public BaseBlock getFullBlock(BlockVector3 position) {
                return baseBlock;
            }

            @Override
            public BlockVector3 getMinimumPoint() {
                return BlockVector3.at(0, 0, 0);
            }

            @Override
            public BlockVector3 getMaximumPoint() {
                return BlockVector3.at(0, 0, 0);
            }

            @Override
            public List<? extends Entity> getEntities(Region region) {
                return new ArrayList<>();
            }

            @Override
            public List<? extends Entity> getEntities() {
                return new ArrayList<>();
            }

            @Nullable
            @Override
            public Entity createEntity(com.sk89q.worldedit.util.Location location, BaseEntity entity) {
                return null;
            }

            @Override
            public void removeEntity(Entity entity) {
                // Synthetic single-block clipboard never contains entities.
            }

            @Override
            public Region getRegion() {
                return new CuboidRegion(BlockVector3.at(0, 0, 0), BlockVector3.at(0, 0, 0));
            }

            @Override
            public BlockVector3 getDimensions() {
                return BlockVector3.at(1, 1, 1);
            }

            @Override
            public BlockVector3 getOrigin() {
                return BlockVector3.at(0, 0, 0);
            }

            @Override
            public void setOrigin(BlockVector3 origin) {
            }
        };
    }

    public static void pasteArmorStandsOnlyFromTransformed(Clipboard transformedClipboard, Location location) {
        com.sk89q.worldedit.world.World adaptedWorld = BukkitAdapter.adapt(location.getWorld());

        try (EditSession editSession = WorldEdit.getInstance().newEditSession(adaptedWorld)) {
            editSession.setTrackingHistory(false);
            editSession.setSideEffectApplier(SideEffectSet.none());

            ClipboardHolder clipboardHolder = new ClipboardHolder(transformedClipboard);

            BlockVector3 minPoint = transformedClipboard.getMinimumPoint();
            BlockVector3 origin = transformedClipboard.getOrigin();

            BlockVector3 pastePosition = BlockVector3.at(
                    location.getBlockX() + (origin.x() - minPoint.x()),
                    location.getBlockY() + (origin.y() - minPoint.y()),
                    location.getBlockZ() + (origin.z() - minPoint.z())
            );

            Operation operation = clipboardHolder
                    .createPaste(editSession)
                    .to(pastePosition)
                    .copyEntities(true)
                    .copyBiomes(false)
                    .ignoreAirBlocks(true)
                    .build();

            Operations.complete(operation);
        } catch (WorldEditException ex) {
            Bukkit.getLogger().warning("Failed to paste schematic entities: " + ex.getMessage());
        }
    }

    public static Clipboard createSingleBlockClipboard(Location adjustedLocation, BaseBlock baseBlock, BlockState blockState, boolean unused) {
        return createSingleBlockClipboard(adjustedLocation, baseBlock, blockState);
    }
}
