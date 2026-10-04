package carpet.pvp.mace;

import carpet.pvp.sim.AttributeSwap;

/**
 * Whether this Minecraft version still lets a fighter combine one item's attack cooldown and base damage with
 * another item's damage, which is what changing item on the tick of the hit buys.
 *
 * <p>The question is measured rather than assumed, because the answer is a property of the version: the
 * {@code mace_swap_probe} and {@code mace_breach_swap_probe} scenarios hit the same dummy with the same fall
 * twice, once with the mace held through and once with the cooldown charged under a faster item and the mace
 * swapped in on the tick of the hit, and record how much the swap added. A measurement that finds no gain turns
 * the technique off for the rest of the session, which costs a bot nothing it would otherwise have had. Until
 * one has been taken the swap counts as available, because it is not a trick: it falls out of
 * {@code Player.attack} reading the base damage and the charge from attributes the game only re-applies once a
 * tick, while the fall bonus, the enchantments and Breach come off the item that is in the hand at the moment
 * of the swing.</p>
 */
public final class MaceSwap
{
    /**
     * Ticks the probes wait under each item. Both are the item's own full charge, so the swing the action pack
     * lets through at all is the same swing in both passes: the only difference between them is which item the
     * cooldown was collected under.
     */
    public static final int MACE_TICKS = 34;
    public static final int SWORD_TICKS = 13;
    /** How much damage the swapped swing has to add before it counts as a working swap. */
    public static final float MIN_GAIN = 0.5F;

    private static boolean swapAllowed = true;
    private static boolean breachAllowed = true;
    private static String swapNote = "not measured yet";
    private static String breachNote = "not measured yet";

    private MaceSwap()
    {
    }

    /** True while a smash off a faster item's cooldown is worth more than holding the mace through. */
    public static boolean allowed()
    {
        return swapAllowed;
    }

    /** True while a ground hit is worth putting the Breach mace in the hand for. */
    public static boolean breachAllowed()
    {
        return breachAllowed;
    }

    /**
     * Records the outcome of the fall measurement: the same fall made with the mace held through, and the same
     * fall made off a cooldown collected under a faster item with the mace swapped in on the tick of the hit.
     * The swap has to be worth more damage and come round again in fewer ticks to count, because either half
     * of it on its own is not why a fighter would make the change.
     */
    public static void recordSmash(float held, int heldTicks, float swapped, int swapTicks)
    {
        swapAllowed = swapped > held + MIN_GAIN && swapTicks < heldTicks;
        swapNote = sentence(held, heldTicks, swapped, swapTicks, swapAllowed);
    }

    /**
     * Records the outcome of the ground measurement: the same charged hit made with the charging item alone,
     * and made with the Breach mace swapped in on the tick of the hit.
     */
    public static void recordBreach(float plain, float breached)
    {
        breachAllowed = breached > plain + MIN_GAIN;
        breachNote = "%.2f without the swap and %.2f with it (%+.2f), so on this version the Breach swap is"
                .formatted(plain, breached, breached - plain)
                + " " + (breachAllowed ? "worth taking" : "not worth anything");
    }

    /** What the last fall measurement said, for the self-test report. */
    public static String describe()
    {
        return swapNote;
    }

    /** What the last ground measurement said, for the self-test report. */
    public static String breachDescribe()
    {
        return breachNote;
    }

    /** Ticks between two full strength hits when the cooldown was collected under a sword. */
    public static int swordCadence()
    {
        return AttributeSwap.cadenceTicks(AttributeSwap.SWORD_ATTACK_SPEED);
    }

    private static String sentence(float plain, int plainTicks, float swapped, int swapTicks, boolean allowed)
    {
        return "%.2f in %d ticks without the swap and %.2f in %d ticks with it (%+.2f), so on this version the swap"
                .formatted(plain, plainTicks, swapped, swapTicks, swapped - plain)
                + " is " + (allowed ? "worth taking" : "not worth anything");
    }
}
