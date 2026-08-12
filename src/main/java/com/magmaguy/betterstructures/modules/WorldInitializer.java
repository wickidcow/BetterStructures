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
        // Paper 26.x no longer keeps spawn chunks permanently loaded, so the old
        // WorldCreator#keepSpawnInMemory(false) option has been removed and is
        // unnecessary for this temporary void world.
        worldCreator.generator(new VoidGenerator());
        World world = worldCreator.createWorld();
        world.setAutoSave(false);
//        player.teleport(new Location(world, 8, 16, 8));
        player.setGameMode(GameMode.SPECTATOR);
        return world;
    }

    private static class VoidGenerator extends ChunkGenerator {
    }
}
