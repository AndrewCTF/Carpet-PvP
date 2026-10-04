package carpet.pvp.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;
import org.junit.jupiter.api.Test;

class SmpAimTest
{
    private static ProjectileAim.Shooter standing(double x, double y, double z, double vx, double vz)
    {
        ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
        shooter.x = x;
        shooter.y = y;
        shooter.z = z;
        shooter.vx = vx;
        shooter.vz = vz;
        shooter.onGround = true;
        return shooter;
    }

    @Test
    void aSplashMeantForTheOwnFeetLandsThere()
    {
        // Standing on flat ground at y = 0 with the head at the usual 1.62, the solve has to send the
        // potion down at the feet. Whatever pitch it comes back with, flying it has to land within
        // reach of the bot, or the bot would heal a spot it is not standing in.
        ProjectileAim.Shooter shooter = standing(0.5D, 0.0D, 0.5D, 0.0D, 0.0D);
        double pitch = SmpAim.feetPitch(shooter, 0.0D, 0.0D);
        assertTrue(!Double.isNaN(pitch), "there was no throw that lands on its own feet");
        assertTrue(pitch > 40.0D, "a potion dropped at the feet is thrown steeply down, was " + pitch);

        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.SPLASH_POTION, shooter.x,
                shooter.y, shooter.z, 0.0D, pitch, shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
        ProjectileSim flight = new ProjectileSim(launch);
        double[] where = new double[3];
        assertTrue(flight.groundPoint(0.0D, SmpAim.THROW_LIMIT, where), "the throw never came down");
        double off = ProjectileAim.boxDistance(where[0], where[1], where[2], shooter.x, 0.0D, shooter.z,
                0.0D, 0.0D);
        assertTrue(off <= SmpAim.FEET_TOLERANCE, "the throw landed " + off + " blocks from its own feet");
    }

    @Test
    void theOwnFeetSolveKeepsUpWithABotThatIsWalking()
    {
        // A bot that throws while it walks has to compensate for its own motion, which is what the
        // shooter fields are for; the landing still has to be its own feet, not the ones of a moment ago.
        ProjectileAim.Shooter shooter = standing(0.5D, 0.0D, 0.5D, 0.0D, 0.13D);
        double pitch = SmpAim.feetPitch(shooter, 0.0D, 0.0D);
        assertTrue(!Double.isNaN(pitch));
        ProjectileSim.Launch launch = ProjectileSim.handLaunch(ProjectileSim.Kind.SPLASH_POTION, shooter.x,
                shooter.y, shooter.z, 0.0D, pitch, shooter.vx, shooter.vy, shooter.vz, shooter.onGround);
        ProjectileSim flight = new ProjectileSim(launch);
        double[] where = new double[3];
        assertTrue(flight.groundPoint(0.0D, SmpAim.THROW_LIMIT, where));
        assertTrue(ProjectileAim.boxDistance(where[0], where[1], where[2], shooter.x, 0.0D, shooter.z,
                0.0D, 0.0D) <= SmpAim.FEET_TOLERANCE);
    }

    @Test
    void aPeelAwayThrowLandsAboutAsFarBackAsItAskedFor()
    {
        // The bot stands at x = 10 and the target it is running from is at x = 0, so the throw has to
        // land towards +x. Twelve blocks of room is inside what a pearl can throw, and a whole tick
        // launch can only come as close to the point as a tick of flight allows, a little past it.
        ProjectileAim.Shooter shooter = standing(10.0D, 0.0D, 0.5D, 0.0D, 0.0D);
        ProjectileAim.Aim aim = SmpAim.peelAway(shooter, 0.0D, 0.5D, 0.0D, 12.0D);
        assertTrue(aim.solved, "no pearl reached");
        assertEquals(-90.0D, aim.yaw, 1.0E-6D);
        double[] where = new double[3];
        int ticks = SmpAim.landing(shooter, aim.yaw, aim.pitch, 0.0D, where);
        assertTrue(ticks > 0, "the pearl stayed up forever");
        double room = Math.hypot(where[0] - shooter.x, where[2] - shooter.z);
        assertTrue(room > 10.0D && room < 15.0D, "the pearl bought " + room + " blocks of room");
        assertTrue(where[0] > shooter.x, "the pearl went towards the target instead of away from it");
    }

    @Test
    void aPeelAwayThrowThatIsNotAskedForDoesNotSolve()
    {
        assertEquals(ProjectileAim.OUT_OF_RANGE, SmpAim.peelAway(standing(0.0D, 0.0D, 0.0D, 0.0D, 0.0D),
                0.0D, 0.0D, 0.0D, 0.0D));
        // Standing on the target there is no line to run away along.
        assertEquals(ProjectileAim.OUT_OF_RANGE, SmpAim.peelAway(standing(0.0D, 0.0D, 0.0D, 0.0D, 0.0D),
                0.0D, 0.0D, 0.0D, 12.0D));
    }

    @Test
    void aThrowThatWouldHaveToClimbIsNotAttempted()
    {
        // The target the bot is running from is far below it, so no pearl thrown down from here lands
        // on the ground at its feet: the solve either finds nothing or finds a lob that misses.
        ProjectileAim.Shooter shooter = standing(10.0D, 120.0D, 0.5D, 0.0D, 0.0D);
        ProjectileAim.Aim aim = SmpAim.peelAway(shooter, 0.0D, 0.5D, 0.0D, 12.0D);
        assertTrue(!aim.solved || aim.apex < 120.0D);
    }

    @Test
    void lookingAtAPointGivesTheYawAndPitchOfTheView()
    {
        double[] out = new double[2];
        // Along +z, which is yaw zero in the Minecraft convention.
        SmpAim.lookAt(0.0D, 1.62D, 0.0D, 0.0D, 1.62D, 10.0D, out);
        assertEquals(0.0D, out[0], 1.0E-6D);
        assertEquals(0.0D, out[1], 1.0E-6D);
        // Along +x, which is yaw minus ninety.
        SmpAim.lookAt(0.0D, 1.62D, 0.0D, 10.0D, 1.62D, 0.0D, out);
        assertEquals(-90.0D, out[0], 1.0E-6D);
        // Looking down at the ground under the eyes is a positive pitch.
        SmpAim.lookAt(0.0D, 1.62D, 0.0D, 0.0D, 0.0D, 0.0D, out);
        assertTrue(out[1] > 80.0D, "a point straight below is straight down, was " + out[1]);
    }
}