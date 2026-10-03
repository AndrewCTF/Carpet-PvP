package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoubleTapTest
{
    /**
     * LivingEntity.hurtServer puts damageCooldownTime at 20 after a fresh hit and ServerPlayer.tick takes one
     * off it every tick, and it only applies a hit whole while that value is at most
     * CombatMath.INVULNERABLE_WINDOW_THRESHOLD = 10. So the window closes 20 - 10 = 10 ticks after the first
     * crystal and a follow-up crystal has to be that far behind the first one.
     */
    @Test
    void followUpNeedsTenTicks()
    {
        assertEquals(10, DoubleTap.earliestFullDamageTick());
        assertEquals(20, CombatMath.INVULNERABLE_TICKS_AFTER_HIT);
        assertEquals(10, CombatMath.INVULNERABLE_WINDOW_THRESHOLD);
    }

    /**
     * Two crystals next to each other both do 21 damage. Nine ticks after the first the cooldown is still 11,
     * so the second only applies the 21 - 21 = 0 it adds. Ten ticks after the first the cooldown is 10 and
     * the second lands whole.
     */
    @Test
    void secondCrystalIsWastedUntilTheWindowCloses()
    {
        assertEquals(0.0f, DoubleTap.followUpDamage(21.0f, 21.0f, 9));
        assertEquals(21.0f, DoubleTap.followUpDamage(21.0f, 21.0f, 10));
        assertEquals(21.0f, DoubleTap.followUpDamage(21.0f, 21.0f, 11));
        assertEquals(0.0f, DoubleTap.followUpDamage(21.0f, 21.0f, 0));
    }

    /** Spacing the crystals so the second one does more than the first applies only the difference. */
    @Test
    void onlyTheExcessLands()
    {
        assertEquals(0.0f, DoubleTap.followUpDamage(20.0f, 30.0f, 5));
        assertEquals(0.0f, DoubleTap.followUpDamage(30.0f, 30.0f, 5));
        assertEquals(11.0f, DoubleTap.followUpDamage(41.0f, 30.0f, 5));
        assertEquals(39.0f, DoubleTap.followUpDamage(39.0f, 30.0f, 19), "the window closed long before");
        assertEquals(41.0f, DoubleTap.followUpDamage(41.0f, 30.0f, 20));
    }

    /**
     * A crystal 2 blocks away does 65 and one 6 blocks away does 32, so a bot that chases the target with a
     * second crystal nine ticks later gets nothing at all for it, and only lands both when it waits the ten
     * ticks the cooldown needs.
     */
    @Test
    void spacedCrystalsBothLand()
    {
        float near = CombatMath.explosionDamage(2.0, 6.0f, 1.0f);
        float far = CombatMath.explosionDamage(6.0, 6.0f, 1.0f);
        assertTrue(near > far);
        assertEquals(0.0f, DoubleTap.followUpDamage(far, near, DoubleTap.earliestFullDamageTick() - 1));
        assertEquals(far, DoubleTap.followUpDamage(far, near, DoubleTap.earliestFullDamageTick()), 1e-6f);
        assertFalse(DoubleTap.earliestFullDamageTick() < 1);
    }
}
