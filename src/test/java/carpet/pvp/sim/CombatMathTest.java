package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatMathTest
{
    @Test
    void minTicksForGate()
    {
        assertEquals(11, CombatMath.minTicksForGate(1.6));
        assertEquals(18, CombatMath.minTicksForGate(1.0));
        assertEquals(30, CombatMath.minTicksForGate(0.6));
        assertEquals(30, CombatMath.minTicksForGate(4.0 - 3.4000000953674316));
    }

    @Test
    void chargeScale()
    {
        assertEquals(12.5f, CombatMath.fullChargeTicks(1.6), 1e-5f);
        assertEquals(0.5f / 12.5f, CombatMath.chargeScale(0, 1.6), 1e-6f);
        assertEquals(1.0f, CombatMath.chargeScale(100, 1.6));
        assertEquals(0.2f, CombatMath.chargeDamageFactor(0.0f), 1e-6f);
        assertEquals(1.0f, CombatMath.chargeDamageFactor(1.0f), 1e-6f);
        assertFalse(CombatMath.passesChargeGate(CombatMath.chargeScale(10, 1.6)));
        assertTrue(CombatMath.passesChargeGate(CombatMath.chargeScale(11, 1.6)));
        assertFalse(CombatMath.passesChargeGate(0.9f));
        assertTrue(CombatMath.passesChargeGate(Math.nextUp(0.9f)));
    }

    @Test
    void critical()
    {
        assertTrue(crit(true, false, false, false, false, false, true, false, true));
        assertFalse(crit(false, false, false, false, false, false, true, false, true));
        assertFalse(crit(true, true, false, false, false, false, true, false, true));
        assertFalse(crit(true, false, true, false, false, false, true, false, true));
        assertFalse(crit(true, false, false, true, false, false, true, false, true));
        assertFalse(crit(true, false, false, false, true, false, true, false, true));
        assertFalse(crit(true, false, false, false, false, true, true, false, true));
        assertFalse(crit(true, false, false, false, false, false, false, false, true));
        assertFalse(crit(true, false, false, false, false, false, true, true, true));
        assertFalse(crit(true, false, false, false, false, false, true, false, false));
        assertEquals(1.5f, CombatMath.CRIT_MULTIPLIER);
    }

    private static boolean crit(boolean a, boolean b, boolean c, boolean d, boolean e, boolean f, boolean g,
                                boolean h, boolean i)
    {
        return CombatMath.isCritical(a, b, c, d, e, f, g, h, i);
    }

    @Test
    void attackDamage()
    {
        assertEquals(7.0f, CombatMath.attackDamage(7.0f, 0.0f, 0.0f, 1.0f, false), 1e-5f);
        assertEquals(10.5f, CombatMath.attackDamage(7.0f, 0.0f, 0.0f, 1.0f, true), 1e-5f);
        assertEquals(1.4f, CombatMath.attackDamage(7.0f, 0.0f, 0.0f, 0.0f, false), 1e-5f);
        assertEquals(10.5f + 3.0f, CombatMath.attackDamage(7.0f, 0.0f, 3.0f, 1.0f, true), 1e-5f);
    }

    @Test
    void maceSmash()
    {
        double[][] cases = {
            {Math.nextUp(1.5), 12, 15.75}, {3, 18, 25.5}, {8, 28, 48}, {20, 40, 90}
        };
        for (double[] c : cases)
        {
            assertEquals(c[1], 6.0 + CombatMath.maceSmashBonus(c[0], false, 0), 1e-4, "fd " + c[0]);
            assertEquals(c[2], 6.0 + CombatMath.maceSmashBonus(c[0], false, 5), 1e-4, "density fd " + c[0]);
        }
        assertEquals(0.0f, CombatMath.maceSmashBonus(1.5, false, 0));
        assertEquals(0.0f, CombatMath.maceSmashBonus(1.5, false, 5));
        assertEquals(0.0f, CombatMath.maceSmashBonus(2.0, true, 0));
        assertEquals(0.0f, CombatMath.maceSmashBonus(1.0, false, 5));
        assertEquals(0.0f, CombatMath.maceSmashBonus(20, true, 5));
        assertTrue(CombatMath.maceSmashBonus(Math.nextUp(1.5), false, 0) > 0.0f);
        assertEquals(12.0f, CombatMath.maceSmashBonus(3.0, false, 0), 1e-5f);
        assertEquals(22.0f, CombatMath.maceSmashBonus(8.0, false, 0), 1e-5f);
    }

    @Test
    void explosion()
    {
        double[] crystalDist = {0, 2, 4, 6, 8};
        int[] crystal = {85, 65, 47, 32, 19};
        int[] anchor = {71, 51, 34, 20, 9};
        for (int i = 0; i < crystalDist.length; i++)
        {
            assertEquals(crystal[i], Math.floor(CombatMath.explosionDamage(crystalDist[i], 6.0f, 1.0f)), "crystal " + crystalDist[i]);
            assertEquals(anchor[i], Math.floor(CombatMath.explosionDamage(crystalDist[i], 5.0f, 1.0f)), "anchor " + crystalDist[i]);
        }
        assertEquals(85.0f, CombatMath.explosionDamage(0, 6.0f, 1.0f), 1e-3f);
        assertEquals(65.16f, CombatMath.explosionDamage(2, 6.0f, 1.0f), 0.01f);
        assertEquals(1.0f, CombatMath.explosionDamage(12.0, 6.0f, 1.0f), 1e-5f);
        assertEquals(0.0f, CombatMath.explosionDamage(12.001, 6.0f, 1.0f));
        assertEquals(1.0f, CombatMath.explosionDamage(10.0, 5.0f, 1.0f), 1e-5f);
        assertEquals(0.0f, CombatMath.explosionDamage(10.01, 5.0f, 1.0f));
        assertEquals(1.0f, CombatMath.explosionDamage(0, 6.0f, 0.0f), 1e-5f);
    }

    @Test
    void difficulty()
    {
        assertEquals(0.0f, CombatMath.playerDifficultyScale(10, CombatMath.PEACEFUL));
        assertEquals(6.0f, CombatMath.playerDifficultyScale(10, CombatMath.EASY));
        assertEquals(1.0f, CombatMath.playerDifficultyScale(1, CombatMath.EASY));
        assertEquals(10.0f, CombatMath.playerDifficultyScale(10, CombatMath.NORMAL));
        assertEquals(15.0f, CombatMath.playerDifficultyScale(10, CombatMath.HARD));
    }

    @Test
    void armorAndProtection()
    {
        float afterArmor = CombatMath.armorAbsorb(85.0f, 20.0f, 12.0f, 0);
        assertEquals(71.4f, afterArmor, 1e-3f);
        assertEquals(25.7f, CombatMath.protectionAbsorb(afterArmor, 16.0f), 0.01f);
        assertEquals(14.28f, CombatMath.protectionAbsorb(afterArmor, 32.0f), 1e-3f);
        assertEquals(CombatMath.protectionAbsorb(afterArmor, 20.0f), CombatMath.protectionAbsorb(afterArmor, 99.0f));
        assertEquals(afterArmor, CombatMath.protectionAbsorb(afterArmor, 0.0f));
        assertEquals(afterArmor, CombatMath.protectionAbsorb(afterArmor, -5.0f));
        assertEquals(14.28f, CombatMath.damageAfterDefences(85.0f, 20.0f, 12.0f, 0, 32.0f), 1e-3f);
        assertEquals(12.0f, CombatMath.armorAbsorb(20.0f, 20.0f, 0.0f, 0), 1e-4f);
        assertEquals(1.0f * (1.0f - 0.8f), CombatMath.armorAbsorb(1.0f, 30.0f, 12.0f, 0), 1e-5f);
        assertEquals(100.0f * (1.0f - 0.2f * 20.0f / 25.0f), CombatMath.armorAbsorb(100.0f, 20.0f, 0.0f, 0), 1e-3f);
        assertEquals(4.0f, CombatMath.armorAbsorb(10.0f, 20.0f, 0.0f, 0), 1e-4f);
        assertEquals(5.5f, CombatMath.armorAbsorb(10.0f, 20.0f, 0.0f, 1), 1e-4f);
        assertEquals(10.0f, CombatMath.armorAbsorb(10.0f, 20.0f, 0.0f, 6), 1e-4f);
        assertEquals(10.0f, CombatMath.epf(4, 3, true));
        assertEquals(4.0f, CombatMath.epf(4, 3, false));
    }

    @Test
    void knockback()
    {
        assertArrayEquals(new double[] {-0.4, 0.4, 0.0},
            CombatMath.knockback(0.4, 1.0, 0.0, 0.0, true, 0, 0, 0), 1e-9);
        assertArrayEquals(new double[] {0.0, -0.3, -0.5},
            CombatMath.knockback(0.5, 0.0, 1.0, 0.0, false, 0.0, -0.3, 0.0), 1e-9);
        assertArrayEquals(new double[] {0.7, 0.35, 0.0},
            CombatMath.knockback(0.2, -1.0, 0.0, 0.0, true, 1.0, 0.3, 0.0), 1e-9);
        assertArrayEquals(new double[] {-0.24, 0.4, -0.32},
            CombatMath.knockback(0.4, 3.0, 4.0, 0.0, true, 0, 0, 0), 1e-9);
        assertArrayEquals(new double[] {2.0, 1.0, 2.0},
            CombatMath.knockback(0.4, 3.0, 4.0, 1.0, true, 2.0, 1.0, 2.0), 0);
        assertArrayEquals(new double[] {-0.2, 0.2, 0.0},
            CombatMath.knockback(0.4, 1.0, 0.0, 0.5, true, 0, 0, 0), 1e-9);
        assertEquals(0.4f, CombatMath.DEFAULT_HIT_KNOCKBACK);
    }

    @Test
    void sprintHit()
    {
        assertEquals(0.5f, CombatMath.extraKnockbackStrength(0.0f, true));
        assertEquals(0.0f, CombatMath.extraKnockbackStrength(0.0f, false));
        assertEquals(0.5f, CombatMath.extraKnockbackStrength(1.0f, false));
        assertEquals(1.5f, CombatMath.extraKnockbackStrength(2.0f, true));
        assertArrayEquals(new double[] {0.6, 0.5, -0.6},
            CombatMath.attackerVelocityAfterKnockback(0.5f, 1.0, 0.5, -1.0), 1e-12);
        assertArrayEquals(new double[] {1.0, 0.5, -1.0},
            CombatMath.attackerVelocityAfterKnockback(0.0f, 1.0, 0.5, -1.0), 0);
        assertFalse(CombatMath.attackerSprintingAfterKnockback(0.5f, true));
        assertTrue(CombatMath.attackerSprintingAfterKnockback(0.0f, true));
    }

    @Test
    void invulnerability()
    {
        assertEquals(10.0f, CombatMath.invulnerabilityDamage(10.0f, 0.0f, 0));
        assertEquals(10.0f, CombatMath.invulnerabilityDamage(10.0f, 8.0f, 10));
        assertEquals(2.0f, CombatMath.invulnerabilityDamage(10.0f, 8.0f, 11));
        assertEquals(2.0f, CombatMath.invulnerabilityDamage(10.0f, 8.0f, 20));
        assertEquals(0.0f, CombatMath.invulnerabilityDamage(8.0f, 8.0f, 15));
        assertEquals(0.0f, CombatMath.invulnerabilityDamage(5.0f, 8.0f, 15));
    }
}
