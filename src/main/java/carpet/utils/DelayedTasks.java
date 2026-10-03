package carpet.utils;

import carpet.CarpetSettings;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
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
        add(server.getTickCount() + Math.max(0, ticks), task);
    }

    /** Runs the task on the next server tick, whatever thread asks for it. */
    public static void scheduleNextTick(MinecraftServer server, Runnable task)
    {
        schedule(server, 1, task);
    }

    static void add(long dueTick, Runnable task)
    {
        synchronized (TASKS)
        {
            TASKS.add(new Entry(dueTick, task));
        }
    }

    /** Runs every task that came due since the last tick. Called from the server tick. */
    public static void tick(MinecraftServer server)
    {
        runDue(server.getTickCount());
    }

    /** A task may schedule another one, so the due tasks are taken off the list before any of them runs. */
    static void runDue(long currentTick)
    {
        List<Entry> due = new ArrayList<>();
        synchronized (TASKS)
        {
            Iterator<Entry> it = TASKS.iterator();
            while (it.hasNext())
            {
                Entry entry = it.next();
                if (currentTick < entry.dueTick()) continue;
                it.remove();
                due.add(entry);
            }
        }
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
        synchronized (TASKS)
        {
            TASKS.clear();
        }
    }

    private record Entry(long dueTick, Runnable task) {}
}
