package carpet.pvp.smp;

import carpet.pvp.BotPvpConfig.Difficulty;

import java.util.function.Predicate;

/**
 * Which survival techniques an SMP bot is allowed to use, from its difficulty and the switches of
 * its style options.
 *
 * <p>A difficulty is a floor, not a switch: every technique has the lowest difficulty at which a
 * player of that skill would do it, so a beginner eats an apple and little else while an expert
 * pearls away, tops its buffs up and drops a cobweb. Each technique also has its own style option,
 * so {@code /bot option <name> smp.pearl false} takes one away from a bot that is otherwise an
 * expert.</p>
 */
public record SmpGates(boolean eat, boolean splashHeal, boolean buff, boolean totem, boolean armorSwap,
        boolean mend, boolean pearl, boolean web, boolean bucket, boolean guard)
{
    /** Style option that switches the golden apples off and on. */
    public static final String OPT_EAT = "smp.eat";
    /** Style option for throwing splash healing at the bot's own feet. */
    public static final String OPT_SPLASH_HEAL = "smp.splashheal";
    /** Style option for drinking and throwing strength and speed. */
    public static final String OPT_BUFF = "smp.buff";
    /** Style option for the totem in the offhand and the wait after a pop. */
    public static final String OPT_TOTEM = "smp.totem";
    /** Style option for swapping a worn piece of armour for a spare. */
    public static final String OPT_ARMOR = "smp.armor";
    /** Style option for mending with experience bottles. */
    public static final String OPT_MEND = "smp.mend";
    /** Style option for pearling away to heal. */
    public static final String OPT_PEARL = "smp.pearl";
    /** Style option for the cobweb dropped at a target that is running. */
    public static final String OPT_WEB = "smp.web";
    /** Style option for the water bucket. */
    public static final String OPT_BUCKET = "smp.bucket";
    /** Style option for keeping the shield up around a slow action. */
    public static final String OPT_GUARD = "smp.guard";
    /** Style option for the ticks a bot waits after popping a totem before it moves a fresh one in. */
    public static final String OPT_RETOTEM = "smp.retotem";
    /** Style option for how long before a buff runs out the bot tops it up. */
    public static final String OPT_BUFF_WINDOW = "smp.buffwindow";
    /** Style option for the distance in blocks a retreat throw aims for. */
    public static final String OPT_PEEL_BACK = "smp.peelback";

    /** The gates of a bot of the given difficulty with the given style options. */
    public static SmpGates of(Difficulty difficulty, Predicate<String> option)
    {
        return new SmpGates(
                atLeast(difficulty, Difficulty.BEGINNER) && option.test(OPT_EAT),
                atLeast(difficulty, Difficulty.AVERAGE) && option.test(OPT_SPLASH_HEAL),
                atLeast(difficulty, Difficulty.SKILLED) && option.test(OPT_BUFF),
                atLeast(difficulty, Difficulty.BEGINNER) && option.test(OPT_TOTEM),
                atLeast(difficulty, Difficulty.AVERAGE) && option.test(OPT_ARMOR),
                atLeast(difficulty, Difficulty.SKILLED) && option.test(OPT_MEND),
                atLeast(difficulty, Difficulty.SKILLED) && option.test(OPT_PEARL),
                atLeast(difficulty, Difficulty.AVERAGE) && option.test(OPT_WEB),
                atLeast(difficulty, Difficulty.AVERAGE) && option.test(OPT_BUCKET),
                atLeast(difficulty, Difficulty.AVERAGE) && option.test(OPT_GUARD));
    }

    /** True when the difficulty is at least the one a technique needs. */
    private static boolean atLeast(Difficulty difficulty, Difficulty needed)
    {
        return difficulty.ordinal() >= needed.ordinal();
    }
}