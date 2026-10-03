package carpet.pvp.autosetup;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.InvalidRuleValueException;
import carpet.api.settings.RuleHelper;
import carpet.api.settings.SettingsManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * The only place {@code /auto-setup} reads and writes {@code /carpet} rules, so that the rest of
 * the package stays free of the settings classes.
 *
 * <p>A session needs a bot that can walk to its target, which is what {@code fakePlayerNavigation}
 * is for. Which rules a session turns on is the list below, and the value each of them had
 * beforehand goes into the session file, so stopping the session puts every one of them back.</p>
 */
public final class AutoSetupSettings
{
    /** Where the problems that reach nobody in chat go. */
    public static final Logger LOG = CarpetSettings.LOG;

    /** The rules a session needs, and what it wants them at while it runs. */
    private static final String[] NEEDED = {"fakePlayerNavigation"};
    private static final String[] WANTED = {"true"};

    private AutoSetupSettings() {}

    /** What the command rule lets, as {@code /carpet} spells a permission. */
    public static Object permission()
    {
        return CarpetSettings.commandAutoSetup;
    }

    /** The difficulty a session starts at when the player does not name one. */
    public static String defaultDifficulty()
    {
        return CarpetSettings.botDifficulty;
    }

    /** The rules a session turns on while it runs, in the order it turns them on. */
    public static String[] neededRules()
    {
        return NEEDED.clone();
    }

    /** What a session wants a rule at while it runs, or null when it does not need it. */
    public static String wantedValue(String rule)
    {
        for (int i = 0; i < NEEDED.length; i++)
        {
            if (NEEDED[i].equals(rule)) return WANTED[i];
        }
        return null;
    }

    /** What a rule is at now, as the {@code /carpet} file would spell it, or null when there is no such rule. */
    public static String value(String rule)
    {
        CarpetRule<?> carpetRule = rule(rule);
        return carpetRule == null ? null : RuleHelper.toRuleString(carpetRule.value());
    }

    /**
     * Sets a rule the way the {@code /carpet} command does, so that everything watching rules sees
     * it. Returns whether it took.
     */
    public static boolean set(String rule, String value)
    {
        CarpetRule<?> carpetRule = rule(rule);
        if (carpetRule == null)
        {
            LOG.warn("/auto-setup wanted the rule " + rule + ", which this server does not have");
            return false;
        }
        try
        {
            carpetRule.set(source(), value);
            return true;
        }
        catch (InvalidRuleValueException e)
        {
            LOG.warn("/auto-setup could not set " + rule + " to " + value + ": " + e.getMessage());
            return false;
        }
    }

    private static CommandSourceStack source()
    {
        MinecraftServer server = CarpetServer.minecraft_server;
        return server == null ? null : server.createCommandSourceStack();
    }

    @SuppressWarnings("removal") // the one field carpet still keeps of its own settings manager
    private static CarpetRule<?> rule(String name)
    {
        SettingsManager manager = CarpetServer.settingsManager;
        return manager == null ? null : manager.getCarpetRule(name);
    }
}
