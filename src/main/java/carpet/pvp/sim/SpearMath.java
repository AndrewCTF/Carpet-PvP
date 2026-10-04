package carpet.pvp.sim;

/**
 * The reach and the damage of a spear thrust, both read out of the game.
 *
 * <p>A spear is charged by holding the use key, which starts no timer of its own; the thrust happens on every
 * tick of that charge instead. {@code KineticWeapon.damageEntities} sweeps the entities the view reaches along
 * the reach of the weapon, works out how fast the two bodies close on each other along the look direction, and
 * adds that speed times a per material multiplier to the attacker's plain base damage. Nothing about the swing
 * is aimed by a click, so the bot's reach and the bot's closing speed decide everything.</p>
 *
 * <p>The reach is not the sword's: a spear reaches further out and has a near limit too, since a thrust needs
 * room. Both numbers come from the {@code ATTACK_RANGE} component that {@code Item.Properties.spear} puts on every
 * spear, with the hitbox margin the reach test adds.</p>
 */
public final class SpearMath
{
    /** {@code Item.Properties.spear} gives every spear the same reach component. */
    public static final double MIN_REACH = 2.0;
    public static final double MAX_REACH = 4.5;
    /** {@code AttackRange.hitboxMargin}, which widens the window on both sides. */
    public static final double HITBOX_MARGIN = 0.125;
    /** {@code KineticWeapon.contactCooldownTicks}: the same fighter cannot be stabbed again this soon. */
    public static final int CONTACT_COOLDOWN = 10;

    private SpearMath()
    {
    }

    /** Closest eye to hitbox distance a survival player can stab at. */
    public static double minReach()
    {
        return MIN_REACH - HITBOX_MARGIN;
    }

    /** Furthest eye to hitbox distance a survival player can stab at. */
    public static double maxReach()
    {
        return MAX_REACH + HITBOX_MARGIN;
    }

    /**
     * Mirrors {@code AttackRange.isInRange} for a survival player and a box: the distance from the eyes to the
     * closest point of the box has to sit inside the reach window, near side included.
     */
    public static boolean inReach(double eyeX, double eyeY, double eyeZ,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        double dx = Math.max(Math.max(minX - eyeX, eyeX - maxX), 0.0);
        double dy = Math.max(Math.max(minY - eyeY, eyeY - maxY), 0.0);
        double dz = Math.max(Math.max(minZ - eyeZ, eyeZ - maxZ), 0.0);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance >= minReach() && distance <= maxReach();
    }

    /**
     * The component of a velocity along the direction a yaw and a pitch look in, blocks per tick. Same
     * direction as {@link ProjectileAim#direction}, since both come from the look vector of the entity.
     */
    public static double along(double vx, double vy, double vz, double yawDegrees, double pitchDegrees)
    {
        double[] direction = ProjectileAim.direction(yawDegrees, pitchDegrees);
        return vx * direction[0] + vy * direction[1] + vz * direction[2];
    }

    /**
     * How fast the two bodies close on each other along the look direction. The game takes the attacker's
     * motion along the view minus the victim's, and never lets a victim running away from the stab count.
     */
    public static double closingSpeed(double attackerAlong, double victimAlong)
    {
        return Math.max(0.0, attackerAlong - victimAlong);
    }

    /**
     * Mirrors the damage a landed thrust does: the attacker's base attack damage, which carries no item
     * modifiers, plus the closing speed times the material's multiplier, rounded down.
     */
    public static float thrustDamage(double baseAttackDamage, double closingSpeed, double damageMultiplier)
    {
        return (float) (baseAttackDamage + Math.floor(closingSpeed * damageMultiplier));
    }

    /**
     * Mirrors {@code KineticWeapon.Condition.test} for the gate the damage itself is behind: the thrust only
     * lands while the charge is young enough and the bodies are closing fast enough. The speed is scaled by one
     * for a player, which is the factor the game passes in, and it is a speed in blocks a second, because that
     * is what {@code KineticWeapon.getMotion} hands the condition.
     */
    public static boolean damages(int chargeTicks, int maxChargeTicks, double closingSpeed, double minClosingSpeed)
    {
        return chargeTicks <= maxChargeTicks && closingSpeed >= minClosingSpeed;
    }
}
