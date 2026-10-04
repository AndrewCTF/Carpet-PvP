package carpet.pvp.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotPvpConfig.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

class SmpGatesTest
{
    private static Predicate<String> all(String... off)
    {
        Map<String, String> options = new HashMap<>();
        for (String option : off)
        {
            options.put(option, "false");
        }
        return key -> !options.containsKey(key);
    }

    @Test
    void aBeginnerOnlyEatsAndKeepsATotem()
    {
        SmpGates gates = SmpGates.of(Difficulty.BEGINNER, all());
        assertTrue(gates.eat());
        assertTrue(gates.totem());
        assertFalse(gates.splashHeal());
        assertFalse(gates.buff());
        assertFalse(gates.armorSwap());
        assertFalse(gates.mend());
        assertFalse(gates.pearl());
        assertFalse(gates.web());
        assertFalse(gates.bucket());
        assertFalse(gates.guard());
    }

    @Test
    void aCasualFighterHasTheBasicsAndNothingElse()
    {
        SmpGates gates = SmpGates.of(Difficulty.CASUAL, all());
        assertTrue(gates.eat());
        assertTrue(gates.totem());
        assertFalse(gates.splashHeal());
        assertFalse(gates.pearl());
        assertFalse(gates.mend());
    }

    @Test
    void anAverageFighterStartsCarryingTheUtility()
    {
        SmpGates gates = SmpGates.of(Difficulty.AVERAGE, all());
        assertTrue(gates.eat());
        assertTrue(gates.splashHeal());
        assertTrue(gates.armorSwap());
        assertTrue(gates.web());
        assertTrue(gates.bucket());
        assertTrue(gates.guard());
        assertFalse(gates.buff());
        assertFalse(gates.mend());
        assertFalse(gates.pearl());
    }

    @Test
    void aSkilledAndAnExpertFighterHaveEverything()
    {
        for (Difficulty difficulty : new Difficulty[] {Difficulty.SKILLED, Difficulty.EXPERT})
        {
            SmpGates gates = SmpGates.of(difficulty, all());
            assertTrue(gates.eat(), difficulty.name());
            assertTrue(gates.splashHeal(), difficulty.name());
            assertTrue(gates.buff(), difficulty.name());
            assertTrue(gates.totem(), difficulty.name());
            assertTrue(gates.armorSwap(), difficulty.name());
            assertTrue(gates.mend(), difficulty.name());
            assertTrue(gates.pearl(), difficulty.name());
            assertTrue(gates.web(), difficulty.name());
            assertTrue(gates.bucket(), difficulty.name());
            assertTrue(gates.guard(), difficulty.name());
        }
    }

    @Test
    void everyTechniqueCanBeSwitchedOffOnItsOwn()
    {
        // An expert with one technique taken away keeps the rest, which is what /bot option does.
        SmpGates gates = SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_PEARL));
        assertFalse(gates.pearl());
        assertTrue(gates.eat());
        assertTrue(gates.mend());
        assertTrue(gates.buff());

        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_EAT)).eat());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_SPLASH_HEAL)).splashHeal());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_BUFF)).buff());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_TOTEM)).totem());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_ARMOR)).armorSwap());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_MEND)).mend());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_WEB)).web());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_BUCKET)).bucket());
        assertFalse(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_GUARD)).guard());
    }

    @Test
    void theOptionNamesAreTheOnesCommandsTake()
    {
        assertEquals("smp.eat", SmpGates.OPT_EAT);
        assertEquals("smp.splashheal", SmpGates.OPT_SPLASH_HEAL);
        assertEquals("smp.buff", SmpGates.OPT_BUFF);
        assertEquals("smp.totem", SmpGates.OPT_TOTEM);
        assertEquals("smp.armor", SmpGates.OPT_ARMOR);
        assertEquals("smp.mend", SmpGates.OPT_MEND);
        assertEquals("smp.pearl", SmpGates.OPT_PEARL);
        assertEquals("smp.web", SmpGates.OPT_WEB);
        assertEquals("smp.bucket", SmpGates.OPT_BUCKET);
        assertEquals("smp.guard", SmpGates.OPT_GUARD);
        assertEquals("smp.retotem", SmpGates.OPT_RETOTEM);
        assertEquals("smp.buffwindow", SmpGates.OPT_BUFF_WINDOW);
        assertEquals("smp.peelback", SmpGates.OPT_PEEL_BACK);
    }
}