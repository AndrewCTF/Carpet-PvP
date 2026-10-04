package carpet.pvp.autosetup;

import carpet.pvp.BotSettings;
import net.minecraft.commands.CommandSourceStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The only place {@code /auto-setup} reads and writes the server settings it needs, so that the rest
 * of the package stays free of a mod loader's settings classes.
 *
 * <p>A session needs a bot that can walk to its target, which is what {@code fakePlayerNavigation}
 * is for. Which settings a session turns on is the list below, and the value each of them had
 * beforehand goes into the session file, so stopping the session puts every one of them back.</p>
 *
 * <p>Carpet's server installs the two hooks from its {@code /carpet} rules, so a change goes through
 * the rule and everything watching rules sees it; the Paper plugin answers for itself out of its
 * {@code config.yml}, which is the same {@link BotSettings} the shared code reads anyway.</p>
 */
public final class AutoSetupSettings
{
    /** Where the problems that reach nobody in chat go. */
    public static final Logger LOG = LoggerFactory.getLogger(AutoSetupSettings.class);

    /** The settings a session needs, and what it wants them at while it runs. */
    private static final String[] NEEDED = {"fakePlayerNavigation"};
    private static final String[] WANTED = {"true"};

    /** Who may run {@code /auto-setup}. Carpet sets this from its {@code commandAutoSetup} rule. */
    public static Predicate<CommandSourceStack> permission = source -> true;

    /** What a setting is at now, as the settings file spells it, or null when there is no such one. */
    public static Function<String, String> reader = setting -> null;

    /** Sets a setting, answering whether it took. Carpet goes through its rule, which reports failure. */
    public static BiPredicate<String, String> writer = (setting, value) -> false;

    private AutoSetupSettings() {}

    /** The difficulty a session starts at when the player does not name one. */
    public static String defaultDifficulty()
    {
        return BotSettings.botDifficulty;
    }

    /** The settings a session turns on while it runs, in the order it turns them on. */
    public static String[] neededSettings()
    {
        return NEEDED.clone();
    }

    /** What a session wants a setting at while it runs, or null when it does not need it. */
    public static String wantedValue(String setting)
    {
        for (int i = 0; i < NEEDED.length; i++)
        {
            if (NEEDED[i].equals(setting)) return WANTED[i];
        }
        return null;
    }

    /** What a setting is at now, or null when this server has no such one. */
    public static String value(String setting)
    {
        String value = reader.apply(setting);
        if (value == null)
        {
            LOG.warn("/auto-setup wanted the setting " + setting + ", which this server does not have");
        }
        return value;
    }

    /** Sets a setting the way the host's own command does, answering whether it took. */
    public static boolean set(String setting, String value)
    {
        if (!writer.test(setting, value))
        {
            LOG.warn("/auto-setup could not set " + setting + " to " + value);
            return false;
        }
        return true;
    }
}