package com.magmaguy.betterstructures.modules;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

public class WorldInitializer {

    public static World generateWorld(String worldName, Player player) {
        WorldCreator worldCreator = new WorldCreator(worldName);
        worldCreator.environment(World.Environment.NORMAL);
        // Paper 1.21.9+ removed functional always-loaded spawn chunks. There is no
        // replacement needed for BetterStructures' generated module worlds.
        worldCreator.generator(new VoidGenerator());
        World world = worldCreator.createWorld();
        if (world == null) {
            throw new IllegalStateException("Failed to create BetterStructures module world " + worldName);
        }
        world.setAutoSave(false);
        player.setGameMode(GameMode.SPECTATOR);
        return world;
    }

    private static class VoidGenerator extends ChunkGenerator {
    }
}
