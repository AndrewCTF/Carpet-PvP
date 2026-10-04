package carpet;

import carpet.settings.Rule;
import carpet.pvp.BotPvpConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that name a combat style have to offer every style there is: the rule is the only way to
 * set the style of every bot a server spawns afterwards, and a strict rule with a style missing from
 * it cannot be set to that style at all.
 */
class BotCombatStyleRuleTest
{
    @Test
    void theStyleRuleOffersExactlyTheStylesThereAre() throws NoSuchFieldException
    {
        Rule rule = rule("botCombatStyle");
        assertTrue(rule.strict(), "the rule is strict, so a style missing from it cannot be set");
        Set<String> offered = new LinkedHashSet<>(Arrays.asList(rule.options()));
        Set<String> styles = Arrays.stream(BotPvpConfig.CombatStyle.values())
                .map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(styles, offered);
    }

    @Test
    void everyStyleTheRuleNamesIsOneTheConfigCanParse()
    {
        for (BotPvpConfig.CombatStyle style : BotPvpConfig.CombatStyle.values())
        {
            assertEquals(style, BotPvpConfig.styleOf(style.name()), style.name());
        }
    }

    private static Rule rule(String name) throws NoSuchFieldException
    {
        Field field = CarpetSettings.class.getField(name);
        Rule rule = field.getAnnotation(Rule.class);
        assertNotNull(rule, name + " is not a rule");
        return rule;
    }
}
