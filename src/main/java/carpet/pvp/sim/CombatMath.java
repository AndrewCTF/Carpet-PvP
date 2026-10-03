package carpet.pvp.sim;

public final class CombatMath
{
    public static final float CRIT_MULTIPLIER = 1.5f;
    public static final float CHARGE_GATE = 0.9f;
    public static final float DEFAULT_HIT_KNOCKBACK = 0.4f;
    public static final float SPRINT_KNOCKBACK_BONUS = 0.5f;
    public static final double ATTACKER_HORIZONTAL_FACTOR = 0.6;
    public static final int INVULNERABLE_TICKS_AFTER_HIT = 20;
    public static final int INVULNERABLE_WINDOW_THRESHOLD = 10;
    public static final float SMASH_FALL_THRESHOLD = 1.5f;

    public static final int PEACEFUL = 0;
    public static final int EASY = 1;
    public static final int NORMAL = 2;
    public static final int HARD = 3;

    private CombatMath()
    {
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }

    /** Mirrors Player.getCurrentItemAttackStrengthDelay. */
    public static float fullChargeTicks(double attackSpeed)
    {
        return (float) (1.0 / attackSpeed * 20.0);
    }

    /** Mirrors Player.getAttackStrengthScale(0.5f) after the given ticks since the last swing. */
    public static float chargeScale(int ticksSinceSwing, double attackSpeed)
    {
        return clamp((ticksSinceSwing + 0.5f) / fullChargeTicks(attackSpeed), 0.0f, 1.0f);
    }

    /** Mirrors Player.baseDamageScaleFactor. */
    public static float chargeDamageFactor(float scale)
    {
        return 0.2f + scale * scale * 0.8f;
    }

    /** Mirrors the scale > 0.9f gate in Player.attack that guards crits and sprint knockback. */
    public static boolean passesChargeGate(float scale)
    {
        return scale > CHARGE_GATE;
    }

    /** Smallest tick count whose charge scale passes the Player.attack gate. */
    public static int minTicksForGate(double attackSpeed)
    {
        int ticks = 0;
        while (!passesChargeGate(chargeScale(ticks, attackSpeed)))
        {
            ticks++;
        }
        return ticks;
    }

    /** Mirrors Player.canCriticalAttack combined with the charge gate in Player.attack. */
    public static boolean isCritical(boolean falling, boolean onGround, boolean climbing, boolean inWater,
                                     boolean blind, boolean passenger, boolean targetIsLiving,
                                     boolean sprinting, boolean chargeGatePassed)
    {
        return chargeGatePassed && falling && !onGround && !climbing && !inWater && !blind
                && !passenger && targetIsLiving && !sprinting;
    }

    /** Mirrors the damage assembly in Player.attack: only the base damage is charge scaled, the enchant bonus is scaled separately. */
    public static float attackDamage(float baseDamage, float weaponBonus, float enchantBonus, float scale, boolean critical)
    {
        float scaledBase = baseDamage * chargeDamageFactor(scale);
        float total = scaledBase + weaponBonus;
        if (critical)
        {
            total *= CRIT_MULTIPLIER;
        }
        return total + enchantBonus * scale;
    }

    /** Mirrors MaceItem.canSmashAttack. */
    public static boolean canSmash(double fallDistance, boolean gliding)
    {
        return fallDistance > SMASH_FALL_THRESHOLD && !gliding;
    }

    /** Mirrors MaceItem.getAttackDamageBonus; density adds 0.5 per level per fallen block. */
    public static float maceSmashBonus(double fallDistance, boolean gliding, int densityLevel)
    {
        if (!canSmash(fallDistance, gliding))
        {
            return 0.0f;
        }
        double bonus;
        if (fallDistance <= 3.0)
        {
            bonus = 4.0 * fallDistance;
        }
        else if (fallDistance <= 8.0)
        {
            bonus = 12.0 + 2.0 * (fallDistance - 3.0);
        }
        else
        {
            bonus = 22.0 + fallDistance - 8.0;
        }
        return (float) (bonus + 0.5 * densityLevel * fallDistance);
    }

    /** Mirrors ExplosionDamageCalculator.getEntityDamageAmount, with ServerExplosion.hurtEntities skipping distance beyond twice the power; not floored in this version. */
    public static float explosionDamage(double distance, float power, float exposure)
    {
        float diameter = power * 2.0f;
        double ratio = distance / diameter;
        if (ratio > 1.0)
        {
            return 0.0f;
        }
        double impact = (1.0 - ratio) * exposure;
        return (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
    }

    /** Mirrors the scalesWithDifficulty block of Player.hurtServer. */
    public static float playerDifficultyScale(float damage, int difficulty)
    {
        if (difficulty == PEACEFUL)
        {
            return 0.0f;
        }
        if (difficulty == EASY)
        {
            return Math.min(damage / 2.0f + 1.0f, damage);
        }
        if (difficulty == HARD)
        {
            return damage * 3.0f / 2.0f;
        }
        return damage;
    }

    /** Mirrors CombatRules.getDamageAfterAbsorb, with Breach (armor_effectiveness -0.15 per level) applied to the armor fraction. */
    public static float armorAbsorb(float damage, float armor, float toughness, int breachLevel)
    {
        float divisor = 2.0f + toughness / 4.0f;
        float fraction = clamp(armor - damage / divisor, armor * 0.2f, 20.0f) / 25.0f;
        if (breachLevel > 0)
        {
            fraction = clamp(fraction - 0.15f * breachLevel, 0.0f, 1.0f);
        }
        return damage * (1.0f - fraction);
    }

    /** Mirrors CombatRules.getDamageAfterMagicAbsorb. */
    public static float protectionAbsorb(float damage, float epf)
    {
        return damage * (1.0f - clamp(epf, 0.0f, 20.0f) / 25.0f);
    }

    /** Enchantment protection factor: Protection 1 per level, Blast Protection 2 per level against explosions. */
    public static float epf(int protectionLevels, int blastProtectionLevels, boolean explosion)
    {
        return protectionLevels + (explosion ? 2.0f * blastProtectionLevels : 0.0f);
    }

    /** Mirrors LivingEntity.actuallyHurt: armor absorb then protection absorb. */
    public static float damageAfterDefences(float damage, float armor, float toughness, int breachLevel, float epf)
    {
        return protectionAbsorb(armorAbsorb(damage, armor, toughness, breachLevel), epf);
    }

    /** Mirrors LivingEntity.knockback(strength, dx, dz) and returns the new velocity as {x, y, z}. */
    public static double[] knockback(double strength, double dx, double dz, double resistance, boolean onGround,
                                     double vx, double vy, double vz)
    {
        double s = strength * (1.0 - resistance);
        if (s <= 0.0)
        {
            return new double[] {vx, vy, vz};
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        double nx = 0.0;
        double nz = 0.0;
        if (len >= 1.0E-5)
        {
            nx = dx / len * s;
            nz = dz / len * s;
        }
        double ny = onGround ? Math.min(0.4, vy / 2.0 + s) : vy;
        return new double[] {vx / 2.0 - nx, ny, vz / 2.0 - nz};
    }

    /** Mirrors LivingEntity.getKnockback plus the sprint bonus passed to Player.causeExtraKnockback. */
    public static float extraKnockbackStrength(float enchantedAttackKnockback, boolean sprintHit)
    {
        return enchantedAttackKnockback / 2.0f + (sprintHit ? SPRINT_KNOCKBACK_BONUS : 0.0f);
    }

    /** Mirrors Player.causeExtraKnockback: attacker velocity after an extra knockback of the given strength. */
    public static double[] attackerVelocityAfterKnockback(float strength, double vx, double vy, double vz)
    {
        if (strength > 0.0f)
        {
            return new double[] {vx * ATTACKER_HORIZONTAL_FACTOR, vy, vz * ATTACKER_HORIZONTAL_FACTOR};
        }
        return new double[] {vx, vy, vz};
    }

    /** Mirrors Player.causeExtraKnockback: sprinting is cleared whenever the strength is positive. */
    public static boolean attackerSprintingAfterKnockback(float strength, boolean sprinting)
    {
        return sprinting && !(strength > 0.0f);
    }

    /** Mirrors the invulnerableTime branch of LivingEntity.hurtServer: amount passed to actuallyHurt, 0 if the hit is rejected. */
    public static float invulnerabilityDamage(float incoming, float lastHurt, int invulnerableTime)
    {
        if (invulnerableTime > INVULNERABLE_WINDOW_THRESHOLD)
        {
            return incoming <= lastHurt ? 0.0f : incoming - lastHurt;
        }
        return incoming;
    }
}
