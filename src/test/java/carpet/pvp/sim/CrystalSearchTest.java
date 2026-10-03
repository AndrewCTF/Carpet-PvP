package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CrystalSearchTest
{
    /** Two blocks of obsidian with an unbroken top layer. */
    private static GridWorld floor()
    {
        GridWorld world = new GridWorld(-16, 40, -16, 16, 90, 16);
        world.fill(-12, 62, -12, 12, 63, 12, GridWorld.OBSIDIAN);
        return world;
    }

    /** An obsidian floor two blocks thick with a square hole punched through the top layer. */
    private static GridWorld arena(int holeX, int holeZ, int holeRadius)
    {
        GridWorld world = floor();
        world.fill(holeX - holeRadius, 63, holeZ - holeRadius, holeX + holeRadius, 63, holeZ + holeRadius,
                GridWorld.AIR);
        return world;
    }

    private static CrystalSearch.Side side(double x, double y, double z, float armor, float toughness,
                                           float epf, float health, boolean totem)
    {
        CrystalSearch.Side side = new CrystalSearch.Side();
        side.x = x;
        side.y = y;
        side.z = z;
        side.armor = armor;
        side.toughness = toughness;
        side.epf = epf;
        side.health = health;
        side.totem = totem;
        return side;
    }

    /** What CombatMath works out for a fighter hit by the explosion of a candidate at the given exposure. */
    private static float expected(CrystalSearch.Side side, CrystalSearch.Candidate candidate, float exposure,
                                   int difficulty)
    {
        double dx = side.x - candidate.cx;
        double dy = side.y - candidate.cy;
        double dz = side.z - candidate.cz;
        float raw = CombatMath.explosionDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), candidate.power, exposure);
        return CombatMath.damageAfterDefences(CombatMath.playerDifficultyScale(raw, difficulty), side.armor,
                side.toughness, 0, side.epf);
    }

    /**
     * A rim crystal of the one block hole sits on the floor block at (1, 63, 0), so its explosion centre is
     * (1.5, 64, 0.5). The target's feet are at (0.5, 63, 0.5), which is sqrt(1 + 1) = 1.4142 away, and two of
     * the five sample rows of the target clear the rim while three do not, so 18 of the 45 rays get through
     * and the exposure is 0.4.
     */
    private static final double RIM_EXPOSURE = 0.4;
    private static final double RIM_DISTANCE = Math.sqrt(2.0);

    @Test
    void rimDamageMatchesCombatMath()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(7.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch.Candidate rim = find(new CrystalSearch().search(world, bot, target, 6.0, false, false, 6, 2,
                6, CombatMath.NORMAL), 1, 63, 0);
        assertNotNull(rim, "the floor block next to the hole has to be offered");
        assertEquals(RIM_EXPOSURE, rim.targetExposure, 1e-6f);
        assertEquals(1.5, rim.cx, 1e-12);
        assertEquals(64.0, rim.cy, 1e-12);
        assertEquals(0.5, rim.cz, 1e-12);
        assertEquals(6.0f, rim.power);
        assertEquals(CombatMath.explosionDamage(RIM_DISTANCE, 6.0f, (float) RIM_EXPOSURE), rim.targetDamage,
                1e-4f);
        assertEquals(expected(target, rim, rim.targetExposure, CombatMath.NORMAL), rim.targetDamage, 1e-4f);

        // The bot is 6 blocks away with nothing in between, so it is fully exposed. At ratio 0.5 the raw
        // damage is (1 + (1 - 0.5) * 0.5 / 2 * 84) = 32.5 and netherite takes it down by 13.5 / 25.
        assertEquals(1.0f, rim.selfExposure, 1e-6f);
        assertEquals(6.0, Math.abs(bot.x - rim.cx), 1e-12);
        assertEquals(32.5f, CombatMath.explosionDamage(6.0, 6.0f, 1.0f), 1e-3f);
        assertEquals(14.95f, rim.selfDamage, 1e-3f);
        assertEquals(expected(bot, rim, rim.selfExposure, CombatMath.NORMAL), rim.selfDamage, 1e-3f);
    }

    /**
     * The hole is one cell wide, so the crystal has to go on one of the eight floor blocks around it. With
     * crystals only, the three best placements are all on that ring and all finish the target, so the search
     * has no reason to spend obsidian. Handing it obsidian adds the bridge over the hole, and that still
     * comes fourth because the rim already kills and the bridge costs a block.
     */
    @Test
    void oneBlockHoleIsHitFromTheRim()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(7.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch search = new CrystalSearch();
        CrystalSearch.Result plain = search.search(world, bot, target, 6.0, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        List<CrystalSearch.Candidate> rim = plain.candidates();
        assertEquals(CrystalSearch.RESULTS, rim.size());
        CrystalSearch.Candidate best = plain.best();
        assertFalse(best.anchor, "the bot carries no respawn anchor here");
        assertFalse(best.bridge, "the bot carries no obsidian here");
        assertEquals(63, best.y, "the crystal stands on the floor layer");
        assertEquals(1, Math.max(Math.abs(best.x), Math.abs(best.z)), "and on the ring around the hole");
        int onTheRim = 0;
        for (CrystalSearch.Candidate candidate : rim)
        {
            assertFalse(candidate.anchor);
            assertEquals(63, candidate.y, "every crystal here stands on the floor layer");
            if (Math.max(Math.abs(candidate.x), Math.abs(candidate.z)) == 1)
            {
                onTheRim++;
                assertTrue(candidate.targetDamage >= 20.0f,
                        "a rim crystal has to be able to finish the target, got " + candidate.targetDamage);
            }
        }
        assertEquals(3, onTheRim, "three of the four best spots are the floor blocks around the hole");
        CrystalSearch.Result bridged = search.search(world, bot, target, 6.0, true, false, 6, 2, 6,
                CombatMath.NORMAL);
        assertNotNull(find(bridged, 1, 63, 0), "the rim survives when the bot also has obsidian");
        assertFalse(bridged.best().bridge, "the rim still wins once the bridge is on the table");
        assertEquals(plain.best().score, bridged.best().score, 1e-4f);
        boolean overTheHole = false;
        for (CrystalSearch.Candidate candidate : bridged.candidates())
        {
            overTheHole |= candidate.bridge;
        }
        assertTrue(overTheHole, "a bot holding obsidian has to be offered the bridge over the hole");
    }

    /**
     * A crystal next to the bot does 85 raw and full netherite still leaves 68 of it, far over 20 health, so
     * the placements near the bot are lethal. The far placements within reach are not, and none of the four
     * the search returns is one of the near ones.
     */
    @Test
    void lethalPlacementIsNeverChosenWhileASafeOneExists()
    {
        GridWorld world = floor();
        CrystalSearch.Side bot = side(2.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        CrystalSearch.Side target = side(4.5, 64.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch search = new CrystalSearch();
        CrystalSearch.Result safe = search.search(world, bot, target, 6.0, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        assertEquals(CrystalSearch.RESULTS, safe.candidates().size());
        for (CrystalSearch.Candidate candidate : safe.candidates())
        {
            assertFalse(candidate.selfLethal, "picked " + candidate.x + " " + candidate.y + " " + candidate.z);
        }
        // 20 health of damage, the 20 point kill bonus, and nothing spent on blocks or a moving target.
        assertEquals(40.0f - 0.5f * safe.best().selfDamage, safe.best().score, 1e-3f);
        assertEquals(71.4f, CombatMath.damageAfterDefences(85.0f, 20.0f, 12.0f, 0, 0.0f), 1e-3f);
        assertTrue(CombatMath.damageAfterDefences(85.0f, 20.0f, 12.0f, 0, 0.0f) > bot.health,
                "a point blank crystal really is lethal");

        // Cutting the reach to 2 leaves nothing but the lethal placements. They are reported rather than
        // hidden, so a caller can see the bot has no safe crystal at all.
        CrystalSearch.Result cornered = search.search(world, bot, target, 2.0, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        assertFalse(cornered.candidates().isEmpty());
        assertTrue(cornered.best().selfLethal, "inside two blocks every crystal finishes the bot too");
        assertTrue(cornered.best().score < -CrystalSearch.DEATH_PENALTY / 2.0f);
    }

    /**
     * An unarmoured bot takes 39.79 from a crystal 5 blocks away, more than its 20 health, so nothing within
     * reach is safe. A totem turns that same hit from a death into a totem pop, which is punished but not
     * refused, so the search prefers it over dying but only when nothing else is on offer.
     */
    @Test
    void nakedBotIsToldEveryPlacementIsLethal()
    {
        GridWorld world = floor();
        CrystalSearch search = new CrystalSearch();
        CrystalSearch.Side naked = side(2.5, 64.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch.Side carried = side(2.5, 64.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(4.5, 64.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch.Result died = search.search(world, naked, target, 4.5, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        CrystalSearch.Result popped = search.search(world, carried, target, 4.5, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        assertTrue(died.best().selfLethal);
        assertTrue(popped.best().selfLethal);
        assertTrue(popped.best().score > died.best().score, "a totem is a smaller loss than dying");
        assertTrue(popped.best().score - died.best().score > CrystalSearch.DEATH_PENALTY
                - CrystalSearch.TOTEM_PENALTY - 1.0f);
        assertTrue(died.best().score <= -CrystalSearch.DEATH_PENALTY + 40.0f);
        assertTrue(popped.best().score <= -CrystalSearch.TOTEM_PENALTY + 40.0f);
    }

    /**
     * The bot only offers an anchor when it has one to charge. Carrying one over the one block hole is worth
     * it: the anchor drops into the hole for power 5 at 2.5 blocks with the same 0.8 exposure as the rim
     * crystal, which finishes the target for three points less than the anchor's cost, so the search takes it
     * instead of the crystal.
     */
    @Test
    void anchorsAreOnlyOfferedWhenCarried()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(7.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch search = new CrystalSearch();
        CrystalSearch.Result bare = search.search(world, bot, target, 6.0, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        for (CrystalSearch.Candidate candidate : bare.candidates())
        {
            assertFalse(candidate.anchor, "the bot is not carrying a respawn anchor");
        }
        assertFalse(bare.best().anchor);
        CrystalSearch.Result loaded = search.search(world, bot, target, 6.0, false, true, 6, 2, 6,
                CombatMath.NORMAL);
        assertTrue(loaded.best().anchor, "a bot holding a respawn anchor has to be offered one");
        assertEquals(5.0f, loaded.best().power);
        assertTrue(loaded.best().score > bare.best().score, "more options cannot make the best spot worse");
    }

    /**
     * The target walks 0.25 along x and 0.1 along z, so two ticks on it puts its box at (3.0, 64, 2.7) in the
     * frames the search works in. The spot the target is walking into takes more off the lead box than off
     * the box it is standing in, and that difference is worth LEAD_WEIGHT of it. The spot behind it is the
     * mirror image, so the two together show the term is not just noise.
     */
    @Test
    void movingTargetIsPredictedTwoTicksAhead()
    {
        assertEquals(2, CrystalSearch.LEAD_TICKS);
        GridWorld world = arena(0, 0, 1);
        CrystalSearch.Side bot = side(6.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        target.vx = 0.25;
        target.vz = 0.1;
        Box lead = target.lead();
        assertEquals(0.5, lead.minX - target.box().minX, 1e-12);
        assertEquals(0.2, lead.minZ - target.box().minZ, 1e-12);
        CrystalSearch.Result result = new CrystalSearch().search(world, bot, target, 6.0, false, false, 6, 2, 6,
                CombatMath.NORMAL);
        CrystalSearch.Candidate best = result.best();
        assertTrue(best.leadDamage > best.targetDamage,
                "the best crystal has to hit where the target is going, got " + best.leadDamage + " against "
                        + best.targetDamage);
        boolean behind = false;
        for (CrystalSearch.Candidate candidate : result.candidates())
        {
            behind |= candidate.leadDamage < candidate.targetDamage;
        }
        assertTrue(behind, "a crystal behind the target has to do less to the lead box");
        float reward = CrystalSearch.LEAD_WEIGHT * Math.max(0.0f, best.leadDamage - best.targetDamage);
        assertEquals(target.health + CrystalSearch.KILL_BONUS + reward - 0.5f * best.selfDamage, best.score,
                1e-3f);
    }

    /**
     * A 13 by 5 by 13 volume is 845 blocks and here every one of them can carry a crystal, so the upper bound
     * has to do the work: it visits the most promising blocks first and drops the rest before a ray is cast.
     */
    @Test
    void searchOverThirteenByThirteenByFiveIsFast()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch search = new CrystalSearch();
        CrystalSearch.Side bot = side(0.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        CrystalSearch.Side target = side(2.5, 64.0, 2.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        target.vx = 0.25;
        target.vz = 0.1;
        for (int i = 0; i < 20000; i++)
        {
            search.search(world, bot, target, 4.5, true, true, 6, 2, 6, CombatMath.NORMAL);
        }
        int rounds = 5000;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++)
        {
            search.search(world, bot, target, 4.5, true, true, 6, 2, 6, CombatMath.NORMAL);
        }
        double millis = (System.nanoTime() - start) / 1e6 / rounds;
        CrystalSearch.Result result = search.search(world, bot, target, 4.5, true, true, 6, 2, 6,
                CombatMath.NORMAL);
        System.out.printf("13x13x5 search: %.4f ms, %d of 845 placements scored, best at %d %d %d%n", millis,
                result.evaluated(), result.best().x, result.best().y, result.best().z);
        assertTrue(millis < 1.0, "a search took " + millis + " ms");
        assertTrue(result.evaluated() < 845, "the upper bound has to cut something");
    }

    /** Every reported damage is the CombatMath chain run on the reported centre, exposure and power. */
    @Test
    void everyDamageMatchesCombatMath()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(5.5, 64.0, 1.5, 18.0f, 8.0f, 4.0f, 17.0f, false);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 16.0f, 4.0f, 0.0f, 13.0f, false);
        target.vx = 0.2;
        target.vz = -0.1;
        CrystalSearch.Result result = new CrystalSearch().search(world, bot, target, 5.0, true, true, 6, 2, 6,
                CombatMath.HARD);
        assertFalse(result.candidates().isEmpty());
        for (CrystalSearch.Candidate candidate : result.candidates())
        {
            assertEquals(expected(bot, candidate, candidate.selfExposure, CombatMath.HARD), candidate.selfDamage,
                    1e-3f);
            float raw = CombatMath.explosionDamage(distance(target, candidate), candidate.power,
                    candidate.targetExposure);
            assertEquals(1.5f * raw, CombatMath.playerDifficultyScale(raw, CombatMath.HARD), 1e-3f);
            assertEquals(expected(target, candidate, candidate.targetExposure, CombatMath.HARD),
                    candidate.targetDamage, 1e-3f);
        }
    }

    /** Peaceful takes every explosion away, so a search there is all zeroes whatever the geometry is. */
    @Test
    void peacefulDoesNoDamage()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(7.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        CrystalSearch.Result result = new CrystalSearch().search(world, bot, target, 6.0, true, true, 6, 2, 6,
                CombatMath.PEACEFUL);
        assertFalse(result.candidates().isEmpty());
        for (CrystalSearch.Candidate candidate : result.candidates())
        {
            assertEquals(0.0f, candidate.targetDamage);
            assertEquals(0.0f, candidate.selfDamage);
            assertFalse(candidate.selfLethal);
        }
    }

    /** The list is sorted by score and every entry carries the numbers behind it. */
    @Test
    void resultsAreSortedAndExplainThemselves()
    {
        GridWorld world = arena(0, 0, 0);
        CrystalSearch.Side bot = side(7.5, 64.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, true);
        CrystalSearch.Side target = side(0.5, 63.0, 0.5, 0.0f, 0.0f, 0.0f, 20.0f, false);
        CrystalSearch.Result result = new CrystalSearch().search(world, bot, target, 6.0, true, true, 6, 2, 6,
                CombatMath.NORMAL);
        List<CrystalSearch.Candidate> candidates = result.candidates();
        assertEquals(result.best(), candidates.get(0));
        for (int i = 1; i < candidates.size(); i++)
        {
            assertTrue(candidates.get(i - 1).score >= candidates.get(i).score, "not sorted at " + i);
        }
        for (CrystalSearch.Candidate candidate : candidates)
        {
            assertTrue(candidate.power == CrystalPlacement.CRYSTAL_POWER
                    || candidate.power == CrystalPlacement.ANCHOR_POWER);
            assertTrue(candidate.targetExposure >= 0.0f && candidate.targetExposure <= 1.0f);
            assertTrue(candidate.selfExposure >= 0.0f && candidate.selfExposure <= 1.0f);
            assertTrue(candidate.leadExposure >= 0.0f && candidate.leadExposure <= 1.0f);
            assertTrue(candidate.targetDamage >= 0.0f);
            assertTrue(candidate.selfDamage >= 0.0f);
        }
    }

    /** Nothing to place on and nowhere free to put it, so the search hands back nothing at all. */
    @Test
    void solidWorldOffersNothing()
    {
        GridWorld world = new GridWorld(-8, -8, -8, 8, 8, 8);
        world.fill(-8, -8, -8, 8, 8, 8, GridWorld.STONE);
        CrystalSearch.Side bot = side(0.5, 0.0, 0.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        CrystalSearch.Side target = side(2.5, 0.0, 2.5, 20.0f, 12.0f, 0.0f, 20.0f, false);
        CrystalSearch.Result result = new CrystalSearch().search(world, bot, target, 4.5, true, true, 1, 1, 1,
                CombatMath.NORMAL);
        assertTrue(result.candidates().isEmpty());
        assertEquals(0, result.evaluated());
    }

    private static double distance(CrystalSearch.Side side, CrystalSearch.Candidate candidate)
    {
        double dx = side.x - candidate.cx;
        double dy = side.y - candidate.cy;
        double dz = side.z - candidate.cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static CrystalSearch.Candidate find(CrystalSearch.Result result, int x, int y, int z)
    {
        for (CrystalSearch.Candidate candidate : result.candidates())
        {
            if (candidate.x == x && candidate.y == y && candidate.z == z && !candidate.anchor)
            {
                return candidate;
            }
        }
        return null;
    }
}
