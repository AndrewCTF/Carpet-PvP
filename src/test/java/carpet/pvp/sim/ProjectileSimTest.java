package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectileSimTest
{
    private static final double TOL = 1.0E-9;

    /**
     * A pearl thrown from a standing bot at yaw 0, pitch 0 leaves at (0, 1.52, 0) with a velocity of (0, 0, 1.5).
     * ThrowableProjectile.tick does vy -= 0.03, v *= 0.99, then p += v, so by hand:
     * tick 1 v = (0, -0.0297, 1.485)      p = (0, 1.4903, 1.485)
     * tick 2 v = (0, -0.059103, 1.47015)  p = (0, 1.431197, 2.95515)
     * tick 3 v = (0, -0.08821197, 1.4554485) p = (0, 1.34298503, 4.4105985)
     * tick 4 v = (0, -0.1170298503, 1.440894015) p = (0, 1.2259551797, 5.851492515)
     * tick 5 v = (0, -0.145559551797, 1.42648507485) p = (0, 1.080395627903, 7.27797758985)
     */
    private static ProjectileSim standingPearl()
    {
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, true);
        return new ProjectileSim(launch);
    }

    @Test
    void pearlLaunchState()
    {
        ProjectileSim pearl = standingPearl();
        assertEquals(0.0, pearl.x, TOL);
        assertEquals(1.62 - 0.1, pearl.y, TOL);
        assertEquals(0.0, pearl.z, TOL);
        assertEquals(0.0, pearl.vx, TOL);
        assertEquals(0.0, pearl.vy, TOL);
        assertEquals(1.5, pearl.vz, TOL);
        assertEquals(1.62 - 0.1, ProjectileSim.handLaunchY(0.0, ProjectileSim.Kind.PEARL), TOL);
        // A wind charge starts at the eyes, not a tenth of a block below them.
        assertEquals(1.62, ProjectileSim.handLaunchY(0.0, ProjectileSim.Kind.WIND_CHARGE), TOL);
    }

    @Test
    void pearlPositionsMatchHandSimulation()
    {
        double[][] expected = {
            {0.0, 1.4903, 1.485},
            {0.0, 1.431197, 2.95515},
            {0.0, 1.34298503, 4.4105985},
            {0.0, 1.2259551797, 5.851492515},
            {0.0, 1.080395627903, 7.27797758985},
        };
        ProjectileSim pearl = standingPearl();
        for (int tick = 0; tick < expected.length; tick++)
        {
            pearl.step();
            assertEquals(expected[tick][0], pearl.x, TOL, "x at tick " + (tick + 1));
            assertEquals(expected[tick][1], pearl.y, TOL, "y at tick " + (tick + 1));
            assertEquals(expected[tick][2], pearl.z, TOL, "z at tick " + (tick + 1));
        }
        // The closed form has to agree with the stepping, which is what the aim solver relies on.
        double[] closed = new double[3];
        standingPearl().displacement(5, closed);
        assertEquals(0.0, closed[0], TOL);
        assertEquals(1.080395627903 - 1.52, closed[1], TOL);
        assertEquals(7.27797758985, closed[2], TOL);
    }

    /**
     * A crossbow arrow leaves at (0, 1.52, 0) with a velocity of (0, 0, 3.15). AbstractArrow.tick moves first, so the
     * first tick keeps the full speed and only then drags and applies gravity:
     * tick 1 p = (0, 1.52, 3.15)          v = (0, -0.05, 3.1185)
     * tick 2 p = (0, 1.47, 6.2685)        v = (0, -0.0995, 3.087315)
     * tick 3 p = (0, 1.3705, 9.355815)    v = (0, -0.148505, 3.05644185)
     * tick 4 p = (0, 1.221995, 12.41225685)
     */
    @Test
    void arrowPositionsMatchHandSimulation()
    {
        ProjectileSim arrow = new ProjectileSim(ProjectileSim.Kind.ARROW, 0.0, 1.52, 0.0, 0.0, 0.0, 3.15);
        double[][] expected = {
            {0.0, 1.52, 3.15},
            {0.0, 1.47, 6.2685},
            {0.0, 1.3705, 9.355815},
            {0.0, 1.221995, 12.41225685},
        };
        for (int tick = 0; tick < expected.length; tick++)
        {
            arrow.step();
            assertEquals(expected[tick][1], arrow.y, 1.0E-12, "y at tick " + (tick + 1));
            assertEquals(expected[tick][2], arrow.z, 1.0E-12, "z at tick " + (tick + 1));
        }
        double[] closed = new double[3];
        new ProjectileSim(ProjectileSim.Kind.ARROW, 0.0, 1.52, 0.0, 0.0, 0.0, 3.15).displacement(4, closed);
        assertEquals(12.41225685, closed[2], 1.0E-12);
        assertEquals(1.221995 - 1.52, closed[1], 1.0E-12);
    }

    /** The two update orders have to stay distinguishable, otherwise the whole model collapses to one of them. */
    @Test
    void updateOrdersDiffer()
    {
        ProjectileSim pearl = standingPearl();
        ProjectileSim arrow = new ProjectileSim(ProjectileSim.Kind.ARROW, 0.0, 1.52, 0.0, 0.0, 0.0, 1.5);
        for (int tick = 0; tick < 3; tick++)
        {
            pearl.step();
            arrow.step();
        }
        assertEquals(1.34298503, pearl.y, 1.0E-12);
        assertEquals(1.52 - 0.05 - 0.0995, arrow.y, 1.0E-12);
        assertTrue(pearl.y < arrow.y, "a throwable starts falling before an arrow does");
    }

    @Test
    void closedFormMatchesSteppingForEveryKind()
    {
        for (ProjectileSim.Kind kind : ProjectileSim.Kind.values())
        {
            for (boolean water : new boolean[] {false, true})
            {
                ProjectileSim start = new ProjectileSim(kind, 1.0, 70.0, -3.0, 0.7, 0.4, -1.1);
                start.inWater = water;
                double[] closed = new double[3];
                start.displacement(17, closed);
                ProjectileSim stepped = new ProjectileSim(kind, start.x, start.y, start.z, start.vx, start.vy, start.vz);
                stepped.inWater = water;
                for (int tick = 0; tick < 17; tick++)
                {
                    stepped.step();
                }
                assertEquals(stepped.x, start.x + closed[0], 1.0E-9, kind + " x water=" + water);
                assertEquals(stepped.y, start.y + closed[1], 1.0E-9, kind + " y water=" + water);
                assertEquals(stepped.z, start.z + closed[2], 1.0E-9, kind + " z water=" + water);
                // And the inverse has to hand back the velocity the flight started from.
                ProjectileSim base = new ProjectileSim(kind, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
                base.inWater = water;
                double[] need = new double[3];
                base.requiredVelocity(17, closed[0], closed[1], closed[2], need);
                assertEquals(start.vx, need[0], 1.0E-9, kind + " inverse x water=" + water);
                assertEquals(start.vy, need[1], 1.0E-9, kind + " inverse y water=" + water);
                assertEquals(start.vz, need[2], 1.0E-9, kind + " inverse z water=" + water);
            }
        }
    }

    @Test
    void waterDragPerKind()
    {
        assertEquals(0.8, ProjectileSim.Kind.PEARL.waterDrag(), TOL);
        assertEquals(0.8, ProjectileSim.Kind.SPLASH_POTION.waterDrag(), TOL);
        assertEquals(0.6, ProjectileSim.Kind.ARROW.waterDrag(), TOL);
        assertEquals(0.99, ProjectileSim.Kind.TRIDENT.waterDrag(), TOL);
        assertEquals(1.0, ProjectileSim.Kind.WIND_CHARGE.waterDrag(), TOL);
        // The abstract hurting projectile defaults are kept here because the wind charge overrides both of them.
        assertEquals(0.95, ProjectileSim.HURTING_INERTIA, TOL);
        assertEquals(0.8, ProjectileSim.HURTING_LIQUID_INERTIA, TOL);
    }

    /**
     * Mirrors the server measurement of a hand throw: a pearl at yaw 0, pitch 0 came out at roughly
     * (0.003, 0.009, 1.486), and with the shooter walking at (0.25, 0, 0) on the ground it came out at
     * (0.232, 0.005, 1.519). The horizontal shooter velocity is added, its vertical one is not.
     */
    @Test
    void shooterVelocityIsAdded()
    {
        ProjectileSim walking = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.25, 0.0, 0.0, true));
        assertEquals(0.25, walking.vx, TOL);
        assertEquals(0.0, walking.vy, TOL);
        assertEquals(1.5, walking.vz, TOL);
        ProjectileSim airborne = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.25, 0.3, 0.0, false));
        assertEquals(0.3, airborne.vy, TOL);
    }

    /**
     * A thrown potion gets its direction from -sin(yaw)cos(pitch), -sin(pitch + roll) and cos(yaw)cos(pitch) with
     * roll = -20, so it leaves twenty degrees above where the thrower looks. The server measured a length of 0.5028
     * for a potion thrown at yaw 0, pitch 0, which is the 0.5 launch speed with a little inaccuracy on top.
     */
    @Test
    void potionRollLiftsTheThrow()
    {
        ProjectileSim potion = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.SPLASH_POTION, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, true));
        assertEquals(-20.0, ProjectileSim.POTION_ROLL, TOL);
        assertEquals(0.5, potion.speed(), TOL);
        // The direction is (-sin(yaw)cos(pitch), -sin(pitch + roll), cos(yaw)cos(pitch)) normalised, so the roll makes
        // the vector longer than one and the normalisation takes a cos(pitch) worth of speed back off the horizontal.
        double rise = -Math.sin(Math.toRadians(-20.0));
        double norm = Math.hypot(rise, 1.0);
        assertEquals(rise / norm * 0.5, potion.vy, TOL);
        assertEquals(0.5 / norm, potion.vz, TOL);
        assertEquals(0.16180778855909234, potion.vy, 1.0E-15);
        // Looking down twenty degrees levels the throw off, which is the measured pitch of a straight splash throw.
        ProjectileSim level = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.SPLASH_POTION, 0.0, 0.0, 0.0,
                0.0, 20.0, 0.0, 0.0, 0.0, true));
        assertEquals(0.0, level.vy, 1.0E-12);
        assertEquals(0.5, level.vz, 1.0E-12);
    }

    /**
     * The inaccuracy is added after the direction is normalised and before the speed is applied, so it spreads the
     * shot without changing its nominal speed: Projectile.getMovementToShoot offsets each axis by
     * triangle(0, 0.0172275 * inaccuracy) and RandomSource.triangle is mean + range * (u1 - u2).
     */
    @Test
    void inaccuracySpreadsWithoutChangingSpeed()
    {
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, true);
        launch.jitterZ = 1.0;
        ProjectileSim spread = new ProjectileSim(launch);
        assertEquals(0.0172275 * 1.5, spread.vz - 1.5, 1.0E-12);
        // A wind charge keeps its speed in both media, so it flies a straight line at 1.5 blocks per tick.
        ProjectileSim charge = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.WIND_CHARGE, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, true));
        for (int tick = 0; tick < 10; tick++)
        {
            charge.step();
        }
        assertEquals(15.0, charge.z, TOL);
        assertEquals(1.62, charge.y, TOL);
        assertEquals(1.5, charge.speed(), TOL);
    }

    /**
     * Mirrors ThrownSplashPotion.onHitAsPotion: strength = 1 - sqrt(distanceToSqr) / 4, skipped at or beyond
     * distanceToSqr 16. So zero blocks gives one, one block 0.75, sqrt(2) gives 0.6464 and four blocks nothing.
     */
    @Test
    void splashStrengthScaling()
    {
        assertEquals(1.0, ProjectileSim.splashStrength(0.0), TOL);
        assertEquals(0.75, ProjectileSim.splashStrength(1.0), TOL);
        assertEquals(0.5, ProjectileSim.splashStrength(4.0), TOL);
        assertEquals(0.25, ProjectileSim.splashStrength(9.0), TOL);
        assertEquals(0.0, ProjectileSim.splashStrength(16.0), TOL);
        assertEquals(1.0 - Math.sqrt(2.0) / 4.0, ProjectileSim.splashStrength(2.0), TOL);
        // The duration lambda truncates to (int)(duration * strength * scale + 0.5).
        assertEquals(900, ProjectileSim.splashDuration(1800, 0.5, 1.0));
        assertEquals(1350, ProjectileSim.splashDuration(1800, 0.75, 1.0));
        assertEquals(0, ProjectileSim.splashDuration(1800, 0.0, 1.0));
        assertEquals(1800, ProjectileSim.splashDuration(1800, 1.0, 1.0));
    }

    /**
     * A splash at a target's own feet lands inside the target box, so the strength is one. Two blocks to the side the
     * impact box edge and the margin-grown target box are 1.7 apart, which is what the game measures.
     */
    @Test
    void splashAtOwnFeet()
    {
        ProjectileSim.Target target = new ProjectileSim.Target();
        target.x = 3.0;
        target.z = -4.0;
        assertEquals(1.0, ProjectileSim.splashStrengthAt(3.0, 0.0, -4.0, target), TOL);
        // The impact box is four blocks wide, so anything inside it takes the full effect.
        assertEquals(1.0, ProjectileSim.splashStrengthAt(5.0, 0.0, -4.0, target), TOL);
        // Five blocks away the target box is 0.4 clear of the impact box, because of the 0.3 projectile margin.
        assertEquals(0.9, ProjectileSim.splashStrengthAt(8.0, 0.0, -4.0, target), 1.0E-12);
        assertEquals(1.0 - 3.4 / 4.0, ProjectileSim.splashStrengthAt(11.0, 0.0, -4.0, target), 1.0E-12);
        // Four and a bit blocks clear of the box is past SPLASH_RANGE_SQ, so there is no effect left.
        assertEquals(0.0, ProjectileSim.splashStrengthAt(11.7, 0.0, -4.0, target), TOL);
        assertEquals(1.0, ProjectileSim.splashStrengthAt(3.0, 0.0, -4.4, target), TOL);
        assertEquals(0.3, ProjectileSim.splashMargin(), TOL);
    }

    /**
     * Mirrors AbstractArrow.onHitEntity: damage is ceil(clamp(speed * baseDamage, 0, Integer.MAX_VALUE)) with
     * ARROW_BASE_DAMAGE 2.0. A full draw bow shot leaves at speed 3.0 and a half draw at power 0.4166667 times 3.
     * A critical hit then adds nextInt(damage / 2 + 2), capped at twice the damage.
     */
    @Test
    void arrowDamageFromSpeed()
    {
        double fullDraw = ProjectileSim.bowSpeed(ProjectileSim.bowPower(20));
        double halfDraw = ProjectileSim.bowSpeed(ProjectileSim.bowPower(10));
        assertEquals(3.0, fullDraw, TOL);
        assertEquals(1.25, halfDraw, 1.0E-9);
        assertEquals(6, ProjectileSim.arrowDamage(fullDraw, ProjectileSim.ARROW_BASE_DAMAGE));
        assertEquals(3, ProjectileSim.arrowDamage(halfDraw, ProjectileSim.ARROW_BASE_DAMAGE));
        assertEquals(7, ProjectileSim.arrowDamage(ProjectileSim.CROSSBOW_ARROW_SPEED, ProjectileSim.ARROW_BASE_DAMAGE));
        assertEquals(0, ProjectileSim.arrowDamage(0.0, ProjectileSim.ARROW_BASE_DAMAGE));
        assertEquals(5, ProjectileSim.arrowCritRollBound(6));
        assertEquals(6, ProjectileSim.arrowCritDamage(6, 0));
        assertEquals(10, ProjectileSim.arrowCritDamage(6, 4));
        assertEquals(12, ProjectileSim.arrowCritDamage(6, 9));
        // The damage only ever falls with distance because the speed decays at 0.99 per tick.
        ProjectileSim arrow = new ProjectileSim(ProjectileSim.Kind.ARROW, 0.0, 1.52, 0.0, 0.0, 0.0, fullDraw);
        for (int tick = 0; tick < 20; tick++)
        {
            arrow.step();
        }
        assertEquals(6, ProjectileSim.arrowDamage(arrow.speed(), ProjectileSim.ARROW_BASE_DAMAGE));
        assertTrue(arrow.speed() < fullDraw);
    }

    /** Mirrors BowItem.getPowerForTime, which is what maps draw ticks to the launch speed. */
    @Test
    void bowDrawCurve()
    {
        assertEquals(0.0, ProjectileSim.bowPower(0), TOL);
        assertEquals(0.1025 / 3.0, ProjectileSim.bowPower(1), 1.0E-12);
        assertEquals(0.1075, ProjectileSim.bowPower(3), TOL);
        assertEquals(0.1875, ProjectileSim.bowPower(5), TOL);
        assertEquals(1.25 / 3.0, ProjectileSim.bowPower(10), 1.0E-12);
        assertEquals(0.6875, ProjectileSim.bowPower(15), TOL);
        assertEquals(2.8025 / 3.0, ProjectileSim.bowPower(19), 1.0E-12);
        assertEquals(1.0, ProjectileSim.bowPower(20), TOL);
        assertEquals(1.0, ProjectileSim.bowPower(40), TOL);
        // BowItem.releaseUsing refuses below a draw fraction of 0.1, which is between one and three ticks.
        assertFalse(ProjectileSim.bowFires(ProjectileSim.bowPower(1)));
        assertTrue(ProjectileSim.bowFires(ProjectileSim.bowPower(3)));
        assertEquals(0.1075, ProjectileSim.bowInaccuracy(ProjectileSim.bowPower(3)), 1.0E-12);
    }

    @Test
    void groundAndTargetQueries()
    {
        // A flat pearl leaves at y 1.52 and loses 0.0297 blocks on the first tick, so it is on the ground in ten:
        // 0.0297 * (1 + 1.99 + 2.9701 + ...) reaches 1.52 after ten ticks, having flown 1.5 * 9.4661746 blocks.
        ProjectileSim pearl = standingPearl();
        double[] landing = new double[3];
        assertTrue(pearl.groundPoint(0.0, 200, landing));
        assertEquals(10, pearl.tickReaching(0.0, 200));
        assertEquals(0.0, landing[0], TOL);
        assertTrue(landing[1] <= 0.0 && landing[1] > -0.5, "it stops just below the surface");
        assertEquals(1.5 * 9.4661745750, landing[2], 1.0E-8);
        // Fired straight up it never comes down inside a short budget.
        ProjectileSim up = new ProjectileSim(ProjectileSim.handLaunch(ProjectileSim.Kind.PEARL, 0.0, 0.0, 0.0,
                0.0, -90.0, 0.0, 0.0, 0.0, true));
        assertEquals(-1, up.tickReaching(0.0, 12));
    }

    @Test
    void segmentBoxTest()
    {
        ProjectileSim.Target target = new ProjectileSim.Target();
        target.x = 5.0;
        assertTrue(ProjectileSim.segmentSlab(4.0, 1.0, 0.0, 6.0, 1.0, 0.0, 5.0, 0.0, 0.0, 0.3, 1.8));
        assertFalse(ProjectileSim.segmentSlab(4.0, 1.0, 0.0, 6.0, 1.0, 0.0, 5.0, 0.0, 3.0, 0.3, 1.8));
        // Passing overhead misses, passing at body height hits.
        assertFalse(ProjectileSim.segmentSlab(4.0, 3.0, 0.0, 6.0, 3.0, 0.0, 5.0, 0.0, 0.0, 0.3, 1.8));
    }
}