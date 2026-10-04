package carpet.pvp.smp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import carpet.pvp.BotPvpConfig.Difficulty;
import carpet.pvp.sim.Durability;
import carpet.pvp.sim.SurvivalPolicy;
import carpet.pvp.sim.SurvivalPolicy.Action;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the style does with its hands for each of the survival model's decisions. The model's own
 * scoring is {@link SurvivalPolicyTest}'s business; this is only the mapping from its answer to a
 * thing a bot can hold, and the gates that switch techniques off.
 */
class SmpPlanTest
{
    private final SurvivalPolicy policy = new SurvivalPolicy();
    private final SmpPlan.Hazard nothing = SmpPlan.Hazard.none();
    private final SmpPlan.Buffs noBuff = SmpPlan.Buffs.none();

    private static Predicate<String> all(String... off)
    {
        Map<String, String> options = new HashMap<>();
        for (String option : off)
        {
            options.put(option, "false");
        }
        return key -> !options.containsKey(key);
    }

    private SmpPlan expert()
    {
        return new SmpPlan(SmpGates.of(Difficulty.EXPERT, all()));
    }

    /**
     * A fight at melee range with a hit every few ticks, which is what the model is meant to be scored
     * against: the rate of health loss a sword exchange actually costs.
     */
    private SurvivalPolicy.Inputs trade(float health, float rate, int goldenApples)
    {
        SurvivalPolicy.Inputs in = new SurvivalPolicy.Inputs();
        in.health = health;
        in.horizonTicks = 40;
        in.beingHit = true;
        in.distance = 2.0D;
        in.incomingDamagePerTick = rate;
        in.outgoingDamagePerTick = 0.5F;
        in.incomingHitDamage = 11.0F;
        in.armorValue = 20.0F;
        in.armorToughness = 3.0F;
        in.enemyHealth = 20.0F;
        in.goldenApples = goldenApples;
        in.healingPotions = 6;
        in.pearls = 0;
        in.totems = 3;
        in.experienceBottles = 8;
        return in;
    }

    @Test
    void theTotemGoesInFirstWhenTheModelPredictsLethal()
    {
        SurvivalPolicy.Inputs in = trade(4.0F, 0.19F, 2);
        in.armorValue = 0.0F;
        in.totemInOffhand = false;
        in.ticksSinceTotemPop = 40;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertTrue(decision.lethalBurst, "four health through no armour is a lethal burst");
        assertTrue(decision.totemToOffhand);
        assertEquals(SmpPlan.Move.TOTEM, expert().choose(decision, noBuff, nothing, true));
    }

    @Test
    void aTotemInsideItsWaitIsNotPutBack()
    {
        // The model still wants a totem in the offhand, but a bot that swaps it on the same tick it
        // popped has done something no player does, so the move falls through to the model's own
        // second choice until the wait is over.
        SurvivalPolicy.Inputs in = trade(4.0F, 0.19F, 2);
        in.armorValue = 0.0F;
        in.totemInOffhand = false;
        in.ticksSinceTotemPop = 2;
        in.retotemDelayTicks = 20;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertTrue(decision.totemToOffhand);
        assertEquals(18, decision.ticksToRetotem);
        SmpPlan.Move move = expert().choose(decision, noBuff, nothing, false);
        assertTrue(move != SmpPlan.Move.TOTEM, "the totem went back in before its wait was over");
        // Once the wait is over the same state puts it straight in.
        assertEquals(SmpPlan.Move.TOTEM, expert().choose(decision, noBuff, nothing, true));
    }

    @Test
    void anEatIsOnlyTakenWhenTheModelAsksForOne()
    {
        // One gap, a slow drain and twelve health: the apple's regen and absorption land inside the
        // window, which is the only shape in which a thirty two tick eat is worth more than the
        // offence it costs. A bot with a stack of gaps is held back by the model's reserve rule, so
        // the eat here is the one-gap case.
        SurvivalPolicy.Decision healing = policy.decide(trade(12.0F, 0.19F, 1));
        assertEquals(Action.EAT_GOLDEN_APPLE, healing.chosen.action);
        assertEquals(SmpPlan.Move.EAT, expert().choose(healing, noBuff, nothing, true));
        // A bot that may not eat keeps trading instead.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.EXPERT, all(SmpGates.OPT_EAT)))
                .choose(healing, noBuff, nothing, true));
        // And a comfortable trade is left alone.
        SurvivalPolicy.Decision comfortable = policy.decide(trade(20.0F, 0.15F, 2));
        assertEquals(Action.KEEP_FIGHTING, comfortable.chosen.action);
        assertEquals(SmpPlan.Move.FIGHT, expert().choose(comfortable, noBuff, nothing, true));
    }

    @Test
    void aSplashIsThrownWhenEatingIsTooSlow()
    {
        // Four health against a fast drain: a thirty two tick apple does not finish in time, and a
        // splash at the bot's own feet heals eight after five ticks of swap and aim.
        SurvivalPolicy.Inputs in = trade(4.0F, 0.25F, 2);
        in.goldenApples = 0;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(Action.THROW_HEALING_POTION, decision.chosen.action);
        assertEquals(SmpPlan.Move.HEAL_THROW, expert().choose(decision, noBuff, nothing, true));
        // Splash throwing is an average fighter's technique.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.CASUAL, all()))
                .choose(decision, noBuff, nothing, true));
    }

    @Test
    void aPearlIsThrownOnlyWhenThereIsNothingLeftToHealWith()
    {
        // Out of gaps and out of potions the only thing left is the room a pearl buys.
        SurvivalPolicy.Inputs in = trade(8.0F, 0.30F, 2);
        in.goldenApples = 0;
        in.healingPotions = 0;
        in.pearls = 3;
        SurvivalPolicy.Decision decision = policy.decide(in);
        assertEquals(Action.RETREAT, decision.chosen.action);
        assertEquals(SmpPlan.Move.PEARL, expert().choose(decision, noBuff, nothing, true));
        // A bot that may not pearl keeps trading.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.AVERAGE, all()))
                .choose(decision, noBuff, nothing, true));
        // The same fight with no pearl at all is not a retreat, it is not even offered.
        SurvivalPolicy.Inputs noPearl = trade(8.0F, 0.30F, 2);
        noPearl.goldenApples = 0;
        noPearl.healingPotions = 0;
        assertTrue(!policy.decide(noPearl).offered(Action.RETREAT));
    }

    @Test
    void armourAndMendingBecomeTheirOwnMoves()
    {
        // A helmet two durability from breaking, with a spare to put on.
        SurvivalPolicy.Inputs worn = trade(20.0F, 0.15F, 2);
        worn.goldenApples = 0;
        worn.totems = 0;
        worn.armorDamage[Durability.HEAD] = Durability.maxDamage(Durability.HEAD,
                Durability.NETHERITE_MULTIPLIER) - 2;
        worn.spareArmor[Durability.HEAD] = Durability.maxDamage(Durability.HEAD, Durability.NETHERITE_MULTIPLIER);
        worn.unbreakingLevel = 3;
        SurvivalPolicy.Decision decision = policy.decide(worn);
        assertEquals(Action.SWAP_ARMOR, decision.chosen.action);
        assertEquals(SmpPlan.Move.SWAP_ARMOR, expert().choose(decision, noBuff, nothing, true));
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.CASUAL, all()))
                .choose(decision, noBuff, nothing, true));

        // Mending is only offered out of reach, with a bottle in hand and health to spare.
        SurvivalPolicy.Inputs mendable = trade(20.0F, 0.15F, 2);
        mendable.goldenApples = 0;
        mendable.healingPotions = 0;
        mendable.beingHit = false;
        mendable.distance = 9.0D;
        mendable.travelTicks = 10;
        mendable.armorDamage[Durability.CHEST] = 40;
        mendable.mending[Durability.CHEST] = 1;
        SurvivalPolicy.Decision mending = policy.decide(mendable);
        assertEquals(Action.MEND_ARMOR, mending.chosen.action);
        assertEquals(SmpPlan.Move.MEND, expert().choose(mending, noBuff, nothing, true));
        // Mending with bottles is a skilled technique.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.AVERAGE, all()))
                .choose(mending, noBuff, nothing, true));
    }

    @Test
    void aBuffIsToppedUpBeforeItRunsOutEvenWhileTrading()
    {
        // Comfortable at full health with the enemy in reach: the model only wants to keep trading,
        // but the strength is twenty seconds from running out and there is a splash of it to hand.
        SurvivalPolicy.Decision decision = policy.decide(trade(20.0F, 0.15F, 2));
        assertEquals(Action.KEEP_FIGHTING, decision.chosen.action);
        SmpPlan.Buffs running = new SmpPlan.Buffs(SurvivalPolicy.Buff.STRENGTH, 40, 240, 4, 1, 1800, true);
        assertEquals(SmpPlan.Move.BUFF_THROW, expert().choose(decision, running, nothing, true));
        // A drinkable potion is drunk rather than thrown.
        SmpPlan.Buffs drinkable = new SmpPlan.Buffs(SurvivalPolicy.Buff.SPEED, 40, 240, 2, 1, 1800, false);
        assertEquals(SmpPlan.Move.DRINK_BUFF, expert().choose(decision, drinkable, nothing, true));
        // With plenty of it left there is nothing to do about it.
        SmpPlan.Buffs plenty = new SmpPlan.Buffs(SurvivalPolicy.Buff.STRENGTH, 2000, 240, 4, 1, 1800, true);
        assertEquals(SmpPlan.Move.FIGHT, expert().choose(decision, plenty, nothing, true));
        // And with no potion of it at all, still nothing.
        SmpPlan.Buffs none = new SmpPlan.Buffs(SurvivalPolicy.Buff.STRENGTH, 40, 240, 0, 1, 1800, true);
        assertEquals(SmpPlan.Move.FIGHT, expert().choose(decision, none, nothing, true));
        // Ticking a buff up is a skilled technique.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.AVERAGE, all()))
                .choose(decision, running, nothing, true));
    }

    @Test
    void aSlowHealIsNotPostponedForABuff()
    {
        // Eating and topping up at the same time is two things at once, so the heal wins.
        SurvivalPolicy.Decision decision = policy.decide(trade(12.0F, 0.19F, 1));
        assertEquals(Action.EAT_GOLDEN_APPLE, decision.chosen.action);
        SmpPlan.Buffs running = new SmpPlan.Buffs(SurvivalPolicy.Buff.STRENGTH, 40, 240, 4, 1, 1800, true);
        assertEquals(SmpPlan.Move.EAT, expert().choose(decision, running, nothing, true));
    }

    @Test
    void waterGoesUnderItsOwnFeetBeforeAnythingElse()
    {
        // Burning out of a long fall matters more than the fight the model was scoring.
        SurvivalPolicy.Decision decision = policy.decide(trade(12.0F, 0.19F, 1));
        SmpPlan.Hazard fire = new SmpPlan.Hazard(false, true, true);
        assertEquals(SmpPlan.Move.BUCKET, expert().choose(decision, noBuff, fire, true));
        // With no bucket in the hotbar there is nothing the bot can do about it.
        SmpPlan.Hazard dry = new SmpPlan.Hazard(false, true, false);
        assertEquals(SmpPlan.Move.EAT, expert().choose(decision, noBuff, dry, true));
        // A beginner has no bucket technique.
        assertEquals(SmpPlan.Move.EAT, new SmpPlan(SmpGates.of(Difficulty.BEGINNER, all()))
                .choose(decision, noBuff, fire, true));
    }

    @Test
    void aCobwebGoesDownUnderATargetThatIsRunning()
    {
        SurvivalPolicy.Decision decision = policy.decide(trade(20.0F, 0.15F, 2));
        SmpPlan.Hazard running = new SmpPlan.Hazard(true, false, true);
        assertEquals(SmpPlan.Move.WEB, expert().choose(decision, noBuff, running, true));
        assertEquals(SmpPlan.Move.FIGHT, expert().choose(decision, noBuff, nothing, true));
        // A beginner has no cobweb technique.
        assertEquals(SmpPlan.Move.FIGHT, new SmpPlan(SmpGates.of(Difficulty.BEGINNER, all()))
                .choose(decision, noBuff, running, true));
    }

    @Test
    void thePlanRemembersWhyItChoseWhatItChose()
    {
        SmpPlan plan = expert();
        SurvivalPolicy.Decision decision = policy.decide(trade(12.0F, 0.19F, 1));
        assertEquals(SmpPlan.Move.EAT, plan.choose(decision, noBuff, nothing, true));
        assertEquals(plan.move(), SmpPlan.Move.EAT);
        assertEquals(decision.chosen.reason, plan.reason());
        assertTrue(plan.reason().contains("regen"), plan.reason());
    }

    @Test
    void onlyTheMovesThatHoldSomethingNeedTheHandToBeCarryingIt()
    {
        for (SmpPlan.Move move : SmpPlan.Move.values())
        {
            switch (move)
            {
                case EAT, DRINK_BUFF ->
                {
                    assertTrue(move.inHand(), move.name());
                    assertTrue(!move.aimsOffTarget(), move.name() + " keeps looking at the target");
                }
                case HEAL_THROW, BUFF_THROW, MEND, PEARL, WEB, BUCKET ->
                {
                    assertTrue(move.inHand(), move.name());
                    assertTrue(move.aimsOffTarget(), move.name() + " has to look away to aim it");
                }
                case TOTEM, SWAP_ARMOR ->
                {
                    assertTrue(!move.inHand(), move.name() + " is an inventory move");
                }
                default ->
                {
                    assertTrue(!move.inHand(), move.name());
                    assertTrue(!move.aimsOffTarget(), move.name());
                }
            }
        }
        assertTrue(!SmpPlan.Move.FIGHT.busy());
        assertTrue(SmpPlan.Move.EAT.busy());
        assertTrue(SmpPlan.Move.TOTEM.busy());
    }
}