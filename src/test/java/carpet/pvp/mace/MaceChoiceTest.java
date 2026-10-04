package carpet.pvp.mace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotPvpConfig.Difficulty;
import carpet.pvp.mace.MaceChoice.Technique;
import carpet.pvp.sim.CombatMath;
import org.junit.jupiter.api.Test;

class MaceChoiceTest
{
    /** The fall distance MaceEngagementTest has a wind charge smash arrive with. */
    private static final float FALL = 3.94F;

    @Test
    void everyPresetKnowsTheWindChargeAndTheSafeLanding()
    {
        for (Difficulty difficulty : Difficulty.values())
        {
            assertTrue(MaceChoice.allows(difficulty, Technique.WIND_CHARGE), difficulty + " launch");
            assertTrue(MaceChoice.allows(difficulty, Technique.SAFE_LANDING), difficulty + " landing");
            assertTrue(MaceChoice.allows(difficulty, Technique.ENCHANT_PICK), difficulty + " enchant pick");
        }
    }

    /** The techniques climb with the preset, and only an expert goes for the swap. */
    @Test
    void theHarderPresetsKnowMore()
    {
        assertFalse(MaceChoice.allows(Difficulty.BEGINNER, Technique.CHAIN));
        assertTrue(MaceChoice.allows(Difficulty.CASUAL, Technique.CHAIN));
        assertFalse(MaceChoice.allows(Difficulty.CASUAL, Technique.PEARL));
        assertTrue(MaceChoice.allows(Difficulty.AVERAGE, Technique.PEARL));
        assertTrue(MaceChoice.allows(Difficulty.AVERAGE, Technique.STUN_SLAM));
        assertFalse(MaceChoice.allows(Difficulty.AVERAGE, Technique.ELYTRA));
        assertTrue(MaceChoice.allows(Difficulty.SKILLED, Technique.ELYTRA));
        assertTrue(MaceChoice.allows(Difficulty.SKILLED, Technique.BOUNCE));
        assertFalse(MaceChoice.allows(Difficulty.SKILLED, Technique.SWAP));
        assertTrue(MaceChoice.allows(Difficulty.EXPERT, Technique.SWAP));
    }

    /**
     * With no armour at all the Density mace wins: its bonus rides on top of the charge scaling and needs
     * no help from the armour fraction, while Breach has nothing to take a slice off.
     */
    @Test
    void densityWinsAgainstAnUnarmouredTarget()
    {
        float dense = MaceChoice.smashDamage(FALL, 5, 0, 0.0F, 0.0F, 0.0F);
        float breach = MaceChoice.smashDamage(FALL, 0, 4, 0.0F, 0.0F, 0.0F);
        assertEquals(1.5F * (6.0F + CombatMath.maceSmashBonus(FALL, false, 5)), dense, 1e-3F);
        assertEquals(1.5F * (6.0F + CombatMath.maceSmashBonus(FALL, false, 0)), breach, 1e-3F);
        assertFalse(MaceChoice.preferBreach(FALL, 5, 4, 0.0F, 0.0F, 0.0F));
    }

    /**
     * A full set of netherite is the other end: the smash is so big that the armour already lets most of
     * it through, and Breach's four levels take the rest of the fraction off entirely.
     */
    @Test
    void breachWinsAgainstNetherite()
    {
        float dense = MaceChoice.smashDamage(FALL, 5, 0, 20.0F, 12.0F, 0.0F);
        float breach = MaceChoice.smashDamage(FALL, 0, 4, 20.0F, 12.0F, 0.0F);
        assertEquals(24.83F, dense, 0.05F);
        assertEquals(29.82F, breach, 0.05F);
        assertTrue(breach > dense, "Breach's four levels take the rest of the fraction off");
        assertTrue(MaceChoice.preferBreach(FALL, 5, 4, 20.0F, 12.0F, 0.0F));
    }

    /** One enchantment missing means there is nothing to choose. */
    @Test
    void aKitWithOnlyOneMaceDoesNotChoose()
    {
        assertFalse(MaceChoice.preferBreach(FALL, 5, 0, 20.0F, 12.0F, 0.0F));
        assertTrue(MaceChoice.preferBreach(FALL, 0, 4, 20.0F, 12.0F, 0.0F));
    }

    /**
     * A chained charge is only worth throwing when the extra fall it buys turns into damage on the
     * target's own defences. Under the model's own gate a fall of less than 1.5 blocks is no smash at
     * all, so a chain that only adds half a block is refused.
     */
    @Test
    void aChainHasToBuyDamage()
    {
        assertFalse(MaceChoice.chainWorthIt(0.5, 5, 0.0F, 0.0F, 0.0F), "under the smash gate");
        assertFalse(MaceChoice.chainWorthIt(MaceChoice.CHAIN_MIN_EXTRA, 5, 20.0F, 12.0F, 0.0F),
                "just over the minimum, but still under the gate");
        assertTrue(MaceChoice.chainWorthIt(3.0, 5, 20.0F, 12.0F, 0.0F), "three extra blocks through netherite");
        assertTrue(MaceChoice.chainWorthIt(1.6, 0, 0.0F, 0.0F, 0.0F),
                "just past the gate against an unarmoured target");
    }
}
