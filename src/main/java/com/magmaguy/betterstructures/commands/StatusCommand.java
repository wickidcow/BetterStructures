package com.magmaguy.betterstructures.commands;

import com.magmaguy.betterstructures.config.DefaultConfig;
import com.magmaguy.betterstructures.performance.GenerationScheduler;
import com.magmaguy.betterstructures.performance.ServerLoadThrottle;
import com.magmaguy.betterstructures.util.ChunkPregenerator;
import com.magmaguy.betterstructures.worldedit.Schematic;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Locale;

public class StatusCommand extends AdvancedCommand {
    public StatusCommand() {
        super(List.of("status"));
        setPermission("betterstructures.status");
        setUsage("/betterstructures status");
        setDescription("Shows BetterStructures generation and TPS throttle status.");
    }

    @Override
    public void execute(CommandData commandData) {
        double mspt = Bukkit.getAverageTickTime();
        double[] samples = Bukkit.getTPS();
        double tps = samples.length == 0 ? 20.0 : samples[0];

        boolean generationPaused = GenerationScheduler.isPausedForLoad();
        boolean pastePaused = Schematic.isPausedForLoad();
        int recoveryTicks = Math.max(
                GenerationScheduler.healthyRecoveryTicks(),
                Schematic.healthyRecoveryTicks());
        int requiredTicks = GenerationScheduler.requiredRecoveryTicks();

        String generationState;
        if (generationPaused || pastePaused) {
            if (recoveryTicks > 0) {
                generationState = "&eRECOVERING &7(" + recoveryTicks + "/" + requiredTicks + ")";
            } else {
                generationState = "&cPAUSED";
            }
        } else if (GenerationScheduler.queuedJobs() > 0 || Schematic.isBusy()) {
            generationState = "&aRUNNING";
        } else {
            generationState = "&7IDLE";
        }

        ServerLoadThrottle.Band loadBand = ServerLoadThrottle.classify(
                tps,
                mspt,
                DefaultConfig.getPlayerGenerationPauseTPS(),
                DefaultConfig.getPlayerGenerationPauseMSPT());
        int adaptiveCooldown = ServerLoadThrottle.adaptiveGenerationCooldownTicks(
                DefaultConfig.getPlayerGenerationTicksBetweenJobs(),
                loadBand);
        int adaptiveScans = ServerLoadThrottle.deferredChunkScanLimit(loadBand);
        int pastePercent = (int) Math.round(ServerLoadThrottle.pasteBudgetScale(loadBand) * 100.0);

        int activePregenerators = ChunkPregenerator.getActivePregenerators().size();
        long pausedPregenerators = ChunkPregenerator.getActivePregenerators().stream()
                .filter(ChunkPregenerator::isPaused)
                .count();

        Logger.sendMessage(commandData.getCommandSender(), "&8&m----------------------------------------");
        Logger.sendMessage(commandData.getCommandSender(), "&2BetterStructures Status");
        Logger.sendMessage(commandData.getCommandSender(),
                "&7Server: &f" + String.format(Locale.ROOT, "%.2f TPS / %.1f MSPT", tps, mspt));
        Logger.sendMessage(commandData.getCommandSender(),
                "&7Generation: " + generationState
                        + " &8| &7Queued: &f" + GenerationScheduler.queuedJobs()
                        + " &8| &7Paste busy: &f" + Schematic.isBusy());
        Logger.sendMessage(commandData.getCommandSender(),
                "&7TPS guard: &fPause <= "
                        + String.format(Locale.ROOT, "%.1f", DefaultConfig.getPlayerGenerationPauseTPS())
                        + " &8| &fResume >= "
                        + String.format(Locale.ROOT, "%.1f", DefaultConfig.getPlayerGenerationResumeTPS())
                        + " &8| &fStable: " + requiredTicks + " ticks");
        Logger.sendMessage(commandData.getCommandSender(),
                "&7Adaptive: &f" + loadBand.name()
                        + " &8| &7Paste: &f" + pastePercent + "%"
                        + " &8| &7Fit delay: &f" + adaptiveCooldown + " ticks"
                        + " &8| &7Chunk scans: &f" + adaptiveScans + "/drain");
        Logger.sendMessage(commandData.getCommandSender(),
                "&7Pregeneration: &f" + activePregenerators + " active"
                        + " &8| &f" + pausedPregenerators + " paused"
                        + " &8| &7Guard: &f"
                        + String.format(Locale.ROOT, "%.1f/%.1f TPS",
                        Math.max(18.5, DefaultConfig.getPregenerationTPSPauseThreshold()),
                        Math.max(19.5, DefaultConfig.getPregenerationTPSResumeThreshold())));
        Logger.sendMessage(commandData.getCommandSender(), "&8&m----------------------------------------");
    }
}
