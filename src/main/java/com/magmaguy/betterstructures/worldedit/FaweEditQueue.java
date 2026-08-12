package com.magmaguy.betterstructures.worldedit;

import com.magmaguy.betterstructures.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Global serialized executor for BetterStructures world edits.
 *
 * <p>The Albion fork intentionally routes block-changing work through FAWE and allows
 * only one heavy edit at a time. FAWE can execute edits asynchronously, but allowing
 * several large structures or module batches to compete at once can still saturate
 * chunk loading, CPU, and disk I/O on a busy resource world.</p>
 */
public final class FaweEditQueue {

    private static final Queue<EditJob> JOBS = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private FaweEditQueue() {
    }

    @FunctionalInterface
    public interface EditWork {
        void run() throws Exception;
    }

    /**
     * Submit an edit to the single global FAWE lane. The edit body runs asynchronously;
     * completion always runs on the Bukkit primary thread and receives {@code null} on
     * success or the thrown failure on error.
     */
    public static void submit(String description, EditWork work, Consumer<Throwable> completion) {
        JOBS.add(new EditJob(description, work, completion));
        startNext();
    }

    public static boolean isBusy() {
        return RUNNING.get() || !JOBS.isEmpty();
    }

    public static int queuedEdits() {
        return JOBS.size();
    }

    public static void shutdown() {
        JOBS.clear();
    }

    private static void startNext() {
        if (!RUNNING.compareAndSet(false, true)) return;

        EditJob job = JOBS.poll();
        if (job == null) {
            RUNNING.set(false);
            if (!JOBS.isEmpty()) startNext();
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(MetadataHandler.PLUGIN, () -> {
            Throwable failure = null;
            try {
                job.work().run();
            } catch (Throwable throwable) {
                failure = throwable;
                Logger.warn("FAWE edit failed (" + job.description() + "): " + throwable.getMessage());
            }

            Throwable finalFailure = failure;
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                try {
                    if (job.completion() != null) {
                        job.completion().accept(finalFailure);
                    }
                } catch (Throwable completionFailure) {
                    Logger.warn("FAWE completion callback failed (" + job.description() + "): "
                            + completionFailure.getMessage());
                    completionFailure.printStackTrace();
                } finally {
                    RUNNING.set(false);
                    startNext();
                }
            });
        });
    }

    private record EditJob(String description, EditWork work, Consumer<Throwable> completion) {
    }
}
