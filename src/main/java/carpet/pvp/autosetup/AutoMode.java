package carpet.pvp.autosetup;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.style.StyleIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fights {@code /auto-setup} can set up: one combat style and the arena that suits it.
 *
 * <p>A mode whose style has no implementation yet is still offered and falls back to the sword, so
 * that the kit and the arena a player asked for are what they get either way. Only ranged waits
 * for a style of its own, since there is nothing to practice against without one.</p>
 */
public enum AutoMode
{
    SWORD("Sword", BotPvpConfig.CombatStyle.MELEE),
    SMP("SMP", BotPvpConfig.CombatStyle.SMP),
    MACE("Mace", BotPvpConfig.CombatStyle.MACE),
    CRYSTAL("Crystal", BotPvpConfig.CombatStyle.CRYSTAL),
    RANGED("Ranged", BotPvpConfig.CombatStyle.RANGED);

    private final String label;
    private final BotPvpConfig.CombatStyle style;

    AutoMode(String label, BotPvpConfig.CombatStyle style)
    {
        this.label = label;
        this.style = style;
    }

    /** The style a bot spawned in this mode fights with. */
    public BotPvpConfig.CombatStyle style()
    {
        return style;
    }

    /** What the menu calls this mode. */
    public String label()
    {
        return label;
    }

    /** True while the mode is worth offering: the four PvP modes always, ranged once it is written. */
    public boolean isOffered()
    {
        return this != RANGED || StyleIndex.has(style);
    }

    /** The kit both fighters start with; a mode without a kit of its own gets the sword's. */
    public String kitName()
    {
        String kit = StyleIndex.kit(style);
        return kit != null ? kit : StyleIndex.kit(BotPvpConfig.CombatStyle.MELEE);
    }

    /** A mode by the name a player types, which is also what the session file keeps. */
    public static AutoMode of(String name)
    {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (AutoMode mode : values())
        {
            if (mode.name().toLowerCase(Locale.ROOT).equals(wanted)) return mode;
        }
        throw new IllegalArgumentException("unknown mode: " + name);
    }

    /** The modes the menu offers, in the order it lists them. */
    public static List<AutoMode> offered()
    {
        List<AutoMode> modes = new ArrayList<>();
        for (AutoMode mode : values())
        {
            if (mode.isOffered()) modes.add(mode);
        }
        return modes;
    }

    /** Every mode name, for the error message of a mistyped one. */
    public static String[] names()
    {
        AutoMode[] modes = values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++)
        {
            names[i] = modes[i].name().toLowerCase(Locale.ROOT);
        }
        return names;
    }
}
