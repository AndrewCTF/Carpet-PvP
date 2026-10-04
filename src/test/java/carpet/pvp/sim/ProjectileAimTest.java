package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectileAimTest
{
    private static final double TOL = 1.0E-9;
    private static final int LIMIT = 200;

    private static ProjectileAim.Shooter shooter()
    {
        ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
        shooter.x = 0.0;
        shooter.y = 0.0;
        shooter.z = 0.0;
        shooter.onGround = true;
        return shooter;
    }

    private static ProjectileSim.Target target(double x, double y, double z, double vx, double vy, double vz)
    {
        ProjectileSim.Target target = new ProjectileSim.Target();
        target.x = x;
        target.y = y;
        target.z = z;
        target.vx = vx;
        target.vy = vy;
        target.vz = vz;
        return target;
    }

    /** Where the aim's own launch ends up at its own flight time. */
    private static ProjectileSim flightOf(ProjectileAim.Aim aim)
    {
        ProjectileSim flight = new ProjectileSim(aim.launch);
        for (int tick = 0; tick < aim.ticks; tick++)
        {
            flight.step();
        }
        return flight;
    }

    /**
     * A solved aim has to cross the target's box, by the game's own swept segment test and by the solver's own measure
     * of the gap. The arrival position is not the box centre, because the game only holds a position at whole ticks and
     * the shot crosses the box part way through the tick.
     */
    private static void assertArrives(ProjectileAim.Aim aim, ProjectileSim.Target target, String what)
    {
        assertTrue(aim.solved, what + ": no solution");
        int hit = new ProjectileSim(aim.launch).tickHitting(target, aim.ticks + 2);
        assertTrue(hit >= 1 && hit <= aim.ticks + 1, what + ": the hit lands on tick " + hit);
    }

    @Test
    void viewVectorConvention()
    {
        double[] flat = ProjectileAim.direction(0.0, 0.0);
        assertEquals(0.0, flat[0], TOL);
        assertEquals(0.0, flat[1], TOL);
        assertEquals(1.0, flat[2], TOL);
        double[] east = ProjectileAim.direction(90.0, 0.0);
        assertEquals(-1.0, east[0], 1.0E-12);
        assertEquals(0.0, east[2], 1.0E-12);
        double[] down = ProjectileAim.direction(0.0, 90.0);
        assertEquals(-1.0, down[1], 1.0E-12);
    }

    @Test
    void solvedAimHitsStationaryTargetAtSeveralDistances()
    {
        for (double distance : new double[] {3.0, 6.0, 10.0, 18.0, 25.0})
        {
            ProjectileSim.Target target = target(distance, 0.0, 0.0, 0.0, 0.0, 0.0);
            ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.PEARL, shooter(), target, LIMIT);
            assertArrives(arcs[0], target, "direct arc at " + distance);
            if (arcs[1].solved)
            {
                assertArrives(arcs[1], target, "lob at " + distance);
                // A flatter shot looks further down, so its pitch is the larger of the two.
                assertTrue(arcs[0].pitch > arcs[1].pitch, "the direct arc at " + distance + " is not the flatter one");
                assertTrue(arcs[1].ticks > arcs[0].ticks, "the lob at " + distance + " is not the slower one");
                assertTrue(arcs[1].apex > arcs[0].apex, "the lob at " + distance + " does not rise higher");
            }
            else
            {
                // Too close to lob anything onto, which is the honest answer rather than a bad shot dressed up.
                assertTrue(distance < 10.0, "a lob should be available at " + distance + " blocks");
            }
        }
    }

    /**
     * A pearl leaves at 1.5 blocks per tick and loses a factor 0.99 every tick, so drag alone caps it at about 150
     * blocks of travel, but gravity brings a flat throw down after ten ticks. Sixty blocks is well out of reach.
     */
    @Test
    void outOfRangeTargetIsReported()
    {
        for (double distance : new double[] {60.0, 120.0, 400.0})
        {
            ProjectileSim.Target far = target(distance, 0.0, 0.0, 0.0, 0.0, 0.0);
            ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.PEARL, shooter(), far, LIMIT);
            assertFalse(arcs[0].solved, distance + " blocks should be out of range");
            assertFalse(arcs[1].solved, distance + " blocks should be out of range");
            assertSameOutOfRange(arcs[0]);
            assertSameOutOfRange(arcs[1]);
        }
        assertEquals(0, ProjectileAim.OUT_OF_RANGE.ticks);
        assertEquals(-1, ProjectileAim.OUT_OF_RANGE.drawTicks);
        assertFalse(ProjectileAim.OUT_OF_RANGE.solved);
        assertNull(ProjectileAim.OUT_OF_RANGE.launch);
    }

    private static void assertSameOutOfRange(ProjectileAim.Aim aim)
    {
        assertFalse(aim.solved);
        assertEquals(ProjectileAim.OUT_OF_RANGE.ticks, aim.ticks);
        assertNull(aim.launch);
    }

    @Test
    void solvedAimHitsWalkingTarget()
    {
        for (double drift : new double[] {0.13, -0.13, 0.28})
        {
            // Nine blocks away along +z and drifting sideways in x, which is the case where leading matters.
            ProjectileSim.Target target = target(0.0, 0.0, 9.0, drift, 0.0, 0.0);
            ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.TRIDENT, shooter(), target, LIMIT);
            assertArrives(arcs[0], target, "trident at a target drifting " + drift);
            double naiveYaw = Math.toDegrees(Math.atan2(0.0, 9.0));
            assertTrue(Math.abs(naiveYaw - arcs[0].yaw) > 0.05,
                    "the solved yaw should lead a target that is moving sideways, " + arcs[0].yaw);
        }
    }

    @Test
    void solvedAimHitsRisingAndFallingTarget()
    {
        ProjectileSim.Target jumping = target(8.0, 0.0, 0.0, 0.0, 0.42, 0.0);
        ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.PEARL, shooter(), jumping, LIMIT);
        assertArrives(arcs[0], jumping, "a target rising at a jump");
        ProjectileSim.Target falling = target(8.0, 12.0, 0.0, 0.0, -1.5, 0.0);
        ProjectileAim.Aim[] down = ProjectileAim.solveHandThrow(ProjectileSim.Kind.PEARL, shooter(), falling, LIMIT);
        assertArrives(down[0], falling, "a target falling from twelve blocks up");
        // A potion thrown two hundred blocks up has nothing like enough speed to get there.
        ProjectileSim.Target gone = target(0.0, 200.0, 0.0, 0.0, 0.0, 0.0);
        assertFalse(ProjectileAim.solveHandThrow(ProjectileSim.Kind.SPLASH_POTION, shooter(), gone, LIMIT)[0].solved,
                "a potion cannot reach two hundred blocks up");
        assertFalse(ProjectileAim.solveHandThrow(ProjectileSim.Kind.SPLASH_POTION, shooter(), gone, LIMIT)[1].solved,
                "a potion cannot lob two hundred blocks up");
    }

    @Test
    void movingShooterVelocityIsCompensated()
    {
        ProjectileSim.Target target = target(12.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ProjectileAim.Aim[] still = ProjectileAim.solveHandThrow(ProjectileSim.Kind.TRIDENT, shooter(), target, LIMIT);
        ProjectileAim.Shooter running = shooter();
        running.vz = 0.288;
        ProjectileAim.Aim[] moving = ProjectileAim.solveHandThrow(ProjectileSim.Kind.TRIDENT, running, target, LIMIT);
        assertArrives(still[0], target, "a standing thrower");
        assertArrives(moving[0], target, "a running thrower");
        // Running forwards carries the throw further, so the same distance is covered in fewer ticks.
        assertTrue(moving[0].ticks <= still[0].ticks, "a running thrower should arrive no later");
    }

    @Test
    void bowDrawTimeAffectsSpeed()
    {
        ProjectileSim.Target close = target(4.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ProjectileAim.Aim[] fullDraw = ProjectileAim.solveBow(shooter(), close, LIMIT);
        assertArrives(fullDraw[0], close, "a full draw");
        assertEquals(ProjectileSim.BOW_FULL_DRAW, fullDraw[0].drawTicks);

        ProjectileAim.Shooter half = shooter();
        half.drawTicks = 10;
        ProjectileAim.Aim[] halfDraw = ProjectileAim.solveBow(half, close, LIMIT);
        assertArrives(halfDraw[0], close, "a half draw");
        assertEquals(10, halfDraw[0].drawTicks);
        // The launch speed is exactly what BowItem scales the draw fraction by.
        assertEquals(3.0, ProjectileSim.bowSpeed(ProjectileSim.bowPower(20)), TOL);
        assertEquals(1.25, ProjectileSim.bowSpeed(ProjectileSim.bowPower(10)), 1.0E-9);
        assertEquals(0.3225, ProjectileSim.bowSpeed(ProjectileSim.bowPower(3)), 1.0E-9);
    }

    /**
     * A full draw arrow leaves at 3.0 with a 0.99 drag, so it flies 3.0 * 99 blocks before it would stop even if it
     * never fell. Fifty-five blocks is well inside that; a half draw at 1.25 is not far enough at this flight time.
     */
    @Test
    void bowPicksTheFastestDrawThatReaches()
    {
        ProjectileSim.Target far = target(55.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ProjectileAim.Aim[] arcs = ProjectileAim.solveBow(shooter(), far, LIMIT);
        assertArrives(arcs[0], far, "a full draw at fifty-five blocks");
        assertEquals(ProjectileSim.BOW_FULL_DRAW, arcs[0].drawTicks);
        ProjectileAim.Shooter half = shooter();
        half.drawTicks = 10;
        assertFalse(ProjectileAim.solveBow(half, far, LIMIT)[0].solved, "half draw does not reach fifty-five blocks");
        // A draw of one tick is below the 0.1 gate, so the game refuses to fire at all.
        ProjectileSim.Target pointBlank = target(1.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ProjectileAim.Shooter twitchy = shooter();
        twitchy.drawTicks = 1;
        assertFalse(ProjectileSim.bowFires(ProjectileSim.bowPower(twitchy.drawTicks)));
        assertFalse(ProjectileAim.solveBow(twitchy, pointBlank, LIMIT)[0].solved);
    }

    /** The landing query has to agree with a straight simulation of the throw it describes. */
    @Test
    void pearlLandsWhereItIsThrown()
    {
        double[] landing = new double[3];
        int ticks = ProjectileAim.pearlLandingTicks(shooter(), 0.0, 5.0, 0.0, LIMIT, landing);
        assertTrue(ticks > 0);
        ProjectileSim pearl = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0,
                0.0, 5.0, 0.0, 0.0, 0.0, true));
        for (int tick = 0; tick < ticks; tick++)
        {
            pearl.step();
        }
        assertEquals(pearl.x, landing[0], TOL);
        assertEquals(pearl.y, landing[1], TOL);
        assertEquals(pearl.z, landing[2], TOL);
        assertTrue(landing[1] <= 0.0 && landing[1] > -0.5, "a pearl stops just below the surface");
        // Solving for that landing point has to hand back a throw that lands there again in the same time.
        ProjectileAim.Aim[] arcs = ProjectileAim.solveGroundPoint(ProjectileSim.Kind.PEARL, shooter(),
                landing[0], landing[1], landing[2], LIMIT);
        assertTrue(arcs[0].solved, "the landing point should be reachable by some throw");
        double[] again = new double[3];
        int againTicks = ProjectileAim.pearlLandingTicks(shooter(), arcs[0].yaw, arcs[0].pitch, 0.0, LIMIT, again);
        assertTrue(againTicks > 0, "the solved throw should still come down");
        assertTrue(Math.abs(againTicks - ticks) <= 2, "the solved throw takes " + againTicks + " ticks against " + ticks);
        assertEquals(landing[2], again[2], 0.3, "the solved throw should land at the same spot");
    }

    /** A throw that never comes down inside the budget is reported as such rather than as a landing. */
    @Test
    void pearlStraightUpNeverLandsInsideTheBudget()
    {
        double[] landing = new double[3];
        assertEquals(-1, ProjectileAim.pearlLandingTicks(shooter(), 0.0, -89.0, 0.0, 8, landing));
    }

    /**
     * A wind charge has no gravity and no drag, so thrown down at forty five degrees it leaves at -sin(45) * 1.5
     * vertically and cos(45) * 1.5 forwards. From eye height 1.62 that puts the burst 1.62 blocks ahead, and the
     * thrower's own velocity adds straight on.
     */
    @Test
    void windChargeBurstsAheadOfTheThrower()
    {
        ProjectileAim.Shooter still = shooter();
        double[] burst = ProjectileAim.windChargeBurst(still, 0.0, 45.0, 0.0);
        assertNotNull(burst);
        assertEquals(0.0, burst[0], 1.0E-12);
        assertEquals(0.0, burst[1], 1.0E-12);
        assertEquals(1.62, burst[2], 1.0E-12);
        // A walking thrower keeps the same flight time of 1.62 / (1.5 sin 45) ticks but carries 0.288 further each
        // one of them, so the burst lands 1.62 + 0.288 * 1.527 blocks ahead instead of 1.62.
        ProjectileAim.Shooter running = shooter();
        running.vz = 0.288;
        double travel = 1.62 / (1.5 * Math.sin(Math.toRadians(45.0)));
        assertEquals(1.62 + 0.288 * travel, ProjectileAim.windChargeBurst(running, 0.0, 45.0, 0.0)[2], 1.0E-12);
        assertEquals(1.5274, travel, 1.0E-4);
        // Thrown flat or upwards it never reaches the ground.
        assertNull(ProjectileAim.windChargeBurst(still, 0.0, 0.0, 0.0));
        assertNull(ProjectileAim.windChargeBurst(still, 0.0, -30.0, 0.0));
    }

    /** The self splash is aimed at the thrower's own feet, so the effect still lands inside its range of them. */
    @Test
    void selfSplashLandsOnTheThrowersFeet()
    {
        ProjectileAim.Shooter running = shooter();
        running.vz = 0.288;
        ProjectileAim.Aim[] arcs = ProjectileAim.selfSplash(running, 0.0, LIMIT);
        assertTrue(arcs[0].solved, "a splash potion should be throwable at its own feet while walking");
        ProjectileSim potion = new ProjectileSim(arcs[0].launch);
        double[] landing = new double[3];
        assertTrue(potion.groundPoint(0.0, LIMIT, landing));
        ProjectileSim.Target self = target(running.x, 0.0, running.z, 0.0, 0.0, 0.0);
        assertEquals(1.0, ProjectileSim.splashStrengthAt(landing[0], landing[1], landing[2], self), 1.0E-12);
        // A stationary thrower drops one almost straight down, but the roll keeps it twenty degrees above the aim.
        ProjectileAim.Shooter still = shooter();
        ProjectileAim.Aim[] dropped = ProjectileAim.selfSplash(still, 0.0, LIMIT);
        assertTrue(dropped[0].solved);
        assertTrue(dropped[0].pitch > 60.0, "a standing thrower looks well down, " + dropped[0].pitch);
    }

    /** A wind charge aimed at a target is solved with the straight-line physics it actually flies. */
    @Test
    void windChargeAimHits()
    {
        ProjectileSim.Target target = target(14.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.WIND_CHARGE, shooter(), target, LIMIT);
        assertArrives(arcs[0], target, "a wind charge at fourteen blocks");
        ProjectileSim charge = flightOf(arcs[0]);
        assertEquals(14.0, charge.x, 1.5);
        assertEquals(0.9, charge.y, 1.5);
        assertEquals(1.5, charge.speed(), 1.0E-9);
        assertEquals(0.0, arcs[0].apex, 1.0E-12);
    }

    /** A charge thrown at a target behind the thrower still reaches it, because it has no gravity to curve round. */
    @Test
    void windChargeCanBeThrownBackwards()
    {
        ProjectileSim.Target behind = target(0.0, 0.0, -12.0, 0.0, 0.0, 0.0);
        ProjectileAim.Aim[] arcs = ProjectileAim.solveHandThrow(ProjectileSim.Kind.WIND_CHARGE, shooter(), behind, LIMIT);
        assertArrives(arcs[0], behind, "a charge thrown backwards");
        assertEquals(180.0, Math.abs(arcs[0].yaw), 1.0E-6);
    }
}