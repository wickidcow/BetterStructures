package com.magmaguy.betterstructures.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySchematicSanitizerTest {

    @TempDir
    Path tempDir;

    @Test
    void replacesLegacyBedPaletteWithRedBedAndRemovesObsoleteBlockEntity() throws Exception {
        Path schematic = tempDir.resolve("legacy-bed.schem");
        writeSchematic(schematic, "BlockEntities", "Id");

        byte[] sanitized;
        try (LegacySchematicSanitizer.SanitizedInput input = LegacySchematicSanitizer.open(schematic.toFile())) {
            assertEquals(1, input.removedBedBlockEntities());
            assertEquals(2, input.replacedBedPaletteEntries());
            sanitized = input.inputStream().readAllBytes();
        }

        String nbtBytes = new String(decompress(sanitized), StandardCharsets.ISO_8859_1);
        assertFalse(nbtBytes.contains("minecraft:bed"));
        assertTrue(nbtBytes.contains("minecraft:red_bed[facing=north,part=foot,occupied=false]"));
        assertTrue(nbtBytes.contains("minecraft:red_bed"));
        assertTrue(nbtBytes.contains("minecraft:blue_bed[facing=south,part=head,occupied=false]"));
        assertTrue(nbtBytes.contains("minecraft:chest"));
        assertTrue(nbtBytes.contains("preserve-me"));
    }

    @Test
    void alsoHandlesLegacyTileEntitiesWithLowercaseId() throws Exception {
        Path schematic = tempDir.resolve("legacy-tile-bed.schematic");
        writeSchematic(schematic, "TileEntities", "id");

        try (LegacySchematicSanitizer.SanitizedInput input = LegacySchematicSanitizer.open(schematic.toFile())) {
            assertEquals(1, input.removedBedBlockEntities());
            assertEquals(2, input.replacedBedPaletteEntries());
            String nbtBytes = new String(decompress(input.inputStream().readAllBytes()), StandardCharsets.ISO_8859_1);
            assertFalse(nbtBytes.contains("minecraft:bed"));
            assertTrue(nbtBytes.contains("minecraft:red_bed"));
            assertTrue(nbtBytes.contains("minecraft:chest"));
        }
    }

    private static void writeSchematic(Path path, String entityListName, String idKey) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            output.writeByte(10);
            writeString(output, "Schematic");

            // Sponge-style Palette compound. Only the obsolete generic bed keys should change.
            output.writeByte(10);
            writeString(output, "Palette");
            writePaletteEntry(output, "minecraft:bed[facing=north,part=foot,occupied=false]", 0);
            writePaletteEntry(output, "minecraft:bed", 1);
            writePaletteEntry(output, "minecraft:blue_bed[facing=south,part=head,occupied=false]", 2);
            writePaletteEntry(output, "minecraft:stone", 3);
            output.writeByte(0);

            output.writeByte(8);
            writeString(output, "Marker");
            writeString(output, "preserve-me");

            output.writeByte(9);
            writeString(output, entityListName);
            output.writeByte(10);
            output.writeInt(2);

            writeBlockEntity(output, idKey, "minecraft:bed");
            writeBlockEntity(output, idKey, "minecraft:chest");

            output.writeByte(0);
        }
    }

    private static void writePaletteEntry(DataOutputStream output, String blockState, int paletteId) throws IOException {
        output.writeByte(3);
        writeString(output, blockState);
        output.writeInt(paletteId);
    }

    private static void writeBlockEntity(DataOutputStream output, String idKey, String id) throws IOException {
        output.writeByte(8);
        writeString(output, idKey);
        writeString(output, id);

        output.writeByte(11);
        writeString(output, "Pos");
        output.writeInt(3);
        output.writeInt(1);
        output.writeInt(2);
        output.writeInt(3);

        output.writeByte(0);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] decompress(byte[] gzipBytes) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
            return input.readAllBytes();
        }
    }
}
