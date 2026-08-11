package com.magmaguy.betterstructures.menus;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.betterstructures.config.contentpackages.ContentPackageConfigFields;
import com.magmaguy.betterstructures.content.BSPackage;
import com.magmaguy.betterstructures.content.BSPackageRefresher;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.menus.SetupMenuBuilder;
import com.magmaguy.magmacore.nightbreak.DownloadAllContentPackage;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BetterStructuresSetupMenu {
    private BetterStructuresSetupMenu() {
    }

    public static void createMenu(Player player) {
        List<BSPackage> rawBsPackages = new ArrayList<>(BSPackage.getBsPackages().values());
        List<BSPackage> bsPackages = rawBsPackages.stream()
                .sorted(Comparator.comparing(pkg ->
                        ChatColor.stripColor(ChatColorConverter.convert(pkg.getContentPackageConfigFields().getName()))))
                .collect(Collectors.toList());
        BSPackageRefresher.refreshContentAndAccess();

        // The published MagmaCore snapshot used by BetterStructures no longer matches
        // the newer NightbreakSetupControls helper. Keep the useful setup/package menu
        // while avoiding that moving convenience API in the Albion fork.
        MenuButton infoButton = new MenuButton(
                Material.BOOK,
                ChatColor.GREEN + "BetterStructures",
                List.of(
                        ChatColor.GRAY + "Original plugin by MagmaGuy",
                        ChatColor.GRAY + "AlbionMC performance fork",
                        ChatColor.YELLOW + "Content packages can be managed here.")) {
            @Override
            public void onClick(Player clickingPlayer) {
                clickingPlayer.sendMessage(ChatColor.GREEN + "BetterStructures Performance / Albion");
                clickingPlayer.sendMessage(ChatColor.GRAY + "Original BetterStructures created by MagmaGuy.");
            }
        };

        new SetupMenuBuilder((JavaPlugin) MetadataHandler.PLUGIN, player)
                .title("Setup menu")
                .infoButton(infoButton)
                .packages(bsPackages)
                .appendPackage(new DownloadAllContentPackage<>(
                        () -> new ArrayList<>(BSPackage.getBsPackages().values()),
                        "BetterStructures",
                        "https://nightbreak.io/plugin/betterstructures/",
                        "bs downloadall"))
                .addFilter(Material.GRASS_BLOCK, "Structure Packs",
                        (Predicate<BSPackage>) BetterStructuresSetupMenu::filterStructures)
                .addFilter(Material.DEEPSLATE_BRICKS, "Module Packs",
                        (Predicate<BSPackage>) BetterStructuresSetupMenu::filterModules)
                .open();
    }

    private static boolean filterStructures(BSPackage bsPackage) {
        return bsPackage.getContentPackageConfigFields().getContentPackageType() ==
                ContentPackageConfigFields.ContentPackageType.STRUCTURE;
    }

    private static boolean filterModules(BSPackage bsPackage) {
        return bsPackage.getContentPackageConfigFields().getContentPackageType() ==
                ContentPackageConfigFields.ContentPackageType.MODULAR;
    }
}
