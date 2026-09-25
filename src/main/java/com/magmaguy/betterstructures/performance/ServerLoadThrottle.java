package com.magmaguy.betterstructures.performance;

import com.magmaguy.betterstructures.config.DefaultConfig;
import org.bukkit.Bukkit;

/**
 * Converts current server load into progressively safer BetterStructures work limits.
 *
 * <p>The hard pause remains controlled by the configured player-generation TPS/MSPT thresholds.
 * The adaptive bands begin backing off before those thresholds are reached so BetterStructures is
 * less likely to contribute to a sharp TPS drop in the first place.</p>
 */
public final class ServerLoadThrottle {
    private static final long MIN_ADAPTIVE_PASTE_NANOS = 500_000L;

    private ServerLoadThrottle() {
    }

    public enum Band {
        HEALTHY,
        WARM,
        ELEVATED,
        HIGH,
        CRITICAL
    }

    public static LoadSnapshot snapshot() {
        double[] samples = Bukkit.getTPS();
        double tps = samples.length == 0 ? 20.0 : samples[0];
        double mspt = Bukkit.getAverageTickTime();
        return new LoadSnapshot(tps, mspt, classify(
                tps,
                mspt,
                DefaultConfig.getPlayerGenerationPauseTPS(),
                DefaultConfig.getPlayerGenerationPauseMSPT()));
    }

    static Band classify(double tps, double mspt, double pauseTps, double pauseMspt) {
        if (tps <= pauseTps || mspt >= pauseMspt) return Band.CRITICAL;

        double highTps = Math.min(20.0, pauseTps + 0.5);
        double elevatedTps = Math.min(20.0, pauseTps + 0.9);
        double warmTps = Math.min(20.0, pauseTps + 1.2);

        if (tps < highTps || mspt >= Math.max(0.0, pauseMspt - 2.0)) return Band.HIGH;
        if (tps < elevatedTps || mspt >= Math.max(0.0, pauseMspt - 4.0)) return Band.ELEVATED;
        if (tps < warmTps || mspt >= Math.max(0.0, pauseMspt - 7.0)) return Band.WARM;
        return Band.HEALTHY;
    }

    public static long adaptivePasteBudgetNanos(long configuredBudgetNanos, Band band) {
        if (band == Band.CRITICAL) return 0L;
        double scale = switch (band) {
            case HEALTHY -> 1.0;
            case WARM -> 0.75;
            case ELEVATED -> 0.50;
            case HIGH -> 0.25;
            case CRITICAL -> 0.0;
        };
        return Math.max(MIN_ADAPTIVE_PASTE_NANOS, (long) (configuredBudgetNanos * scale));
    }

    public static int adaptiveGenerationCooldownTicks(int configuredBaseTicks, Band band) {
        int base = Math.max(0, configuredBaseTicks);
        return switch (band) {
            case HEALTHY -> base;
            case WARM -> Math.max(base, 4);
            case ELEVATED -> Math.max(base, 8);
            case HIGH -> Math.max(base, 15);
            case CRITICAL -> Math.max(base, 20);
        };
    }

    public static int deferredChunkScanLimit(Band band) {
        return switch (band) {
            case HEALTHY -> 8;
            case WARM -> 4;
            case ELEVATED -> 2;
            case HIGH -> 1;
            case CRITICAL -> 0;
        };
    }

    public static long deferredChunkDrainDelayTicks(Band band) {
        return switch (band) {
            case HEALTHY -> 1L;
            case WARM -> 2L;
            case ELEVATED -> 4L;
            case HIGH -> 8L;
            case CRITICAL -> 20L;
        };
    }

    public record LoadSnapshot(double tps, double mspt, Band band) {
    }
}
