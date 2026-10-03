package carpet.pvp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-scoped faction registry for PvP bots (and players).
 *
 * <p>Bots that share a faction — or whose factions are explicitly allied — will not target
 * each other (see {@link TargetSelector}). Membership is keyed by entity {@link UUID} so both
 * fake players and real players can join factions.</p>
 *
 * <p>State is held in-memory for the server session. (Cross-restart persistence via
 * {@code SavedData} is a planned follow-up.)</p>
 */
public final class FactionManager
{
    private FactionManager() {}

    private static final Set<String> factions = new HashSet<>();
    private static final Map<UUID, String> membership = new HashMap<>();
    private static final Map<String, Set<String>> allies = new HashMap<>();

    public static boolean create(String name)
    {
        return factions.add(name);
    }

    public static boolean delete(String name)
    {
        if (!factions.remove(name)) return false;
        membership.values().removeIf(f -> f.equals(name));
        allies.remove(name);
        allies.values().forEach(s -> s.remove(name));
        return true;
    }

    public static boolean exists(String name)
    {
        return factions.contains(name);
    }

    public static boolean join(String faction, UUID member)
    {
        if (!factions.contains(faction)) return false;
        membership.put(member, faction);
        return true;
    }

    public static void leave(UUID member)
    {
        membership.remove(member);
    }

    /**
     * Makes the registry agree with a member's configured faction, which may be none at all: a member with a
     * faction is put in it, and one without is taken out of whatever it was in.
     */
    public static void sync(UUID member, String faction)
    {
        if (faction == null)
        {
            leave(member);
        }
        else
        {
            create(faction);
            join(faction, member);
        }
    }

    public static String factionOf(UUID member)
    {
        return membership.get(member);
    }

    public static boolean ally(String a, String b)
    {
        if (!factions.contains(a) || !factions.contains(b) || a.equals(b)) return false;
        allies.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        allies.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        return true;
    }

    public static boolean unally(String a, String b)
    {
        boolean changed = false;
        Set<String> sa = allies.get(a);
        if (sa != null) changed |= sa.remove(b);
        Set<String> sb = allies.get(b);
        if (sb != null) changed |= sb.remove(a);
        return changed;
    }

    /**
     * True if two entities should be considered friendly (same faction, or allied factions).
     */
    public static boolean areFriendly(UUID a, UUID b)
    {
        String fa = membership.get(a);
        String fb = membership.get(b);
        if (fa == null || fb == null) return false;
        if (fa.equals(fb)) return true;
        Set<String> alliedToA = allies.get(fa);
        return alliedToA != null && alliedToA.contains(fb);
    }

    public static Set<String> allFactions()
    {
        return new HashSet<>(factions);
    }

    public static String info(String name)
    {
        if (!factions.contains(name)) return null;
        long members = membership.values().stream().filter(f -> f.equals(name)).count();
        Set<String> al = allies.getOrDefault(name, Set.of());
        return "Faction '" + name + "': " + members + " member(s), allies=" + (al.isEmpty() ? "none" : al);
    }

    /** Clears all faction state (used on server shutdown / between sessions). */
    public static void reset()
    {
        factions.clear();
        membership.clear();
        allies.clear();
    }
}
