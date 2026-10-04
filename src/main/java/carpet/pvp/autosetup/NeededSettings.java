package carpet.pvp.autosetup;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which settings the running sessions are holding on to, and what each of them was at before the
 * first of them turned it on.
 *
 * <p>Two players in two arenas share {@code fakePlayerNavigation}: the first session finds it off and
 * turns it on, and the second finds it already on. Only the one that changed it has something to put
 * back, so a count would be the wrong shape and the wrong player undoing it would take the setting
 * away from the session still running. Each session therefore holds what it needs by name, and the
 * value goes back when the last of them lets go of it.</p>
 *
 * <p>Nothing here knows what a setting is: the caller reads the value before it turns one on and puts
 * one back when this hands it over, which is what keeps this testable without a server.</p>
 */
final class NeededSettings
{
    /** What each held setting was at before the first session turned it on. */
    private final Map<String, String> before = new HashMap<>();
    /** The settings each player is holding, by name. */
    private final Map<String, Set<String>> holding = new HashMap<>();

    /**
     * Records that a session of this player needs the setting.
     *
     * @param was what the setting is at now, or null when this server has no such setting
     * @return the {@code setting=value} line the session file keeps, or null when there is no such setting
     */
    String hold(String player, String setting, String was)
    {
        if (was == null) return null;
        String first = before.putIfAbsent(setting, was);
        holding.computeIfAbsent(player, name -> new HashSet<>()).add(setting);
        return setting + "=" + (first == null ? was : first);
    }

    /** Takes up again the claims a session file records, as a file read off the disk does. */
    void holdAll(String player, List<String> rules)
    {
        for (String rule : rules)
        {
            int split = rule.indexOf('=');
            if (split > 0) hold(player, rule.substring(0, split), rule.substring(split + 1));
        }
    }

    /**
     * Records that a session of this player is over with the setting.
     *
     * @return the value to put back, or null while another session still needs the setting
     */
    String release(String player, String setting)
    {
        Set<String> mine = holding.get(player);
        if (mine == null || !mine.remove(setting)) return null;
        if (mine.isEmpty()) holding.remove(player);
        return stillHeld(setting) ? null : before.remove(setting);
    }

    /**
     * Drops what a player was holding without putting anything back, which is what a server that lost
     * its sessions does: the settings stay as they are and the files still say what to undo.
     */
    void forget(String player)
    {
        Set<String> mine = holding.remove(player);
        if (mine == null) return;
        for (String setting : mine)
        {
            if (!stillHeld(setting)) before.remove(setting);
        }
    }

    private boolean stillHeld(String setting)
    {
        for (Set<String> others : holding.values())
        {
            if (others.contains(setting)) return true;
        }
        return false;
    }
}
