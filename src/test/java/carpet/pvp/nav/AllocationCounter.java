package carpet.pvp.nav;

import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Assumptions;

/**
 * Counts what the running thread allocates, so the per-tick paths can be held to allocating nothing after warm-up.
 * Skips the test on a JVM that cannot report it.
 */
final class AllocationCounter
{
    private static long sink;

    private AllocationCounter()
    {
    }

    /** Bytes {@code body} allocated once, after it has been run {@code warmup} times. */
    static long measure(Runnable body, int warmup)
    {
        com.sun.management.ThreadMXBean bean = bean();
        for (int i = 0; i < warmup; i++)
        {
            body.run();
        }
        long id = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(id);
        body.run();
        return bean.getThreadAllocatedBytes(id) - before;
    }

    /** Keeps a computed value alive, so a measured loop cannot have its work optimised away. */
    static void keep(long value)
    {
        sink += value;
    }

    private static com.sun.management.ThreadMXBean bean()
    {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean, "no allocation counter on this JVM");
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) bean;
        Assumptions.assumeTrue(sun.isThreadAllocatedMemorySupported(), "no allocation counter on this JVM");
        sun.setThreadAllocatedMemoryEnabled(true);
        return sun;
    }
}