package com.magmaguy.betterstructures.commands;

import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfig;
import com.magmaguy.betterstructures.config.modulegenerators.ModuleGeneratorsConfigFields;
import com.magmaguy.betterstructures.modules.WFCGenerator;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;

import java.util.ArrayList;
import java.util.List;

public class GenerateModulesCommand extends AdvancedCommand {
    public GenerateModulesCommand() {
        super(List.of("generateModules"));
        setUsage("/bs generateModules <ModuleGeneratorsConfigFile.yml>");
        // Generator configs are fully loaded before commands are registered, so a stable
        // snapshot list gives us tab completion without relying on the removed dynamic
        // argument class from newer/unpublished MagmaCore revisions.
        addArgument("moduleGeneratorsConfigFile", new ListStringCommandArgument(
                new ArrayList<>(ModuleGeneratorsConfig.getModuleGenerators().keySet()),
                "<module.yml>"));
        setPermission("betterstructures.generatemodules");
        setDescription("Generates modular builds in a dedicated world, based on the generator's configuration file.");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        ModuleGeneratorsConfigFields moduleGeneratorsConfigFields = ModuleGeneratorsConfig.getModuleGenerators().get(
                commandData.getStringArgument("moduleGeneratorsConfigFile"));
        if (moduleGeneratorsConfigFields == null) {
            Logger.sendMessage(commandData.getCommandSender(), "File "
                    + commandData.getStringArgument("moduleGeneratorsConfigFile")
                    + " not found! The world won't generate.");
            return;
        }
        WFCGenerator.generateFromConfig(moduleGeneratorsConfigFields, commandData.getPlayerSender());
    }
}
