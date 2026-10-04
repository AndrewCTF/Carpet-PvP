package carpet.pvp.mace;

/**
 * Whether this Minecraft version still lets a fighter combine one item's attack cooldown with another
 * item's damage, which is what switching item on the tick of the hit would buy.
 *
 * <p>The question is measured rather than assumed, because the answer is a property of the version: the
 * {@code mace_attribute_swap_probe} scenario hits the same dummy twice with the same wait, once with the
 * cooldown charged under a fast item and once with it charged under the mace itself, and records which
 * of the two hurt more. A bot only uses the swap once a measurement has said it is worth anything; until
 * then the technique stays off, which costs a bot nothing it would otherwise have had.</p>
 */
public final class MaceSwap
{
    /**
     * Ticks the probe waits under each item. Both are the item's own full charge, so the swing the
     * action pack will let through at all is the same swing in both passes: the only difference between
     * them is which item the cooldown was collected under.
     */
    public static final int MACE_TICKS = 34;
    public static final int AXE_TICKS = 21;
    /** How much more damage the swapped swing has to do before it counts as a working swap. */
    public static final float MIN_GAIN = 0.05F;

    private static Boolean allowed;

    private MaceSwap()
    {
    }

    /** True once a measurement says the swap is worth more than charging the swing item itself. */
    public static boolean allowed()
    {
        return Boolean.TRUE.equals(allowed);
    }

    /** Records the outcome of a measurement. */
    public static void record(float chargedDamage, float swappedDamage)
    {
        allowed = swappedDamage > chargedDamage + MIN_GAIN;
    }

    /** What the last measurement said, for the self-test report. */
    public static String describe()
    {
        return allowed == null ? "not measured yet" : allowed ? "allowed" : "refused";
    }
}
