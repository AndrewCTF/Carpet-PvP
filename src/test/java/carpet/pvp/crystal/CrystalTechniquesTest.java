package carpet.pvp.crystal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotPvpConfig.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.List;

class CrystalTechniquesTest
{
    /** Every option on, which is the kit of a bot nobody has switched anything off on. */
    private static CrystalTechniques all(Difficulty difficulty, boolean anchorStyle)
    {
        return new CrystalTechniques(difficulty, anchorStyle, true, true, true, true, true, true, true, 20.0);
    }

    @Test
    void aBeginnerGetsPlainCrystalsAndNothingElse()
    {
        CrystalTechniques beginner = all(Difficulty.BEGINNER, false);
        assertTrue(beginner.crystals);
        assertFalse(beginner.bridges, "the obsidian bridge is not taught yet");
        assertFalse(beginner.anchors);
        assertFalse(beginner.pearls);
        assertFalse(beginner.doubleTap);
        assertFalse(beginner.handTotem);
        assertTrue(beginner.plain(), "which is what plain means");
    }

    @Test
    void eachTechniqueTurnsOnAtItsOwnDifficulty()
    {
        assertFalse(all(Difficulty.CASUAL, false).pearls, "pearls start at average");
        assertTrue(all(Difficulty.AVERAGE, false).pearls);
        assertFalse(all(Difficulty.AVERAGE, false).bridges, "the bridge starts at skilled");
        assertTrue(all(Difficulty.SKILLED, false).bridges);
        assertTrue(all(Difficulty.SKILLED, false).doubleTap, "the double tap comes with the bridge");
        assertFalse(all(Difficulty.SKILLED, true).anchors, "anchors are for an expert");
        assertFalse(all(Difficulty.SKILLED, true).handTotem);
        CrystalTechniques expert = all(Difficulty.EXPERT, true);
        assertTrue(expert.anchors);
        assertTrue(expert.handTotem);
        assertFalse(expert.plain());
    }

    /** Anchors are an option of the crystal style, so the crystal style has to be asked for them as well. */
    @Test
    void anAnchorBotNeedsBothTheStyleAndTheOption()
    {
        assertTrue(all(Difficulty.EXPERT, true).anchors);
        assertFalse(all(Difficulty.EXPERT, false).anchors, "a crystal bot does not reach for an anchor");
    }

    @Test
    void aSwitchedOffTechniqueIsOffWhicheverDifficulty()
    {
        CrystalTechniques expert = new CrystalTechniques(Difficulty.EXPERT, true, true, true, false, true, true,
                true, true, 20.0);
        assertFalse(expert.anchors, "the owner switched anchors off");
        assertTrue(expert.bridges, "and left the rest on");
        CrystalTechniques noCrystals = new CrystalTechniques(Difficulty.EXPERT, true, false, true, true, true,
                true, true, true, 20.0);
        assertTrue(noCrystals.none());
        assertFalse(noCrystals.bridges, "nothing hangs off a bot that is not placing crystals");
        assertFalse(noCrystals.pearls);
        assertFalse(noCrystals.reTotem);
    }

    @Test
    void theReTotemDelayIsTheOneThatWasConfigured()
    {
        assertEquals(20, all(Difficulty.AVERAGE, false).reTotemDelay);
        assertEquals(1, new CrystalTechniques(Difficulty.AVERAGE, false, true, true, true, true, true, true,
                true, 1.4).reTotemDelay);
        assertEquals(0, new CrystalTechniques(Difficulty.AVERAGE, false, true, true, true, true, true, true,
                true, -3.0).reTotemDelay);
    }

    /** A harder bot looks again more often and over more of the world; it is never slower than a beginner. */
    @Test
    void harderLooksSoonerAndFurther()
    {
        int previousInterval = Integer.MAX_VALUE;
        int previousVolume = 0;
        for (Difficulty difficulty : Difficulty.values())
        {
            CrystalTechniques techniques = all(difficulty, false);
            assertTrue(techniques.planInterval <= previousInterval,
                    difficulty + " waits longer than the one below it");
            assertTrue(techniques.planInterval >= 1, "a bot always searches now and then");
            int volume = (2 * techniques.halfX + 1) * (2 * techniques.halfY + 1)
                    * (2 * techniques.halfZ + 1);
            assertTrue(volume >= previousVolume, difficulty + " looks at less than the one below it");
            previousInterval = techniques.planInterval;
            previousVolume = volume;
        }
        assertEquals(1, all(Difficulty.EXPERT, false).planInterval);
        assertEquals(2, all(Difficulty.BEGINNER, false).halfX, "a beginner only looks at the blocks around its feet");
        assertEquals(6, all(Difficulty.EXPERT, false).halfX, "an expert looks as far as its arm reaches");
    }

    @Test
    void sameAsOnlyWhenNothingTheStyleReadsHasChanged()
    {
        CrystalTechniques expert = all(Difficulty.EXPERT, false);
        assertTrue(expert.sameAs(all(Difficulty.EXPERT, false)));
        assertFalse(expert.sameAs(all(Difficulty.EXPERT, true)), "the anchor style is one of them");
        assertFalse(expert.sameAs(all(Difficulty.AVERAGE, false)));
        assertFalse(expert.sameAs(new CrystalTechniques(Difficulty.EXPERT, false, true, true, true, true, true,
                true, true, 30.0)), "a different re-totem delay is a change too");
        assertFalse(expert.sameAs(null));
    }

    /** The options the style reads are the ones StyleIndex declares, so /bot option accepts them. */
    @Test
    void everyOptionTheStyleReadsIsDeclaredWithItsDefault()
    {
        String[] keys = carpet.pvp.style.StyleIndex.options().keySet().toArray(new String[0]);
        for (String key : List.of("crystal.crystals", "crystal.obsidian", "crystal.anchors", "crystal.pearls",
                "crystal.doubletap", "crystal.hand_totem", "crystal.retotem", "crystal.retotem_delay",
                "crystal.reach"))
        {
            assertTrue(List.of(keys).contains(key), key + " is not declared in StyleIndex");
        }
        assertTrue(List.of(BotPvpConfig.keys()).contains("crystal.reach"),
                "and /bot option has to suggest it");
    }
}
