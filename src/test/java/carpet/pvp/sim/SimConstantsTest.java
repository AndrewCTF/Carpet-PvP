package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the game constants the combat models are built on.
 *
 * <p>Every value here was read out of the Minecraft jars of all three supported versions
 * (26.3, 26.2 and 1.21.11) and is the same in each of them, so the models need no
 * version branches. If one of these assertions ever needs a per-version value, the
 * constant moves behind a Stonecutter condition and the test gets a matching branch.
 */
class SimConstantsTest
{
    @Test
    void attackChargeAndCrit()
    {
        // Player.baseDamageScaleFactor: 0.2f + scale * scale * 0.8f
        assertEquals(0.2f, CombatMath.chargeDamageFactor(0.0f), 1e-6f);
        assertEquals(1.0f, CombatMath.chargeDamageFactor(1.0f), 1e-6f);
        // Player.getCurrentItemAttackStrengthDelay: 1 / attackSpeed * 20
        assertEquals(20.0f, CombatMath.fullChargeTicks(1.0), 1e-4f);
        // the scale > 0.9f gate in Player.attack
        assertEquals(0.9f, CombatMath.CHARGE_GATE, 1e-6f);
        // the crit multiplier in Player.attack
        assertEquals(1.5f, CombatMath.CRIT_MULTIPLIER, 1e-6f);
    }

    @Test
    void knockback()
    {
        // the 0.4f base knockback of a hit and the 0.5f sprint extra of Player.attack
        assertEquals(0.4f, CombatMath.DEFAULT_HIT_KNOCKBACK, 1e-6f);
        assertEquals(0.5f, CombatMath.SPRINT_KNOCKBACK_BONUS, 1e-6f);
        // Player.causeExtraKnockback scales the attacker's horizontal velocity by 0.6
        assertEquals(0.6, CombatMath.ATTACKER_HORIZONTAL_FACTOR, 1e-6);
        assertEquals(1.0f, CombatMath.extraKnockbackStrength(1.0f, true), 1e-6f);
        assertEquals(0.5f, CombatMath.extraKnockbackStrength(1.0f, false), 1e-6f);
    }

    @Test
    void invulnerability()
    {
        // LivingEntity sets invulnerableTime to 20 on a hit and compares against lastHurt above 10
        assertEquals(20, CombatMath.INVULNERABLE_TICKS_AFTER_HIT);
        assertEquals(10, CombatMath.INVULNERABLE_WINDOW_THRESHOLD);
    }

    @Test
    void maceSmash()
    {
        // MaceItem.canSmashAttack needs a fall distance above 1.5 and no gliding
        assertEquals(1.5f, CombatMath.SMASH_FALL_THRESHOLD, 1e-6f);
        assertEquals(4.0f * 2.0f, CombatMath.maceSmashBonus(2.0, false, 0), 1e-4f);
        assertEquals(12.0 + 2.0 * 5.0, CombatMath.maceSmashBonus(8.0, false, 0), 1e-4f);
        assertEquals(22.0 + 1.0, CombatMath.maceSmashBonus(9.0, false, 0), 1e-4f);
        assertEquals(0.0f, CombatMath.maceSmashBonus(9.0, true, 3), 1e-6f);
    }

    @Test
    void explosionDamage()
    {
        // ExplosionDamageCalculator: ((1 - d / (2 * power)) ^ 2 + (1 - d / (2 * power))) / 2 * 7 * (2 * power) + 1
        assertEquals(57.0f, CombatMath.explosionDamage(0.0, 4.0f, 1.0f), 1e-4f);
        assertEquals(0.0f, CombatMath.explosionDamage(9.0, 4.0f, 1.0f), 1e-6f);
    }

    @Test
    void difficultyScaling()
    {
        assertEquals(0.0f, CombatMath.playerDifficultyScale(10.0f, CombatMath.PEACEFUL), 1e-6f);
        assertEquals(6.0f, CombatMath.playerDifficultyScale(10.0f, CombatMath.EASY), 1e-6f);
        assertEquals(10.0f, CombatMath.playerDifficultyScale(10.0f, CombatMath.NORMAL), 1e-6f);
        assertEquals(15.0f, CombatMath.playerDifficultyScale(10.0f, CombatMath.HARD), 1e-6f);
    }

    @Test
    void armourAndProtection()
    {
        // CombatRules divides by 2 + toughness / 4, clamps to [armor * 0.2, 20] and scales by 25
        assertEquals(10.0f, CombatMath.armorAbsorb(10.0f, 0.0f, 0.0f, 0), 1e-6f);
        assertEquals(4.0f, CombatMath.armorAbsorb(10.0f, 20.0f, 0.0f, 0), 1e-4f);
        // Breach takes 0.15 per level off the armor fraction
        assertEquals(5.5f, CombatMath.armorAbsorb(10.0f, 20.0f, 0.0f, 1), 1e-4f);
        assertEquals(10.0f * 0.2f, CombatMath.protectionAbsorb(10.0f, 20.0f), 1e-4f);
        assertEquals(2.0f, CombatMath.epf(0, 1, true), 1e-6f);
    }

    @Test
    void projectilePhysics()
    {
        // ThrowableProjectile gravity and inertia, AbstractArrow water drag, potion roll
        assertEquals(0.03, ProjectileSim.THROWABLE_GRAVITY, 1e-9);
        assertEquals(0.05, ProjectileSim.POTION_GRAVITY, 1e-9);
        assertEquals(0.05, ProjectileSim.ARROW_GRAVITY, 1e-9);
        assertEquals(0.99, ProjectileSim.AIR_DRAG, 1e-9);
        assertEquals(0.8, ProjectileSim.THROWABLE_WATER_DRAG, 1e-9);
        assertEquals(0.6, ProjectileSim.ARROW_WATER_DRAG, 1e-9);
        assertEquals(0.99, ProjectileSim.TRIDENT_WATER_DRAG, 1e-9);
        assertEquals(0.95, ProjectileSim.HURTING_INERTIA, 1e-9);
        assertEquals(0.8, ProjectileSim.HURTING_LIQUID_INERTIA, 1e-9);
        assertEquals(1.62, ProjectileSim.EYE_HEIGHT, 1e-9);
        assertEquals(1.5, ProjectileSim.PEARL_SPEED, 1e-9);
        assertEquals(0.5, ProjectileSim.POTION_SPEED, 1e-9);
        assertEquals(-20.0, ProjectileSim.POTION_ROLL, 1e-9);
    }

    @Test
    void armourPieceValues()
    {
        // the per-piece multipliers, defence points, toughness and mending
        assertEquals(33, Durability.DIAMOND_MULTIPLIER);
        assertEquals(37, Durability.NETHERITE_MULTIPLIER);
        assertEquals(8, Durability.NETHERITE_DEFENCE[Durability.HEAD]);
        assertEquals(3.0f, Durability.NETHERITE_TOUGHNESS, 1e-6f);
        assertEquals(0.1f, Durability.NETHERITE_KNOCKBACK_RESISTANCE, 1e-6f);
        assertEquals(2, Durability.MENDING_DURABILITY_PER_POINT);
    }
}