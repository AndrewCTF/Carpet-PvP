package carpet.pvp.selftest;

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
 *
 * <p>A host with no rules, which is the Paper plugin, remembers nothing and has nothing to put back: it
 * answers the two questions of {@link SelfTest#ruleSnapshot} and {@link SelfTest#ruleRestore} with nothing.</p>
 */
final class RuleGuard
{
    private static final Map<String, String> BEFORE = new LinkedHashMap<>();

    private RuleGuard() {}

    /** Remembers the value of every rule as it is at the start of a scenario. */
    static void enter()
    {
        BEFORE.clear();
        BEFORE.putAll(SelfTest.ruleSnapshot.get());
    }

    /**
     * Restores every rule that is no longer on the value the scenario started with.
     *
     * @return the names of the rules that were put back, for the scenario's report
     */
    static String leave()
    {
        return BEFORE.isEmpty() ? "" : String.join(", ", SelfTest.ruleRestore.apply(BEFORE));
    }
}