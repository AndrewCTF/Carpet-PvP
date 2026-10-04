package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DuelSimTest
{
    private static final int WALK = DuelSim.action(1, 0, false, false, false);
    private static final int SPRINT = DuelSim.action(1, 0, false, true, false);
    private static final int SPRINT_JUMP = DuelSim.action(1, 0, true, true, false);
    private static final int ATTACK = DuelSim.action(0, 0, false, false, true);

    /** Speed in blocks per second over the final tick after running the action for the given ticks, opponent far away. */
    private static double steadySpeed(int action, int ticks)
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(10000.0);
        double before = 0;
        for (int i = 0; i < ticks; i++)
        {
            before = sim.a.z;
            sim.step(action, DuelSim.NOOP);
        }
        return (sim.a.z - before) * 20.0;
    }

    private static double distanceCovered(int action, int ticks)
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(10000.0);
        for (int i = 0; i < ticks; i++)
        {
            sim.step(action, DuelSim.NOOP);
        }
        return sim.a.z;
    }

    @Test
    void walkingSpeedMatchesVanilla()
    {
        assertEquals(4.317, steadySpeed(WALK, 100), 0.005);
    }

    @Test
    void sprintingSpeedMatchesVanilla()
    {
        assertEquals(5.612, steadySpeed(SPRINT, 100), 0.005);
    }

    @Test
    void jumpApexMatchesVanilla()
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(10000.0);
        double apex = 0.0;
        sim.step(DuelSim.action(0, 0, true, false, false), DuelSim.NOOP);
        for (int i = 0; i < 40; i++)
        {
            apex = Math.max(apex, sim.a.y);
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
        }
        assertEquals(1.2522, apex, 0.0005);
        assertTrue(sim.a.onGround);
    }

    @Test
    void sprintJumpIsFasterThanSprinting()
    {
        double ground = distanceCovered(SPRINT, 100);
        double hop = distanceCovered(SPRINT_JUMP, 100);
        assertTrue(hop > ground * 1.05, "hop " + hop + " vs ground " + ground);
    }

    private static DuelSim armed(double distance, float armor)
    {
        DuelSim sim = new DuelSim();
        sim.a.setLoadout(8.0, 1.6, 0.0f, 0.0f, 0.0f, 0.0f, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0f, armor, armor > 0 ? 4.0f : 0.0f, armor > 0 ? 2.0f : 0.0f, 0.0);
        sim.placeFacing(distance);
        return sim;
    }

    @Test
    void hitLandsOnlyInsideReach()
    {
        // eye is inside the target's vertical extent, so reach is horizontal distance minus half the hitbox width
        DuelSim in = armed(3.29, 0.0f);
        in.step(ATTACK, DuelSim.NOOP);
        assertEquals(12.0f, in.b.health, 1e-4f);
        DuelSim out = armed(3.31, 0.0f);
        out.step(ATTACK, DuelSim.NOOP);
        assertEquals(20.0f, out.b.health);
        assertEquals(0, out.a.ticksSinceSwing - 1, "a miss still swings");
    }

    @Test
    void damageFollowsCombatMath()
    {
        for (int ticks : new int[] {0, 3, 8, 11, 40})
        {
            DuelSim sim = armed(2.0, 12.0f);
            sim.a.ticksSinceSwing = ticks;
            sim.step(ATTACK, DuelSim.NOOP);
            float scale = CombatMath.chargeScale(ticks, 1.6);
            float raw = CombatMath.attackDamage(8.0f, 0.0f, 0.0f, scale, false);
            float expected = CombatMath.damageAfterDefences(raw, 12.0f, 4.0f, 0, 2.0f);
            assertEquals(20.0f - expected, sim.b.health, 1e-4f, "ticks " + ticks);
        }
    }

    @Test
    void criticalHitOnDescent()
    {
        DuelSim sim = armed(2.0, 0.0f);
        sim.a.y = 1.0;
        sim.a.onGround = false;
        sim.a.vy = -0.2;
        sim.step(ATTACK, DuelSim.NOOP);
        assertEquals(20.0f - 8.0f * 1.5f, sim.b.health, 1e-4f);
    }

    @Test
    void invulnerabilityWindowFollowsCombatMath()
    {
        int[] times = {15, 11, 10, 3};
        float[] lastHurt = {5.0f, 5.0f, 5.0f, 5.0f};
        for (int i = 0; i < times.length; i++)
        {
            DuelSim sim = armed(2.0, 0.0f);
            sim.b.invulTime = times[i];
            sim.b.lastHurt = lastHurt[i];
            sim.step(ATTACK, DuelSim.NOOP);
            float expected = CombatMath.invulnerabilityDamage(8.0f, 5.0f, times[i]);
            assertEquals(20.0f - expected, sim.b.health, 1e-4f, "invul " + times[i]);
            if (times[i] > CombatMath.INVULNERABLE_WINDOW_THRESHOLD)
            {
                assertEquals(0.0, sim.b.vz, 1e-9, "no knockback inside the window");
            }
        }
        DuelSim rejected = armed(2.0, 0.0f);
        rejected.b.invulTime = 15;
        rejected.b.lastHurt = 9.0f;
        rejected.step(ATTACK, DuelSim.NOOP);
        assertEquals(20.0f, rejected.b.health);
    }

    @Test
    void hitSetsInvulnerabilityWindow()
    {
        DuelSim sim = armed(2.0, 0.0f);
        sim.step(ATTACK, DuelSim.NOOP);
        assertEquals(CombatMath.INVULNERABLE_TICKS_AFTER_HIT - 1, sim.b.invulTime);
        assertEquals(8.0f, sim.b.lastHurt, 1e-5f);
    }

    /** Distance B ends up knocked after a hit, with A sprinting or not and B otherwise at rest. */
    private static double knockedDistance(boolean sprinting)
    {
        DuelSim sim = armed(2.0, 0.0f);
        sim.a.sprinting = sprinting;
        sim.step(ATTACK, DuelSim.NOOP);
        for (int i = 0; i < 9; i++)
        {
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
        }
        return sim.b.z - 2.0;
    }

    @Test
    void sprintHitKnocksFurtherAndSecondHitDoesNot()
    {
        double standing = knockedDistance(false);
        double sprint = knockedDistance(true);
        assertTrue(sprint > standing * 1.3, "sprint " + sprint + " standing " + standing);

        DuelSim sim = armed(2.0, 0.0f);
        sim.a.sprinting = true;
        sim.step(ATTACK, DuelSim.NOOP);
        assertFalse(sim.a.sprinting, "sprint lost on the hit");
        int holdSprint = DuelSim.action(0, 0, false, true, false);
        for (int i = 0; i < 20; i++)
        {
            sim.step(holdSprint, DuelSim.NOOP);
        }
        assertFalse(sim.a.sprinting, "holding sprint without releasing does not re-sprint");
        sim.b.z = 2.0;
        sim.b.vx = 0.0;
        sim.b.vz = 0.0;
        sim.b.vy = 0.0;
        sim.b.y = 0.0;
        sim.b.onGround = true;
        sim.a.z = 0.0;
        sim.a.vx = 0.0;
        sim.a.vz = 0.0;
        sim.b.invulTime = 0;
        sim.b.lastHurt = 0.0f;
        sim.step(ATTACK, DuelSim.NOOP);
        for (int i = 0; i < 9; i++)
        {
            sim.step(DuelSim.NOOP, DuelSim.NOOP);
        }
        assertEquals(standing, sim.b.z - 2.0, 1e-9);

        sim.step(DuelSim.NOOP, DuelSim.NOOP);
        sim.step(DuelSim.action(1, 0, false, false, false), DuelSim.NOOP);
        sim.step(SPRINT, DuelSim.NOOP);
        assertTrue(sim.a.sprinting, "releasing sprint re-enables it");
    }

    @Test
    void copyIsIndependentAndExact()
    {
        DuelSim sim = armed(2.0, 5.0f);
        sim.step(SPRINT_JUMP, ATTACK);
        DuelSim copy = new DuelSim();
        copy.copyFrom(sim);
        sim.step(ATTACK, SPRINT);
        DuelSim twin = new DuelSim();
        twin.copyFrom(copy);
        copy.step(ATTACK, SPRINT);
        twin.step(ATTACK, SPRINT);
        assertEquals(copy.a.x, twin.a.x);
        assertEquals(copy.b.health, twin.b.health);
        assertEquals(sim.b.z, copy.b.z);
    }
}
