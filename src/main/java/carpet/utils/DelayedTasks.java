package carpet.utils;

import carpet.CarpetSettings;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Delayed server-thread tasks. Delays are counted in game ticks and the due tasks are run from
 * {@link carpet.CarpetServer#tick(MinecraftServer)}, so nothing touches the world off the server thread.
 */
public final class DelayedTasks
{
    private static final List<Entry> TASKS = new ArrayList<>();

    private DelayedTasks() {}

    /** Runs the task on the server thread once {@code ticks} server ticks have passed. */
    public static void schedule(MinecraftServer server, int ticks, Runnable task)
    {
        TASKS.add(new Entry(server.getTickCount() + Math.max(0, ticks), task));
    }

    /** Runs the task on the next server tick, whatever thread asks for it. */
    public static void scheduleNextTick(MinecraftServer server, Runnable task)
    {
        schedule(server, 1, task);
    }

    /** Runs every task that came due since the last tick. Called from the server tick. */
    public static void tick(MinecraftServer server)
    {
        if (TASKS.isEmpty()) return;

        long currentTick = server.getTickCount();
        // A task may schedule the next one, which an iterator over the live list would not survive, so
        // the due tasks are taken out first and run afterwards.
        List<Entry> due = new ArrayList<>();
        TASKS.removeIf(entry -> {
            if (currentTick < entry.dueTick()) return false;
            due.add(entry);
            return true;
        });
        for (Entry entry : due)
        {
            try
            {
                entry.task().run();
            }
            catch (Exception e)
            {
                CarpetSettings.LOG.error("Delayed task failed", e);
            }
        }
    }

    public static void clear()
    {
        TASKS.clear();
    }

    private record Entry(long dueTick, Runnable task) {}
}
