package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TntCartPlanTest
{
    /** A flat stone floor with air above it. */
    private static GridWorld floor()
    {
        GridWorld world = new GridWorld(-24, 40, -24, 24, 90, 24);
        world.fill(-20, 62, -20, 20, 62, 20, GridWorld.STONE);
        return world;
    }

    /** A fighter in the diamond kit of the mods, so the blast has armour and protection to get through. */
    private static CrystalSearch.Side side(double x, double y, double z)
    {
        CrystalSearch.Side side = new CrystalSearch.Side();
        side.x = x;
        side.y = y;
        side.z = z;
        side.armor = 20.0F;
        side.toughness = 3.0F;
        side.epf = CombatMath.epf(4, 4, true);
        return side;
    }

    @Test
    void theCartGoesWhereTheTargetIsInTheBlastAndNeitherFighterStandsInIt()
    {
        TntCartPlan.Placement best = new TntCartPlan(floor())
                .choose(side(0.5, 63.0, 0.5), side(2.5, 63.0, 0.5), 4, 2, 4, CombatMath.NORMAL);
        assertNotNull(best);
        assertEquals(63, best.y, "the rail sits on the floor the target stands on");
        assertTrue(best.targetDamage > 0.0F, "the target is in the blast");
        assertTrue(best.score > 0.0F, "the blast is worth more than standing next to it costs");
        assertFalse(best.cell().intersects(Box.player(0.5, 63.0, 0.5)), "the cart was placed inside the bot");
        assertFalse(best.cell().intersects(Box.player(2.5, 63.0, 0.5)), "the cart was placed inside the target");
    }

    @Test
    void aCellThatWouldBlowTheBotAwayIsSkippedForOneThatWouldNot()
    {
        TntCartPlan plan = new TntCartPlan(floor());
        CrystalSearch.Side self = side(0.5, 63.0, 0.5);
        TntCartPlan.Placement best = plan.choose(self, side(2.5, 63.0, 0.5), 4, 2, 4, CombatMath.NORMAL);
        assertNotNull(best);
        float chosen = plan.damage(self, best.cx, best.cy, best.cz, TntCartPlan.POWER_MEAN, CombatMath.NORMAL);
        // The cell on the bot's own doorstep, which is the one it would like, since the target is right there.
        float armLength = plan.damage(self, 1.5, 63.5, 0.5, TntCartPlan.POWER_MEAN, CombatMath.NORMAL);
        assertTrue(chosen < armLength, "at the chosen cell the bot takes " + chosen + ", at arm's length "
                + armLength);
    }

    @Test
    void aCartCannotBeLaidWhereThereIsNothingUnderIt()
    {
        // Nothing solid under the searched volume, so there is nowhere a rail could go.
        GridWorld world = new GridWorld(-8, 40, -8, 8, 90, 8);
        assertNull(new TntCartPlan(world).choose(side(0.5, 70.0, 0.5), side(2.5, 70.0, 0.5),
                4, 2, 4, CombatMath.NORMAL));
    }

    @Test
    void aPeacefulServerIsNotWorthLayingACartOn()
    {
        // Every blast does nothing at all, so the search has nothing to score.
        assertNull(new TntCartPlan(floor()).choose(side(0.5, 63.0, 0.5), side(2.5, 63.0, 0.5),
                4, 2, 4, CombatMath.PEACEFUL));
    }

    @Test
    void theBotLightsTheCartOnlyFromWhereTheBlastLeavesItAlive()
    {
        TntCartPlan plan = new TntCartPlan(floor());
        double cx = 2.5;
        double cy = 63.5;
        double cz = 0.5;
        // Standing on the cart is the worst place there is.
        assertFalse(plan.survivable(side(cx, 63.0, cz), cx, cy, cz, CombatMath.NORMAL));
        // A wall between the bot and the cart hides it completely, which is how a cart is used in practice.
        GridWorld world = floor();
        world.fill(1, 63, 0, 1, 65, 0, GridWorld.STONE);
        float behindWall = new TntCartPlan(world).damage(side(-0.5, 63.0, 0.5), cx, cy, cz, TntCartPlan.POWER_MEAN,
                CombatMath.NORMAL);
        float inTheOpen = plan.damage(side(0.5, 63.0, 0.5), cx, cy, cz, TntCartPlan.POWER_MEAN, CombatMath.NORMAL);
        assertTrue(behindWall < 1.0F, "behind the wall the bot still took " + behindWall);
        assertTrue(inTheOpen > behindWall * 10.0F, "in the open it would take " + inTheOpen);
        assertTrue(new TntCartPlan(world).survivable(side(-0.5, 63.0, 0.5), cx, cy, cz, CombatMath.NORMAL));
        // And far enough out on open ground the blast has dropped below what it would take.
        assertTrue(plan.survivable(side(0.5, 63.0, 12.5), cx, cy, cz, CombatMath.NORMAL));
    }

    @Test
    void theDamageIsWhatCombatMathWorksOutForTheExposure()
    {
        TntCartPlan plan = new TntCartPlan(floor());
        CrystalSearch.Side target = side(1.5, 63.0, 0.5);
        double cx = 3.5;
        double cy = 63.5;
        double cz = 0.5;
        // The game shrinks both ends of every sample ray by a hundred millionth before walking the voxels, so
        // the bottom row of a box standing on the ground is reported as hidden by the floor under it. That
        // leaves nine of the forty five rays of a player blocked on open ground, and the blast sees 0.8.
        float exposure = SeenPercent.of(floor(), cx, cy, cz, Box.player(1.5, 63.0, 0.5));
        assertEquals(0.8F, exposure, 1.0E-4F);
        float raw = CombatMath.explosionDamage(Math.sqrt(2.0 * 2.0 + 0.5 * 0.5), TntCartPlan.POWER_MEAN, exposure);
        assertEquals(CombatMath.damageAfterDefences(raw, target.armor, target.toughness, 0, target.epf),
                plan.damage(target, cx, cy, cz, TntCartPlan.POWER_MEAN, CombatMath.NORMAL), 1.0E-4F);
    }
}
