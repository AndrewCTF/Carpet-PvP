package carpet.pvp.nav;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import carpet.pvp.nav.BudgetedSearch.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * One flow field per chased target, shared by every bot chasing it.
 *
 * <p>Ten bots after one player each running their own search is ten times the work for the same answer, and the
 * answer is the same because they are all going to the same place. From {@link #SHARED_FROM} bots onwards the
 * chasers follow this field instead: whoever gets there first spends part of the tick's search budget on the
 * field, and the rest read the directions it leaves behind.
 *
 * <p>A field only reaches so far around its target, so it is an answer for the bots near it and no answer for one
 * that is further away; a bot with no directions falls back to searching for itself. The field is dropped as soon
 * as the last bot chasing that target lets go.
 */
public final class ChaseFlowFields
{
    /** Chasers of one target from which a single field is cheaper than a search each. */
    public static final int SHARED_FROM = 3;
    /** How far around its target a field reaches. */
    private static final int RADIUS_XZ = 20;
    private static final int RADIUS_Y = 5;
    /** How far a target may drift before its field is built again, which stops a walking target rebuilding it every tick. */
    private static final double REBUILD_DISTANCE = 3.0D;

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    private ChaseFlowFields()
    {
    }

    private static final class Entry
    {
        private final FlowField field = new FlowField(RADIUS_XZ, RADIUS_Y, REBUILD_DISTANCE);
        private final BudgetedSearch runner = new BudgetedSearch();
        private int chasers;
        private long updatedAtTick = Long.MIN_VALUE;

        /** Spends part of this tick's search budget on the field, unless it has already been worked on this tick. */
        Status update(MinecraftServer server, LevelWalkability view, BlockPos target, int shared)
        {
            long tick = server.overworld().getGameTime();
            if (updatedAtTick == tick)
            {
                return field.ready() ? Status.DONE : Status.CONTINUE;
            }
            updatedAtTick = tick;
            // The field is one search for every chaser, so it draws on the shared cap rather than on one bot's
            // share of it; otherwise a rebuild would take as many ticks as there are bots chasing the target.
            NavSearchBudget.runShared(server, shared, runner,
                    budget -> field.step(view, target.getX(), target.getY(), target.getZ(), budget));
            return field.ready() ? Status.DONE : Status.CONTINUE;
        }
    }

    /** Records that a bot has started chasing a target, joining or opening its field. */
    public static void acquire(UUID target)
    {
        if (target == null) return;
        ENTRIES.computeIfAbsent(target, id -> new Entry()).chasers++;
    }

    /** Records that a bot has stopped chasing a target, and forgets the field once nobody is left. */
    public static void release(UUID target)
    {
        if (target == null) return;
        Entry entry = ENTRIES.get(target);
        if (entry == null) return;
        if (--entry.chasers <= 0)
        {
            ENTRIES.remove(target);
        }
    }

    /** How many bots are chasing this target. */
    public static int chasers(UUID target)
    {
        Entry entry = ENTRIES.get(target);
        return entry == null ? 0 : entry.chasers;
    }

    /** True when enough bots chase this target that following one field beats every bot searching for itself. */
    public static boolean isShared(UUID target)
    {
        return chasers(target) >= SHARED_FROM;
    }

    /**
     * The next cell on the way to the target from where the caller stands, or {@link FlowField#NO_ROUTE} when the
     * target has no field, the field has no directions yet, or the caller stands outside it. Part of the tick's
     * search budget goes into building the field while it is being built.
     */
    public static int direction(MinecraftServer server, LevelWalkability view, UUID target, BlockPos at, int shared)
    {
        Entry entry = ENTRIES.get(target);
        if (entry == null || !isShared(target)) return FlowField.NO_ROUTE;
        BlockPos goal = CHASE_TARGET_POS.get(target);
        if (goal == null) return FlowField.NO_ROUTE;
        entry.update(server, view, goal, shared);
        return entry.field.direction(at.getX(), at.getY(), at.getZ());
    }

    private static final Map<UUID, BlockPos> CHASE_TARGET_POS = new HashMap<>();

    /** Records where a chased target is now, which is the point its field is built around. */
    public static void targetMoved(UUID target, BlockPos pos)
    {
        CHASE_TARGET_POS.put(target, pos.immutable());
    }

    /** Forgets everything about every field, for a server that has just started. */
    public static void reset()
    {
        ENTRIES.clear();
        CHASE_TARGET_POS.clear();
    }

    private static FlowField field(Entry entry)
    {
        return entry.field;
    }
}
