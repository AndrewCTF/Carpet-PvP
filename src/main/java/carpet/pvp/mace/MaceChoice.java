package carpet.pvp.mace;

import carpet.pvp.BotPvpConfig.Difficulty;
import carpet.pvp.sim.CombatMath;

/**
 * The questions a mace fighter asks before it commits to something: whether its difficulty knows the
 * technique at all, which of its two maces to swing, and whether a second charge is worth the ticks
 * it costs. The answers come out of {@link CombatMath} and the difficulty presets, and none of them
 * needs a world, so they are unit tested on their own.
 */
public final class MaceChoice
{
    /** Extra fall distance a second charge has to add before the wait for its cooldown is worth it. */
    public static final double CHAIN_MIN_EXTRA = 0.75;
    /** Damage a chained charge has to be worth on the target's own defences before it is thrown. */
    public static final float CHAIN_MIN_GAIN = 1.0F;

    /** The techniques a mace style can switch off one by one. */
    public enum Technique
    {
        WIND_CHARGE, CHAIN, PEARL, ELYTRA, ROCKET, STUN_SLAM, FALL_STUN_SLAM, ENCHANT_PICK, BOUNCE,
        SAFE_LANDING, SWAP, BREACH_SWAP, READ
    }

    private MaceChoice()
    {
    }

    /**
     * Whether a difficulty preset knows a technique. The presets climb: a beginner throws one wind charge and
     * tries not to break a leg on landing, a casual fighter chains a second one, an average one adds the pearl
     * and reads what the target is doing, a skilled one dives, chains the bounce off a smash and changes item on
     * the tick of the hit, and an expert is willing to take the stun slam inside a single fall.
     */
    public static boolean allows(Difficulty difficulty, Technique technique)
    {
        return switch (difficulty)
        {
            case BEGINNER -> technique == Technique.WIND_CHARGE
                    || technique == Technique.SAFE_LANDING
                    || technique == Technique.ENCHANT_PICK;
            case CASUAL -> allows(Difficulty.BEGINNER, technique) || technique == Technique.CHAIN;
            case AVERAGE -> allows(Difficulty.CASUAL, technique)
                    || technique == Technique.PEARL
                    || technique == Technique.STUN_SLAM
                    || technique == Technique.READ;
            case SKILLED -> allows(Difficulty.AVERAGE, technique)
                    || technique == Technique.ELYTRA
                    || technique == Technique.ROCKET
                    || technique == Technique.BOUNCE
                    || technique == Technique.SWAP
                    || technique == Technique.BREACH_SWAP;
            case EXPERT -> allows(Difficulty.SKILLED, technique)
                    || technique == Technique.FALL_STUN_SLAM;
        };
    }

    /**
     * The damage a fully charged crit of a mace with the given enchantments does from a fall of the
     * given distance to the given armour, exactly as {@link CombatMath} assembles it.
     */
    public static float smashDamage(double fallDistance, int density, int breach, float armor, float toughness,
            float epf)
    {
        float raw = CombatMath.attackDamage(6.0F, CombatMath.maceSmashBonus(fallDistance, false, density),
                0.0F, 1.0F, true);
        return CombatMath.damageAfterDefences(raw, armor, toughness, breach, epf);
    }

    /**
     * True when the Breach mace does more to this armour than the Density one from the same fall. The
     * kit's two maces cannot have both enchantments, so the comparison is between the one that adds half
     * a point per level per fallen block and the one that takes a slice off the armour fraction: the first
     * wants a long fall, the second wants a heavy target.
     */
    public static boolean preferBreach(double fallDistance, int densityLevel, int breachLevel, float armor,
            float toughness, float epf)
    {
        if (breachLevel <= 0)
        {
            return false;
        }
        if (densityLevel <= 0)
        {
            return true;
        }
        return smashDamage(fallDistance, 0, breachLevel, armor, toughness, epf)
                > smashDamage(fallDistance, densityLevel, 0, armor, toughness, epf);
    }

    /**
     * Whether a second wind charge is worth throwing once the first one's cooldown is over. The extra
     * height is only worth the ticks of exposure it adds if it turns into damage on the target's own
     * defences, which is what the chain has to buy.
     */
    public static boolean chainWorthIt(double extraHeight, int densityLevel, float armor, float toughness, float epf)
    {
        if (extraHeight < CHAIN_MIN_EXTRA)
        {
            return false;
        }
        float gain = CombatMath.damageAfterDefences(
                CombatMath.maceSmashBonus(extraHeight, false, densityLevel) * CombatMath.CRIT_MULTIPLIER,
                armor, toughness, 0, epf);
        return gain >= CHAIN_MIN_GAIN;
    }
}
