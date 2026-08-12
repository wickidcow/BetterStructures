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
 * Removes obsolete bed block-entity records from legacy schematic NBT before
 * WorldEdit/FAWE sends them through Minecraft's DataFixer.
 *
 * <p>Minecraft 26.2 removed the {@code minecraft:bed} block entity. Older
 * Sponge/MCEdit schematics can still contain those records even though the bed
 * itself is fully represented by its block state. Keeping the stale block
 * entity causes Minecraft's 26.2 data fixer to report
 * {@code Unsupported key: minecraft:bed} once for every old bed record.</p>
 *
 * <p>This sanitizer intentionally removes only list entries whose direct
 * {@code Id} or {@code id} string is exactly {@code minecraft:bed}, and only
 * from {@code BlockEntities} or {@code TileEntities} lists. Palette and block
 * state data are copied byte-for-byte, so bed colour, facing and part remain
 * untouched.</p>
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
    private static final byte[] ID_UPPER = ascii("Id");
    private static final byte[] ID_LOWER = ascii("id");
    private static final byte[] BED_ID = ascii("minecraft:bed");

    private LegacySchematicSanitizer() {
    }

    /**
     * Opens a schematic for WorldEdit, sanitizing obsolete bed block entities
     * in memory when present. The source file is never modified.
     *
     * <p>If the file is not a gzip-compressed NBT schematic, or if its NBT
     * cannot be parsed by this compatibility pass, the original file stream is
     * returned unchanged so WorldEdit remains the authority for format/error
     * handling.</p>
     */
    public static SanitizedInput open(File schematicFile) throws IOException {
        try {
            Counter removed = new Counter();
            NamedTag root;
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                    new GZIPInputStream(new FileInputStream(schematicFile))))) {
                root = readNamedTag(input, 0, removed);
            }

            if (removed.value == 0) {
                return originalInput(schematicFile);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(bytes)))) {
                writeNamedTag(output, root);
            }

            return new SanitizedInput(new ByteArrayInputStream(bytes.toByteArray()), removed.value);
        } catch (IOException | RuntimeException ignored) {
            // Do not replace WorldEdit's own format/error handling with ours.
            // This pass is deliberately best-effort and only exists for the
            // removed 26.2 bed block-entity compatibility case.
            return originalInput(schematicFile);
        }
    }

    private static SanitizedInput originalInput(File schematicFile) throws IOException {
        return new SanitizedInput(new BufferedInputStream(new FileInputStream(schematicFile)), 0);
    }

    private static NamedTag readNamedTag(DataInputStream input, int depth, Counter removed) throws IOException {
        checkDepth(depth);
        byte type = input.readByte();
        if (type == TAG_END) {
            throw new IOException("NBT root tag cannot be TAG_End");
        }
        RawString name = readRawString(input);
        return new NamedTag(name, readPayload(input, type, name, depth + 1, removed));
    }

    private static NbtTag readPayload(
            DataInputStream input,
            byte type,
            RawString name,
            int depth,
            Counter removed) throws IOException {
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
            case TAG_LIST -> readList(input, name, depth + 1, removed);
            case TAG_COMPOUND -> readCompound(input, depth + 1, removed);
            case TAG_INT_ARRAY -> {
                int length = checkedLength(input.readInt(), "int array");
                int[] value = new int[length];
                for (int i = 0; i < length; i++) {
                    value[i] = input.readInt();
                }
                yield new NbtTag(type, value);
            }
            case TAG_LONG_ARRAY -> {
                int length = checkedLength(input.readInt(), "long array");
                long[] value = new long[length];
                for (int i = 0; i < length; i++) {
                    value[i] = input.readLong();
                }
                yield new NbtTag(type, value);
            }
            default -> throw new IOException("Unsupported NBT tag type " + type);
        };
    }

    private static NbtTag readList(DataInputStream input, RawString name, int depth, Counter removed) throws IOException {
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
            NbtTag element = readPayload(input, elementType, null, depth + 1, removed);
            if (bedEntityList && isRemovedBedBlockEntity(element)) {
                removed.value++;
            } else {
                elements.add(element);
            }
        }

        return new NbtTag(TAG_LIST, new ListPayload(elementType, elements));
    }

    private static NbtTag readCompound(DataInputStream input, int depth, Counter removed) throws IOException {
        checkDepth(depth);
        List<NamedTag> entries = new ArrayList<>();
        while (true) {
            byte type = input.readByte();
            if (type == TAG_END) {
                break;
            }
            RawString name = readRawString(input);
            entries.add(new NamedTag(name, readPayload(input, type, name, depth + 1, removed)));
        }
        return new NbtTag(TAG_COMPOUND, entries);
    }

    private static boolean isRemovedBedBlockEntity(NbtTag tag) {
        if (tag.type != TAG_COMPOUND) {
            return false;
        }

        @SuppressWarnings("unchecked")
        List<NamedTag> entries = (List<NamedTag>) tag.value;
        for (NamedTag entry : entries) {
            if (entry.tag.type != TAG_STRING) {
                continue;
            }
            if (!entry.name.equalsBytes(ID_UPPER) && !entry.name.equalsBytes(ID_LOWER)) {
                continue;
            }
            RawString id = (RawString) entry.tag.value;
            if (id.equalsBytes(BED_ID)) {
                return true;
            }
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
                for (NbtTag element : list.elements) {
                    writePayload(output, element);
                }
            }
            case TAG_COMPOUND -> {
                @SuppressWarnings("unchecked")
                List<NamedTag> entries = (List<NamedTag>) tag.value;
                for (NamedTag entry : entries) {
                    writeNamedTag(output, entry);
                }
                output.writeByte(TAG_END);
            }
            case TAG_INT_ARRAY -> {
                int[] value = (int[]) tag.value;
                output.writeInt(value.length);
                for (int entry : value) {
                    output.writeInt(entry);
                }
            }
            case TAG_LONG_ARRAY -> {
                long[] value = (long[]) tag.value;
                output.writeInt(value.length);
                for (long entry : value) {
                    output.writeLong(entry);
                }
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
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting exceeds " + MAX_DEPTH + " levels");
        }
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

        private boolean equalsBytes(byte[] expected) {
            return Arrays.equals(bytes, expected);
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

    public record SanitizedInput(InputStream inputStream, int removedBedBlockEntities) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
