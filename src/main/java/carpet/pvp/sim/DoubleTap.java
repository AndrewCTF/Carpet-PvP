package carpet.pvp.sim;

/**
 * The damage cooldown of LivingEntity.hurtServer: a hit that lands while the cooldown is still above
 * CombatMath.INVULNERABLE_WINDOW_THRESHOLD only applies the excess over the last hit, so a second crystal
 * has to wait for the window to close before it lands for its full amount.
 */
public final class DoubleTap
{
    private DoubleTap()
    {
    }

    /** Ticks after a full hit at which a second hit of the same size lands for its full amount. */
    public static int earliestFullDamageTick()
    {
        return CombatMath.INVULNERABLE_TICKS_AFTER_HIT - CombatMath.INVULNERABLE_WINDOW_THRESHOLD;
    }

    /** What a hit of {@code incoming} damage does {@code ticksAfterFirstHit} ticks after a hit of {@code firstHit}. */
    public static float followUpDamage(float incoming, float firstHit, int ticksAfterFirstHit)
    {
        int cooldown = CombatMath.INVULNERABLE_TICKS_AFTER_HIT - Math.max(0, ticksAfterFirstHit);
        return CombatMath.invulnerabilityDamage(incoming, firstHit, cooldown);
    }
}
