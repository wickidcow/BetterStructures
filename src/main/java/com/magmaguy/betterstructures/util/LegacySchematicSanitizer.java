package com.magmaguy.betterstructures.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Repairs the legacy bed representation used by older schematic formats before
 * WorldEdit/FAWE sends the data through Minecraft 26.2's DataFixer.
 *
 * <p>Minecraft 26.2 no longer accepts the obsolete {@code minecraft:bed}
 * block-entity id. Older Sponge/MCEdit schematics may also contain a palette key
 * named {@code minecraft:bed}. This compatibility pass removes only the stale
 * bed block-entity records and rewrites only legacy bed palette keys to
 * {@code minecraft:red_bed}. Existing modern bed colours are never changed.</p>
 *
 * <p>When a legacy palette key includes block-state properties, for example
 * {@code minecraft:bed[facing=north,part=foot,occupied=false]}, the complete
 * property suffix is preserved when it becomes {@code minecraft:red_bed[...]}.</p>
 */
public final class LegacySchematicSanitizer {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_SHORT = 2;
    private static final byte TAG_INT = 3;
    private static final byte TAG_LONG = 4;
    private static final byte TAG_FLOAT = 5;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_BYTE_ARRAY = 7;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;
    private static final byte TAG_INT_ARRAY = 11;
    private static final byte TAG_LONG_ARRAY = 12;

    private static final int MAX_DEPTH = 512;
    private static final int MAX_COLLECTION_ELEMENTS = 64 * 1024 * 1024;

    private static final byte[] BLOCK_ENTITIES = ascii("BlockEntities");
    private static final byte[] TILE_ENTITIES = ascii("TileEntities");
    private static final byte[] PALETTE = ascii("Palette");
    private static final byte[] ID_UPPER = ascii("Id");
    private static final byte[] ID_LOWER = ascii("id");
    private static final byte[] BED_ID = ascii("minecraft:bed");
    private static final String LEGACY_BED_BLOCK = "minecraft:bed";
    private static final String RED_BED_BLOCK = "minecraft:red_bed";

    private LegacySchematicSanitizer() {
    }

    /**
     * Opens a schematic for WorldEdit, repairing legacy bed data in memory when
     * present. The source schematic file is never modified.
     *
     * <p>If the file is not a gzip-compressed NBT schematic, or if this small
     * compatibility parser cannot read it, the original file stream is returned
     * unchanged so WorldEdit remains the authority for normal format handling.</p>
     */
    public static SanitizedInput open(File schematicFile) throws IOException {
        try {
            Counter removedBlockEntities = new Counter();
            Counter replacedPaletteEntries = new Counter();
            NamedTag root;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(schematicFile))))) {
                root = readNamedTag(input, 0, removedBlockEntities, replacedPaletteEntries);
            }

            if (removedBlockEntities.value == 0 && replacedPaletteEntries.value == 0) {
                return originalInput(schematicFile);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(bytes)))) {
                writeNamedTag(output, root);
            }

            return new SanitizedInput(
                    new ByteArrayInputStream(bytes.toByteArray()),
                    removedBlockEntities.value,
                    replacedPaletteEntries.value);
        } catch (IOException | RuntimeException ignored) {
            // Do not replace WorldEdit's own format/error handling with ours.
            return originalInput(schematicFile);
        }
    }

    private static SanitizedInput originalInput(File schematicFile) throws IOException {
        return new SanitizedInput(new BufferedInputStream(new FileInputStream(schematicFile)), 0, 0);
    }

    private static NamedTag readNamedTag(
            DataInputStream input,
            int depth,
            Counter removedBlockEntities,
            Counter replacedPaletteEntries) throws IOException {
        checkDepth(depth);
        byte type = input.readByte();
        if (type == TAG_END) {
            throw new IOException("NBT root tag cannot be TAG_End");
        }
        RawString name = readRawString(input);
        return new NamedTag(name, readPayload(
                input, type, name, depth + 1, removedBlockEntities, replacedPaletteEntries));
    }

    private static NbtTag readPayload(
            DataInputStream input,
            byte type,
            RawString name,
            int depth,
            Counter removedBlockEntities,
            Counter replacedPaletteEntries) throws IOException {
        checkDepth(depth);
        return switch (type) {
            case TAG_BYTE -> new NbtTag(type, input.readByte());
            case TAG_SHORT -> new NbtTag(type, input.readShort());
            case TAG_INT -> new NbtTag(type, input.readInt());
            case TAG_LONG -> new NbtTag(type, input.readLong());
            case TAG_FLOAT -> new NbtTag(type, input.readFloat());
            case TAG_DOUBLE -> new NbtTag(type, input.readDouble());
            case TAG_BYTE_ARRAY -> {
                int length = checkedLength(input.readInt(), "byte array");
                byte[] value = new byte[length];
                input.readFully(value);
                yield new NbtTag(type, value);
            }
            case TAG_STRING -> new NbtTag(type, readRawString(input));
            case TAG_LIST -> readList(
                    input, name, depth + 1, removedBlockEntities, replacedPaletteEntries);
            case TAG_COMPOUND -> readCompound(
                    input, name, depth + 1, removedBlockEntities, replacedPaletteEntries);
            case TAG_INT_ARRAY -> {
                int length = checkedLength(input.readInt(), "int array");
                int[] value = new int[length];
                for (int i = 0; i < length; i++) value[i] = input.readInt();
                yield new NbtTag(type, value);
            }
            case TAG_LONG_ARRAY -> {
                int length = checkedLength(input.readInt(), "long array");
                long[] value = new long[length];
                for (int i = 0; i < length; i++) value[i] = input.readLong();
                yield new NbtTag(type, value);
            }
            default -> throw new IOException("Unsupported NBT tag type " + type);
        };
    }

    private static NbtTag readList(
            DataInputStream input,
            RawString name,
            int depth,
            Counter removedBlockEntities,
            Counter replacedPaletteEntries) throws IOException {
        checkDepth(depth);
        byte elementType = input.readByte();
        int length = checkedLength(input.readInt(), "list");
        if (elementType == TAG_END && length != 0) {
            throw new IOException("Non-empty NBT list cannot use TAG_End elements");
        }

        boolean bedEntityList = elementType == TAG_COMPOUND
                && name != null
                && (name.equalsBytes(BLOCK_ENTITIES) || name.equalsBytes(TILE_ENTITIES));

        List<NbtTag> elements = new ArrayList<>(Math.min(length, 4096));
        for (int i = 0; i < length; i++) {
            NbtTag element = readPayload(
                    input, elementType, null, depth + 1, removedBlockEntities, replacedPaletteEntries);
            if (bedEntityList && isRemovedBedBlockEntity(element)) {
                removedBlockEntities.value++;
            } else {
                elements.add(element);
            }
        }

        return new NbtTag(TAG_LIST, new ListPayload(elementType, elements));
    }

    private static NbtTag readCompound(
            DataInputStream input,
            RawString compoundName,
            int depth,
            Counter removedBlockEntities,
            Counter replacedPaletteEntries) throws IOException {
        checkDepth(depth);
        List<NamedTag> entries = new ArrayList<>();
        boolean paletteCompound = compoundName != null && compoundName.equalsBytes(PALETTE);

        while (true) {
            byte type = input.readByte();
            if (type == TAG_END) break;

            RawString entryName = readRawString(input);
            if (paletteCompound) {
                RawString normalizedName = normalizeLegacyBedPaletteName(entryName);
                if (normalizedName != entryName) {
                    entryName = normalizedName;
                    replacedPaletteEntries.value++;
                }
            }

            entries.add(new NamedTag(entryName, readPayload(
                    input, type, entryName, depth + 1, removedBlockEntities, replacedPaletteEntries)));
        }
        return new NbtTag(TAG_COMPOUND, entries);
    }

    private static RawString normalizeLegacyBedPaletteName(RawString name) {
        String value = name.asUtf8();
        if (value.equals(LEGACY_BED_BLOCK)) {
            return RawString.utf8(RED_BED_BLOCK);
        }
        String statePrefix = LEGACY_BED_BLOCK + "[";
        if (value.startsWith(statePrefix)) {
            return RawString.utf8(RED_BED_BLOCK + value.substring(LEGACY_BED_BLOCK.length()));
        }
        return name;
    }

    private static boolean isRemovedBedBlockEntity(NbtTag tag) {
        if (tag.type != TAG_COMPOUND) return false;

        @SuppressWarnings("unchecked")
        List<NamedTag> entries = (List<NamedTag>) tag.value;
        for (NamedTag entry : entries) {
            if (entry.tag.type != TAG_STRING) continue;
            if (!entry.name.equalsBytes(ID_UPPER) && !entry.name.equalsBytes(ID_LOWER)) continue;
            RawString id = (RawString) entry.tag.value;
            if (id.equalsBytes(BED_ID)) return true;
        }
        return false;
    }

    private static void writeNamedTag(DataOutputStream output, NamedTag tag) throws IOException {
        output.writeByte(tag.tag.type);
        writeRawString(output, tag.name);
        writePayload(output, tag.tag);
    }

    private static void writePayload(DataOutputStream output, NbtTag tag) throws IOException {
        switch (tag.type) {
            case TAG_BYTE -> output.writeByte((Byte) tag.value);
            case TAG_SHORT -> output.writeShort((Short) tag.value);
            case TAG_INT -> output.writeInt((Integer) tag.value);
            case TAG_LONG -> output.writeLong((Long) tag.value);
            case TAG_FLOAT -> output.writeFloat((Float) tag.value);
            case TAG_DOUBLE -> output.writeDouble((Double) tag.value);
            case TAG_BYTE_ARRAY -> {
                byte[] value = (byte[]) tag.value;
                output.writeInt(value.length);
                output.write(value);
            }
            case TAG_STRING -> writeRawString(output, (RawString) tag.value);
            case TAG_LIST -> {
                ListPayload list = (ListPayload) tag.value;
                output.writeByte(list.elementType);
                output.writeInt(list.elements.size());
                for (NbtTag element : list.elements) writePayload(output, element);
            }
            case TAG_COMPOUND -> {
                @SuppressWarnings("unchecked")
                List<NamedTag> entries = (List<NamedTag>) tag.value;
                for (NamedTag entry : entries) writeNamedTag(output, entry);
                output.writeByte(TAG_END);
            }
            case TAG_INT_ARRAY -> {
                int[] value = (int[]) tag.value;
                output.writeInt(value.length);
                for (int entry : value) output.writeInt(entry);
            }
            case TAG_LONG_ARRAY -> {
                long[] value = (long[]) tag.value;
                output.writeInt(value.length);
                for (long entry : value) output.writeLong(entry);
            }
            default -> throw new IOException("Unsupported NBT tag type " + tag.type);
        }
    }

    private static RawString readRawString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new RawString(bytes);
    }

    private static void writeRawString(DataOutputStream output, RawString string) throws IOException {
        if (string.bytes.length > 0xFFFF) {
            throw new IOException("NBT string exceeds unsigned-short length limit");
        }
        output.writeShort(string.bytes.length);
        output.write(string.bytes);
    }

    private static int checkedLength(int length, String type) throws IOException {
        if (length < 0 || length > MAX_COLLECTION_ELEMENTS) {
            throw new IOException("Invalid NBT " + type + " length: " + length);
        }
        return length;
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nesting exceeds " + MAX_DEPTH + " levels");
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static final class Counter {
        private int value;
    }

    private static final class RawString {
        private final byte[] bytes;

        private RawString(byte[] bytes) {
            this.bytes = bytes;
        }

        private static RawString utf8(String value) {
            return new RawString(value.getBytes(StandardCharsets.UTF_8));
        }

        private boolean equalsBytes(byte[] expected) {
            return Arrays.equals(bytes, expected);
        }

        private String asUtf8() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static final class NbtTag {
        private final byte type;
        private final Object value;

        private NbtTag(byte type, Object value) {
            this.type = type;
            this.value = value;
        }
    }

    private static final class NamedTag {
        private final RawString name;
        private final NbtTag tag;

        private NamedTag(RawString name, NbtTag tag) {
            this.name = name;
            this.tag = tag;
        }
    }

    private static final class ListPayload {
        private final byte elementType;
        private final List<NbtTag> elements;

        private ListPayload(byte elementType, List<NbtTag> elements) {
            this.elementType = elementType;
            this.elements = elements;
        }
    }

    public record SanitizedInput(
            InputStream inputStream,
            int removedBedBlockEntities,
            int replacedBedPaletteEntries) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
