package carpet.logic.web;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounds how often one session may write programs. An editor that saves by itself saves a couple of seconds
 * after the last change; a page that sends writes faster than that is held up rather than allowed to keep the
 * disk busy.
 */
class SaveLimiter
{
    static final int WRITES_PER_MINUTE = 60;
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_SESSIONS = 256;

    private static class Window
    {
        long start;
        int writes;
    }

    private final Map<Object, Window> windows = new HashMap<>();

    /**
     * Counts one write.
     *
     * @return 0 when it may go ahead, otherwise how many milliseconds to wait
     */
    synchronized long admit(Object session, long now)
    {
        windows.values().removeIf(window -> now - window.start >= WINDOW_MILLIS);
        Window window = windows.get(session);
        if (window == null)
        {
            if (windows.size() >= MAX_SESSIONS)
            {
                return WINDOW_MILLIS;
            }
            window = new Window();
            window.start = now;
            windows.put(session, window);
        }
        if (window.writes >= WRITES_PER_MINUTE)
        {
            return window.start + WINDOW_MILLIS - now;
        }
        window.writes++;
        return 0;
    }
}
