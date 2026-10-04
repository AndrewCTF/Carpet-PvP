package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The attribute swap priced against the vanilla damage assembly, and the choice between the two maces of the
 * kit and between swapping and not swapping at all.
 */
class AttributeSwapTest
{
    /** Netherite armour of the kit: 20 points of armour and 12 of toughness. */
    private static final float NETHERITE_ARMOR = 20.0F;
    private static final float NETHERITE_TOUGHNESS = 12.0F;

    /**
     * Player.attack takes its base damage from the ATTACK_DAMAGE attribute and its charge from the
     * ATTACK_SPEED one, and both are only re-read from the held item once per tick, in LivingEntity.tick. So a
     * swing made with the mace in the hand while the hand held the sword a moment earlier is the sword's eight
     * base damage at the sword's full charge, with nothing of the mace's six.
     */
    @Test
    void aSwapOnTheGroundCarriesTheOtherItemsBaseDamage()
    {
        float swapped = AttributeSwap.damage(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, false, 0.0, 0, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        assertEquals(8.0F, swapped, 1e-5F, "a charged sword's eight base damage, with no fall bonus to add");
        float mace = AttributeSwap.damage(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 34, false,
                0.0, 0, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        assertEquals(6.0F, mace, 1e-5F);
        assertTrue(swapped > mace, "a netherite sword hits harder than a mace on the ground");
    }

    /**
     * The same fall twice. With the mace held through, the whole smash is charged: six base damage scaled by
     * the charge and the fall bonus, both multiplied by the crit. Swapped in on the tick of the hit, the eight
     * base damage of the sword arrive at the sword's charge and the fall bonus and Density ride on top of them
     * unscaled, then the crit multiplies the sum. At a short fall the swap wins on the base damage; at a long
     * one the mace's own longer charge starts to matter.
     */
    @Test
    void aSwapOnAFallAddsTheBonusesToTheSwordsBaseDamage()
    {
        float swapped = AttributeSwap.damage(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, true, 4.0, 0, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        float mace = AttributeSwap.damage(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 34, true,
                4.0, 0, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        // The fall bonus at 4 blocks is 12 + 2, added after the charge scaling and before the crit.
        assertEquals((8.0F + 14.0F) * CombatMath.CRIT_MULTIPLIER, swapped, 1e-4F);
        assertEquals((6.0F + 14.0F) * CombatMath.CRIT_MULTIPLIER, mace, 1e-4F);
        assertTrue(swapped > mace);
    }

    /**
     * Density is 0.5 a point per level per fallen block, added inside CombatMath.maceSmashBonus, so it rides on
     * top of whatever base damage the hand carried and is multiplied by the crit with everything else.
     */
    @Test
    void densityComesOffTheMaceNotTheSword()
    {
        float plain = AttributeSwap.damage(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, false, 4.0, 0, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        float dense = AttributeSwap.damage(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, false, 4.0, 5, 0.0F, 0.0F, 0.0F, 0, 0.0F);
        assertEquals(8.0F + 14.0F, plain, 1e-4F);
        assertEquals(8.0F + 14.0F + 0.5F * 5.0F * 4.0F, dense, 1e-4F);
    }

    /** The cooldown the swap carries over is the charging item's, so the next smash is ready far sooner. */
    @Test
    void theSwordsCooldownIsAThirdOfTheMaces()
    {
        assertEquals(13, AttributeSwap.cadenceTicks(AttributeSwap.SWORD_ATTACK_SPEED));
        assertEquals(34, AttributeSwap.cadenceTicks(SmashTiming.MACE_ATTACK_SPEED));
        assertEquals(20, AttributeSwap.cadenceTicks(AttributeSwap.AXE_ATTACK_SPEED));
        assertTrue(AttributeSwap.cadenceTicks(AttributeSwap.SWORD_ATTACK_SPEED) * 2
                < AttributeSwap.cadenceTicks(SmashTiming.MACE_ATTACK_SPEED));
    }

    /** An uncharged swap loses to a mace that held its own charge, so the comparison has to be priced, not assumed. */
    @Test
    void anUnchargedSwapIsNotWorthIt()
    {
        int charged = 13;
        assertTrue(AttributeSwap.beatsHeld(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED, charged,
                false, 4.0, 0, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0.0F,
                SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 34),
                "off a charged sword the swap is the harder hit");
        assertFalse(AttributeSwap.beatsHeld(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED, 0,
                false, 4.0, 0, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0.0F,
                SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 34),
                "off a spent cooldown the swap is worth less than the mace's own charge");
    }

    /**
     * Breach takes 0.15 off the armour fraction per level and the protection is applied afterwards, so the gain
     * is large against netherite and nothing at all without armour.
     */
    @Test
    void breachIsWorthTheSwapOnlyThroughArmour()
    {
        float gain = AttributeSwap.breachGain(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, false, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 4, 0.0F);
        assertEquals(8.0F * (0.15F * 4.0F), gain, 1e-4F, "the raw hit times the fraction Breach takes off");
        assertTrue(AttributeSwap.preferBreach(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED, 13,
                0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 4, 0.0F));
        assertEquals(0.0F, AttributeSwap.breachGain(AttributeSwap.SWORD_BASE_DAMAGE,
                AttributeSwap.SWORD_ATTACK_SPEED, 13, false, 0.0F, 0.0F, 0.0F, 4, 0.0F), 0.0F,
                "nothing to cut on an unarmoured target");
        assertFalse(AttributeSwap.preferBreach(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, 0.0F, 0.0F, 0.0F, 4, 0.0F));
        assertFalse(AttributeSwap.preferBreach(AttributeSwap.SWORD_BASE_DAMAGE, AttributeSwap.SWORD_ATTACK_SPEED,
                13, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0, 0.0F), "without Breach there is nothing to add");
    }

    /**
     * A shield raised for five ticks takes an axe hit and puts itself on cooldown for a hundred, and the mace
     * lands inside that window. Both hits are made from the same fall, so the second one keeps the whole fall
     * bonus even though the first spent the cooldown.
     */
    @Test
    void aStunSlamInOneFallKeepsTheFallBonus()
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(3.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0F, 0.0F, 0.0F, 0.0F, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0.0F, 0.0);
        sim.a.y = 8.0;
        sim.a.onGround = false;
        sim.a.vy = -0.3;
        sim.a.ticksSinceSwing = 100;
        SmashTiming.FallStunSlam plan = SmashTiming.planFallStunSlam(sim, 0, true, -100, 5,
                AttributeSwap.AXE_BASE_DAMAGE, AttributeSwap.AXE_ATTACK_SPEED, 100, 1, 40);
        assertTrue(plan.axeHitTick >= 0, "the axe reached the raised shield");
        assertEquals(1, plan.maceHitTick - plan.axeHitTick, "the mace landed a tick later, the hotbar change");
        assertTrue(plan.shieldAbsorbedAxe, "the shield ate the axe hit");
        assertEquals(0.0F, plan.axeDamage, 0.0F);
        assertTrue(plan.maceFallDistance > CombatMath.SMASH_FALL_THRESHOLD, "still falling: " + plan.maceFallDistance);
        assertTrue(plan.oneFall(), "the mace still had fall left: " + plan.axeFallDistance + " then "
                + plan.maceFallDistance);
        // The fall bonus at about four blocks with Density 5 is 16 + 10, and it is added after the charge
        // scaling, so a hit whose cooldown was just spent still carries all of it.
        float bonus = CombatMath.maceSmashBonus(plan.maceFallDistance, false, 5);
        assertEquals(CombatMath.armorAbsorb(10.0F * CombatMath.chargeDamageFactor(
                CombatMath.chargeScale(1, AttributeSwap.AXE_ATTACK_SPEED)) + bonus,
                NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0), plan.maceDamage, 1e-3F);
        assertTrue(plan.lands());
    }

    /**
     * The same two hits with nothing to fall from. A smash needs more than 1.5 blocks of fall, so on the
     * ground the plan never opens a window at all, which is what the fall is buying.
     */
    @Test
    void aStunSlamNeedsTheFall()
    {
        DuelSim sim = new DuelSim();
        sim.placeFacing(3.0);
        sim.a.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0F, 0.0F, 0.0F, 0.0F, 0.0);
        sim.b.setLoadout(8.0, 1.6, 0.0F, NETHERITE_ARMOR, NETHERITE_TOUGHNESS, 0.0F, 0.0);
        SmashTiming.FallStunSlam plan = SmashTiming.planFallStunSlam(sim, 0, true, -100, 5,
                AttributeSwap.AXE_BASE_DAMAGE, AttributeSwap.AXE_ATTACK_SPEED, 100, 1, 40);
        assertEquals(-1, plan.axeHitTick, "on the ground there is no smash to swing into the shield with");
        assertFalse(plan.lands());
    }
}
