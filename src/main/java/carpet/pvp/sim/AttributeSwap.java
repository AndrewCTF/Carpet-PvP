package carpet.pvp.sim;

/**
 * What an attribute swap is worth: the mace in the hand on the tick of the hit, on the base damage and the
 * already charged cooldown of whatever the hand held before it.
 *
 * <p>The game only re-applies an item's attribute modifiers in {@code LivingEntity.tick} through
 * {@code detectEquipmentUpdates}, and only zeroes the swing timer in {@code Player.tick} when the main hand
 * item changed. Both happen once per tick, after the action pack has already run, so a fighter that selects
 * another hotbar slot and swings in the same tick attacks with the new item's enchantments, fall bonus,
 * Breach and shield disabling on top of the old item's {@code ATTACK_DAMAGE} and its {@code ATTACK_SPEED}
 * charge. That is what these numbers price: a sword recharges in a third of the time a mace does and its
 * base damage is two points higher, so a fall smashed off a sword is both harder and ready sooner.</p>
 *
 * <p>Nothing here needs a world, so the self-test can hold the game against the same numbers.</p>
 */
public final class AttributeSwap
{
    /** A netherite sword's ATTACK_DAMAGE modifier of 7 plus the 1 of an empty hand, and its attack speed of 1.6. */
    public static final double SWORD_BASE_DAMAGE = 8.0;
    public static final double SWORD_ATTACK_SPEED = 1.6;
    /** A netherite axe's ATTACK_DAMAGE modifier of 9 plus the 1 of an empty hand, and its attack speed of 1.0. */
    public static final double AXE_BASE_DAMAGE = 10.0;
    public static final double AXE_ATTACK_SPEED = 1.0;
    /** Damage a ground hit has to gain from Breach before the swap is worth the hotbar change it costs. */
    public static final float MIN_BREACH_GAIN = 0.4F;

    private AttributeSwap()
    {
    }

    /** Ticks between two full strength hits when the cooldown is collected under the given item. */
    public static int cadenceTicks(double attackSpeed)
    {
        return (int) Math.ceil(CombatMath.fullChargeTicks(attackSpeed));
    }

    /**
     * The damage a swing lands with, made with the mace in the hand and everything that comes off the item
     * carried by the mace, on the base damage and the charge of the item the hand held before the swap.
     * Mirrors {@code Player.attack}: the base damage alone is charge scaled, the item's own fall bonus is
     * added on top of it, a crit multiplies the sum and the enchantment bonus is added last and scaled on its
     * own.
     */
    public static float damage(double baseDamage, double attackSpeed, int ticksSinceSwing, boolean critical,
            double fallDistance, int densityLevel, float enchantBonus, float armor, float toughness,
            int breachLevel, float epf)
    {
        float scale = CombatMath.chargeScale(ticksSinceSwing, attackSpeed);
        float bonus = CombatMath.maceSmashBonus(fallDistance, false, densityLevel);
        float raw = CombatMath.attackDamage((float) baseDamage, bonus, enchantBonus, scale, critical);
        return CombatMath.damageAfterDefences(raw, armor, toughness, breachLevel, epf);
    }

    /**
     * True when a hit that swaps the mace in on the tick it lands beats the same hit made with the mace held
     * through, which is what it has to be worth for the technique to be used at all. The swap can only lose
     * on the fall bonus and the enchantments it leaves behind; what it wins is the base damage and the charge
     * of the other item, so a long fall against a light target is where holding the mace wins and every other
     * case is where swapping wins.
     */
    public static boolean beatsHeld(double chargeDamage, double chargeSpeed, int chargeTicks, boolean critical,
            double fallDistance, int densityLevel, float enchantBonus, float armor, float toughness, float epf,
            double maceDamage, double maceSpeed, int maceTicks)
    {
        return damage(chargeDamage, chargeSpeed, chargeTicks, critical, fallDistance, densityLevel, enchantBonus,
                armor, toughness, 0, epf)
                > damage(maceDamage, maceSpeed, maceTicks, critical, fallDistance, densityLevel, enchantBonus,
                armor, toughness, 0, epf);
    }

    /**
     * How much the Breach enchantment adds to a hit against this armour and protection, which is the whole
     * reason to put a Breach mace in the hand on the tick of a ground hit and take it straight back out.
     * Breach takes 0.15 off the armour fraction per level before the protection is applied, so the gain grows
     * with the raw damage and the armour and vanishes on an unarmoured target.
     */
    public static float breachGain(double baseDamage, double attackSpeed, int ticksSinceSwing, boolean critical,
            float enchantBonus, float armor, float toughness, int breachLevel, float epf)
    {
        if (breachLevel <= 0)
        {
            return 0.0F;
        }
        float scale = CombatMath.chargeScale(ticksSinceSwing, attackSpeed);
        float raw = CombatMath.attackDamage((float) baseDamage, 0.0F, enchantBonus, scale, critical);
        return CombatMath.damageAfterDefences(raw, armor, toughness, breachLevel, epf)
                - CombatMath.damageAfterDefences(raw, armor, toughness, 0, epf);
    }

    /** Whether a ground hit on this armour is worth putting the Breach mace in the hand for. */
    public static boolean preferBreach(double baseDamage, double attackSpeed, int ticksSinceSwing,
            float enchantBonus, float armor, float toughness, int breachLevel, float epf)
    {
        return breachGain(baseDamage, attackSpeed, ticksSinceSwing, false, enchantBonus, armor, toughness,
                breachLevel, epf) >= MIN_BREACH_GAIN;
    }
}
