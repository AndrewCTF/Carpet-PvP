package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeenPercentTest
{
    /**
     * A player at (0.5, 64, 0.5) has the box (0.2, 64, 0.2) to (0.8, 65.8, 0.8). Mirroring
     * ServerExplosion.getSeenPercent the step along an axis is 1 / (size * 2 + 1), so
     * <pre>
     *   x: 1 / (0.6 * 2 + 1) = 1 / 2.2 = 0.454545   -&gt; 3 samples at t = 0, 0.454545, 0.909091
     *   y: 1 / (1.8 * 2 + 1) = 1 / 4.6 = 0.217391   -&gt; 5 samples at t = 0, 0.217391, 0.434783, 0.652174, 0.869565
     * </pre>
     * and the offset is (1 - floor(1 / step) * step) / 2 = (1 - 2 * 0.454545) / 2 = 0.045455 on x and on z,
     * added to the sampled coordinate and not to y. The sample rows land at y = 64 + t * 1.8, that is
     * 64.000, 64.391, 64.783, 65.174 and 65.565.
     */
    private static Box playerBox()
    {
        return Box.player(0.5, 64.0, 0.5);
    }

    @Test
    void rayGrid()
    {
        assertEquals(45, SeenPercent.rayCount(playerBox()));
        assertEquals(0.2, playerBox().minX, 1e-12);
        assertEquals(0.8, playerBox().maxX, 1e-12);
        assertEquals(64.0, playerBox().minY, 1e-12);
        assertEquals(65.8, playerBox().maxY, 1e-12);
        assertEquals(0.2, playerBox().minZ, 1e-12);
        assertEquals(0.8, playerBox().maxZ, 1e-12);
    }

    /** Nothing between the box and the centre: all 45 rays get through. */
    @Test
    void openIsFullyVisible()
    {
        GridWorld world = new GridWorld(-8, 60, -8, 8, 70, 8);
        assertEquals(1.0f, SeenPercent.of(world, 0.5, 64.0, 4.5, playerBox()));
    }

    /** A solid block around the box: the very first voxel of every ray is solid, so 0 of 45 get through. */
    @Test
    void solidIsNotVisible()
    {
        GridWorld world = new GridWorld(-8, 60, -8, 8, 70, 8);
        world.fill(-4, 62, -4, 4, 68, 4, GridWorld.STONE);
        assertEquals(0.0f, SeenPercent.of(world, 0.5, 64.0, 4.5, playerBox()));
    }

    /**
     * One cover block at (0, 64, 2), so it spans y in [64, 65), and the explosion centre at (0.5, 65, 4.5).
     * By z = 2 every ray has travelled (2 - 0.2455) / (4.5 - 0.2455) = 0.4124 of the way, so the three
     * lower sample rows arrive at y = 64.41, 64.64 and 64.87, inside the block, while the two upper rows
     * start above it and only fall to 65.10 and 65.33 by z = 2. Two rows of three by three rays, that is
     * 18 of the 45, get through: 18 / 45 = 0.4.
     */
    @Test
    void partialCoverCountsBlockedRays()
    {
        GridWorld world = new GridWorld(-8, 60, -8, 8, 70, 8);
        world.set(0, 64, 2, GridWorld.STONE);
        assertEquals(0.4f, SeenPercent.of(world, 0.5, 65.0, 4.5, playerBox()), 1e-6f);
    }

    /** A wall between the centre and the box: every ray walks into it, so nothing gets through. */
    @Test
    void wallInFrontBlocksEveryRay()
    {
        GridWorld world = new GridWorld(-8, 60, -8, 8, 70, 8);
        world.fill(-2, 62, -2, 2, 68, -2, GridWorld.STONE);
        assertEquals(0.0f, SeenPercent.of(world, 0.5, 64.0, -4.5, playerBox()));
    }

    /** The negative step guard of getSeenPercent, which returns 0 before casting a single ray. */
    @Test
    void invertedBoxIsNotVisible()
    {
        GridWorld world = new GridWorld(-8, 60, -8, 8, 70, 8);
        Box inverted = new Box(1.0, 64.0, 0.0, 0.0, 65.8, 0.6);
        assertEquals(0.0f, SeenPercent.of(world, 0.5, 64.0, 4.5, inverted));
    }

    @Test
    void crystalNeedsAnObsidianOrBedrockBase()
    {
        GridWorld world = GridWorld.of(8, 8, 8);
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
        world.set(0, 0, 0, GridWorld.STONE);
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
        world.set(0, 0, 0, GridWorld.OBSIDIAN);
        assertTrue(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
        world.set(0, 0, 0, GridWorld.BEDROCK);
        assertTrue(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
    }

    /** The cell above the base has to be air, which is the Level.isEmptyBlock test in EndCrystalItem.useOn. */
    @Test
    void crystalNeedsAirAboveTheBase()
    {
        GridWorld world = GridWorld.of(8, 8, 8);
        world.set(0, 0, 0, GridWorld.OBSIDIAN);
        world.set(0, 1, 0, GridWorld.STONE);
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
        world.set(0, 1, 0, GridWorld.AIR);
        assertTrue(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
    }

    /**
     * EndCrystalItem.useOn queries the entities in the box from the cell above the base to two blocks up, so
     * a standing player inside that box stops the placement while a player whose feet start at the top of the
     * box does not, because AABB.intersects is strict on every face.
     */
    @Test
    void crystalNeedsItsColumnFree()
    {
        GridWorld world = GridWorld.of(8, 8, 8);
        world.set(0, 0, 0, GridWorld.OBSIDIAN);
        assertTrue(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0));
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0, Box.player(0.5, 1.0, 0.5)));
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0, Box.player(0.5, 2.0, 0.5)));
        assertTrue(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0, Box.player(0.5, 3.0, 0.5)));
        assertFalse(CrystalPlacement.canPlaceCrystal(world, 0, 0, 0, null, Box.player(0.5, 1.0, 0.5)));
    }

    @Test
    void centresAndPowers()
    {
        double[] crystal = CrystalPlacement.crystalCentre(3, 4, 5);
        assertEquals(3.5, crystal[0], 1e-12);
        assertEquals(5.0, crystal[1], 1e-12);
        assertEquals(5.5, crystal[2], 1e-12);
        double[] anchor = CrystalPlacement.anchorCentre(3, 4, 5);
        assertEquals(3.5, anchor[0], 1e-12);
        assertEquals(4.5, anchor[1], 1e-12);
        assertEquals(5.5, anchor[2], 1e-12);
        assertEquals(6.0f, CrystalPlacement.CRYSTAL_POWER);
        assertEquals(5.0f, CrystalPlacement.ANCHOR_POWER);
    }

    /** An anchor is an ordinary block: it needs an empty cell, and it blows up if something stands in it. */
    @Test
    void anchorNeedsAnEmptyCell()
    {
        GridWorld world = GridWorld.of(8, 8, 8);
        assertTrue(CrystalPlacement.canPlaceAnchor(world, 0, 0, 0));
        assertFalse(CrystalPlacement.canPlaceAnchor(world, 0, 0, 0, Box.player(0.5, 0.0, 0.5)));
        world.set(0, 0, 0, GridWorld.OBSIDIAN);
        assertFalse(CrystalPlacement.canPlaceAnchor(world, 0, 0, 0));
    }

    /** Bridging only works where the bot can put a block, and it still leaves the crystal cell empty. */
    @Test
    void bridgingNeedsAnEmptyBase()
    {
        GridWorld world = GridWorld.of(8, 8, 8);
        assertTrue(CrystalPlacement.canBridgeCrystal(world, 0, 0, 0));
        assertFalse(CrystalPlacement.canBridgeCrystal(world, 0, 0, 0, Box.player(0.5, 1.0, 0.5)));
        world.set(0, 0, 0, GridWorld.OBSIDIAN);
        assertFalse(CrystalPlacement.canBridgeCrystal(world, 0, 0, 0));
        world.set(0, 0, 0, GridWorld.AIR);
        world.set(0, 1, 0, GridWorld.OBSIDIAN);
        assertFalse(CrystalPlacement.canBridgeCrystal(world, 0, 0, 0));
    }
}
