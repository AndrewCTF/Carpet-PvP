package carpet.pvp.mace;

import carpet.pvp.BotBody;
import carpet.pvp.sim.AttributeSwap;

/**
 * The ground breach swap: every charged hit is made with the Breach mace in the hand on the tick of the swing
 * and the charging item put back afterwards, so the hit goes through armour the mace has cut while keeping the
 * base damage, the charge and the rhythm of the item the hand was holding.
 *
 * <p>Breach takes 0.15 off the armour fraction per level before the target's protection is applied, so what the
 * swap is worth depends entirely on what the target is wearing: nothing at all against a naked fighter, and the
 * better part of a hit against netherite. Nothing here swings and nothing here decides: the style that owns the
 * fight asks {@link #hitSlot} what the hit wants in its hand on the tick it lands and holds {@link #chargeSlot}
 * in between, which is the only shape the technique can take, since the game allows one hotbar change a tick.</p>
 */
public final class MaceBreachSwap
{
    /** Ticks between two full strength hits when the cooldown is collected under a netherite sword. */
    public static final int SWORD_CADENCE = 13;

    private final MaceGear gear;

    public MaceBreachSwap(BotBody body, MaceGear gear)
    {
        this.gear = gear;
    }

    /** True while the kit carries a Breach mace, a charger to collect the cooldown under, and a measurement said yes. */
    public boolean available()
    {
        return gear.breachLevel() > 0 && gear.chargerSlot() >= 0 && MaceSwap.breachAllowed();
    }

    /** The slot of the Breach mace, or -1. */
    public int breachSlot()
    {
        return gear.breachSlot();
    }

    /** The slot of the item a hit's cooldown is collected under: the sword, or the axe when the kit has no sword. */
    public int chargeSlot()
    {
        return gear.chargerSlot();
    }

    /** The base damage a swapped ground hit carries, which is the charging item's own attribute. */
    public double baseDamage()
    {
        return gear.chargerDamage();
    }

    /** The attack speed of the item a swapped hit's cooldown is collected under. */
    public double chargeSpeed()
    {
        return gear.chargerSpeed();
    }

    /** How much the swap would add to a fully charged hit against this armour and protection. */
    public float gain(float armor, float toughness, float epf)
    {
        if (!available())
        {
            return 0.0F;
        }
        return AttributeSwap.breachGain(baseDamage(), chargeSpeed(), SWORD_CADENCE, false, 0.0F, armor, toughness,
                gear.breachLevel(), epf);
    }

    /** Whether the next charged hit is worth putting the Breach mace in the hand for. */
    public boolean worthIt(float armor, float toughness, float epf)
    {
        return available() && gain(armor, toughness, epf) >= AttributeSwap.MIN_BREACH_GAIN;
    }

    /**
     * The slot the hit wants in the hand on the tick it lands: the Breach mace where it pays, and -1 where it
     * does not, which leaves the caller's hand alone rather than making a change for nothing.
     */
    public int hitSlot(float armor, float toughness, float epf)
    {
        return worthIt(armor, toughness, epf) ? gear.breachSlot() : -1;
    }
}
