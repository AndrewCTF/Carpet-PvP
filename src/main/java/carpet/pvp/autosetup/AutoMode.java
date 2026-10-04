package carpet.pvp.autosetup;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.style.StyleIndex;

import java.util.List;
import java.util.Locale;

/**
 * The fights {@code /auto-setup} can set up: one combat style and the arena that suits it.
 *
 * <p>Every mode here has a style of its own behind it and a kit of its own to hand out, so what a
 * player picks is what they fight.</p>
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

    /** The kit both fighters start with, which every style of the menu has one of. */
    public String kitName()
    {
        return StyleIndex.kit(style);
    }

    /** A mode by the name a player types, which is what the command and the menu run. */
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
        return List.of(values());
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
