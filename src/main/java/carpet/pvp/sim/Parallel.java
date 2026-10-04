package carpet.pvp.sim;

/**
 * Splits an index range over plain threads. Every index is independent and writes only its own slot, so the
 * result does not depend on how the work happened to be spread.
 */
final class Parallel
{
    interface Body
    {
        void run(int index);
    }

    private Parallel()
    {
    }

    static int threads()
    {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    static void forEach(int count, Body body)
    {
        int workers = Math.min(threads(), count);
        Thread[] pool = new Thread[workers];
        for (int w = 0; w < workers; w++)
        {
            int from = (int) ((long) count * w / workers);
            int to = (int) ((long) count * (w + 1) / workers);
            pool[w] = new Thread(() ->
            {
                for (int i = from; i < to; i++)
                {
                    body.run(i);
                }
            }, "sim-" + w);
        }
        for (Thread t : pool)
        {
            t.start();
        }
        for (Thread t : pool)
        {
            try
            {
                t.join();
            }
            catch (InterruptedException e)
            {
                throw new IllegalStateException(e);
            }
        }
    }
}