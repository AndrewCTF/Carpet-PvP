package carpet.logic.web;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Slows down guessing at the admin sign-in. Attempts are counted per remote address and per name being tried;
 * once a key has used up its free attempts it has to wait, twice as long after each further one. The name is
 * the real brake, because a guess at one account is counted wherever it comes from. The address only keeps one
 * machine from working through many names, and is given more room: behind a reverse proxy every visitor
 * arrives from the proxy's address.
 */
class LoginThrottle
{
    static final int FREE_PER_ACCOUNT = 5;
    static final int FREE_PER_ADDRESS = 20;
    static final long FIRST_WAIT_MILLIS = 5_000L;
    static final long LONGEST_WAIT_MILLIS = 15 * 60_000L;
    /** A key that has not failed for this long starts again with a clean record. */
    static final long FORGET_MILLIS = 60 * 60_000L;
    static final int MAX_KEYS = 4096;

    private static class Attempts
    {
        int count;
        long blockedUntil;
        long last;
    }

    private final Map<String, Attempts> table = new HashMap<>();

    /**
     * Counts one attempt against the address and the name, unless either still has to wait.
     *
     * @return 0 when the attempt may go ahead, otherwise how many milliseconds to wait
     */
    synchronized long admit(String address, String name, long now)
    {
        String addressKey = addressKey(address);
        String nameKey = nameKey(name);
        long wait = Math.max(remaining(addressKey, now), remaining(nameKey, now));
        if (wait > 0)
        {
            return wait;
        }
        forget(now);
        int unknown = (table.containsKey(addressKey) ? 0 : 1) + (table.containsKey(nameKey) ? 0 : 1);
        if (table.size() + unknown > MAX_KEYS)
        {
            // Nothing is thrown out to make room: an attacker could then wash an account's record away by
            // trying a few thousand other names. New keys wait until old ones have been forgotten.
            return FIRST_WAIT_MILLIS;
        }
        count(addressKey, FREE_PER_ADDRESS, now);
        count(nameKey, FREE_PER_ACCOUNT, now);
        return 0;
    }

    /**
     * An attempt that was admitted turned out right: its address and name start again.
     */
    synchronized void succeeded(String address, String name)
    {
        table.remove(addressKey(address));
        table.remove(nameKey(name));
    }

    /**
     * @return how many milliseconds the address has to wait, 0 when it does not
     */
    synchronized long blocked(String address, long now)
    {
        return remaining(addressKey(address), now);
    }

    /**
     * Counts a failure against the address alone, for a request that named no account.
     */
    synchronized void failed(String address, long now)
    {
        String key = addressKey(address);
        forget(now);
        if (table.containsKey(key) || table.size() < MAX_KEYS)
        {
            count(key, FREE_PER_ADDRESS, now);
        }
    }

    synchronized int size()
    {
        return table.size();
    }

    private long remaining(String key, long now)
    {
        Attempts attempts = table.get(key);
        return attempts == null ? 0 : Math.max(0, attempts.blockedUntil - now);
    }

    private void count(String key, int free, long now)
    {
        Attempts attempts = table.computeIfAbsent(key, k -> new Attempts());
        if (now - attempts.last > FORGET_MILLIS)
        {
            attempts.count = 0;
        }
        attempts.count++;
        attempts.last = now;
        if (attempts.count >= free)
        {
            int doublings = Math.min(attempts.count - free, 30);
            attempts.blockedUntil = now + Math.min(LONGEST_WAIT_MILLIS, FIRST_WAIT_MILLIS << doublings);
        }
    }

    private void forget(long now)
    {
        table.values().removeIf(attempts -> now - attempts.last > FORGET_MILLIS && now >= attempts.blockedUntil);
    }

    private static String nameKey(String name)
    {
        return "n:" + name.toLowerCase(Locale.ROOT);
    }

    private static String addressKey(String address)
    {
        return "a:" + address;
    }

    /**
     * What an address is counted under: itself, or for IPv6 its /64, since one machine there holds a whole one.
     */
    static String key(InetAddress address)
    {
        byte[] bytes = address.getAddress();
        if (bytes.length != 16)
        {
            return address.getHostAddress();
        }
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 8; i++)
        {
            prefix.append(String.format("%02x", bytes[i]));
        }
        return prefix + "/64";
    }
}
