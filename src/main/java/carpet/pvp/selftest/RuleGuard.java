package carpet.pvp.selftest;

import carpet.CarpetServer;
import carpet.api.settings.CarpetRule;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts the {@code /carpet} rules back the way a scenario found them, whether it passed or not.
 *
 * <p>A scenario that changes a rule and then fails, or runs out of time before it changed it back, would
 * otherwise hand the next one a server that is not the one the run was written against, and the rule would
 * stay changed for the rest of the run. {@code bot_budget} cuts the shared simulation budget down to starve
 * the bots and puts it back itself; this is what makes that put-back certain when it cannot get there.</p>
 *
 * <p>What is remembered is the value each rule had when the scenario started rather than the rule's own
 * default, because the run itself sets rules up front: the navigation is turned on for the whole run and must
 * survive every scenario.</p>
 */
final class RuleGuard
{
    private static final Map<String, String> BEFORE = new LinkedHashMap<>();

    private RuleGuard() {}

    /** Remembers the value of every rule as it is at the start of a scenario. */
    static void enter()
    {
        BEFORE.clear();
        if (CarpetServer.settingsManager == null)
        {
            return;
        }
        for (CarpetRule<?> rule : CarpetServer.settingsManager.getCarpetRules())
        {
            BEFORE.put(rule.name(), rule.value().toString());
        }
    }

    /**
     * Restores every rule that is no longer on the value the scenario started with.
     *
     * @return the names of the rules that were put back, for the scenario's report
     */
    static String leave(MinecraftServer server)
    {
        if (CarpetServer.settingsManager == null || BEFORE.isEmpty())
        {
            return "";
        }
        StringBuilder restored = new StringBuilder();
        for (Map.Entry<String, String> rule : BEFORE.entrySet())
        {
            CarpetRule<?> current = CarpetServer.settingsManager.getCarpetRule(rule.getKey());
            if (current == null || rule.getValue().equals(current.value().toString()))
            {
                continue;
            }
            SelfTest.run(server, "carpet " + rule.getKey() + " " + rule.getValue());
            if (restored.length() > 0)
            {
                restored.append(", ");
            }
            restored.append(rule.getKey());
        }
        return restored.toString();
    }
}