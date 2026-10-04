package carpet;

import carpet.api.settings.CarpetRule;
import carpet.api.settings.InvalidRuleValueException;
import carpet.api.settings.RuleHelper;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.settings.SettingsManager;
import carpet.utils.CommandHelper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Fabric side of {@link AutoSetupSettings}: {@code /auto-setup} asks its host for the settings a
 * session needs, and on this side a setting is a {@code /carpet} rule, so a session's change goes
 * through the rule and everything watching rules sees it. The Paper plugin answers the same questions
 * out of its own {@code config.yml}.
 */
public final class CarpetAutoSetup
{
    private CarpetAutoSetup() {}

    /** Called from {@link CarpetServer#onServerLoaded}, once the rules are read. */
    public static void hook()
    {
        AutoSetupSettings.permission = source -> CommandHelper.canUseCommand(source, CarpetSettings.commandAutoSetup);
        AutoSetupSettings.reader = CarpetAutoSetup::value;
        AutoSetupSettings.writer = CarpetAutoSetup::set;
    }

    /** What a rule is at now, as the {@code /carpet} file would spell it, or null when there is none. */
    public static String value(String setting)
    {
        CarpetRule<?> rule = rule(setting);
        return rule == null ? null : RuleHelper.toRuleString(rule.value());
    }

    /** Sets a rule the way the {@code /carpet} command does, answering whether it took. */
    public static boolean set(String setting, String value)
    {
        CarpetRule<?> rule = rule(setting);
        if (rule == null)
        {
            AutoSetupSettings.LOG.warn("/auto-setup wanted the rule " + setting + ", which this server does not have");
            return false;
        }
        try
        {
            rule.set(source(), value);
            return true;
        }
        catch (InvalidRuleValueException e)
        {
            AutoSetupSettings.LOG.warn("/auto-setup could not set " + setting + " to " + value + ": " + e.getMessage());
            return false;
        }
    }

    /** Every rule of this server and what it is at, as {@link #value} spells it. */
    @SuppressWarnings("removal")
    public static Map<String, String> everyRule()
    {
        Map<String, String> values = new LinkedHashMap<>();
        SettingsManager manager = CarpetServer.settingsManager;
        if (manager == null) return values;
        for (CarpetRule<?> rule : manager.getCarpetRules())
        {
            values.put(rule.name(), rule.value().toString());
        }
        return values;
    }

    private static CommandSourceStack source()
    {
        MinecraftServer server = CarpetServer.minecraft_server;
        return server == null ? null : server.createCommandSourceStack();
    }

    @SuppressWarnings("removal") // the one field carpet still keeps of its own settings manager
    private static CarpetRule<?> rule(String name)
    {
        return CarpetServer.settingsManager == null ? null : CarpetServer.settingsManager.getCarpetRule(name);
    }
}