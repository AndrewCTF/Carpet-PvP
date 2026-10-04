package carpet.pvp.style;

import carpet.pvp.sim.DuelSim;

import java.util.function.IntPredicate;

/**
 * Which techniques a bot's difficulty allows, as a filter on the actions the planner may choose.
 *
 * <p>A technique a preset forbids is removed from the planner's action set, so the planner never
 * plans a jump crit, a strafe or a sprint tap it is not allowed to use; nothing is filtered out after
 * the plan has been made. The sprint lock is part of the filter: while sprint must stay off, sprinting
 * is not an action the planner may pick.</p>
 */
public final class Techniques implements IntPredicate
{
    /** Ticks without sprint after a sprint hit for a bot that may not W-tap. */
    public static final int NO_TAP_LOCK_TICKS = 6;

    private final boolean jumpCrits;
    private final boolean strafing;
    private final boolean wtap;
    private final boolean shield;
    private int sprintLock;

    public Techniques(boolean jumpCrits, boolean strafing, boolean wtap, boolean shield)
    {
        this.jumpCrits = jumpCrits;
        this.strafing = strafing;
        this.wtap = wtap;
        this.shield = shield;
    }

    public boolean jumpCrits()
    {
        return jumpCrits;
    }

    public boolean strafing()
    {
        return strafing;
    }

    public boolean wtap()
    {
        return wtap;
    }

    public boolean shield()
    {
        return shield;
    }

    /** Ticks left of the sprint lock, zero while sprint is available. */
    public int sprintLock()
    {
        return sprintLock;
    }

    /** Locks sprint for a while, as after a sprint hit for a bot that may not tap sprint back on. */
    public void lockSprint(int ticks)
    {
        sprintLock = Math.max(sprintLock, ticks);
    }

    /** Counts a tick of the lock down. Call once per tick, before the planner runs. */
    public void tick()
    {
        if (sprintLock > 0)
        {
            sprintLock--;
        }
    }

    /** True if the planner may pick this action. */
    @Override
    public boolean test(int action)
    {
        if (sprintLock > 0 && DuelSim.sprint(action))
        {
            return false;
        }
        if (!wtap && DuelSim.sprint(action))
        {
            // A bot that may not tap sprint cannot get it back after a sprint hit, so it keeps sprint
            // off and never lands one.
            return false;
        }
        if (!jumpCrits && DuelSim.jump(action))
        {
            return false;
        }
        return strafing || DuelSim.strafe(action) == 0;
    }
}