package carpet.pvp.smp;

import carpet.pvp.sim.SurvivalPolicy;

/**
 * What an SMP bot does with its hands this tick, from the scored survival decision and nothing
 * else. Pure arithmetic over {@link SurvivalPolicy.Decision} and the bot's own state, so the
 * unit tests can ask what it would do at any health without a running game.
 *
 * <p>Three things sit on top of the model's choice. A totem that the model says has to be in the
 * offhand goes first, because it costs nothing and nothing else can be started while it is done. A
 * buff that is about to run out is topped up when the model would otherwise keep trading, which is
 * what a player does with the strength that runs out in five seconds. And the water bucket and the
 * cobweb are reactions to the ground rather than to the fight: water goes under the bot's own feet
 * when it is burning or falling further than is safe, and a cobweb goes under a target that is
 * running away while it is still inside the distance a block can be placed at.</p>
 */
public final class SmpPlan
{
    /** One thing the bot's hands can be doing. */
    public enum Move
    {
        FIGHT,
        /** Move a totem into the offhand, which costs no time at all. */
        TOTEM,
        EAT,
        HEAL_THROW,
        BUFF_THROW,
        DRINK_BUFF,
        MEND,
        PEARL,
        WEB,
        BUCKET,
        /** Swap a worn piece of armour for a spare, two inventory slots and nothing else. */
        SWAP_ARMOR;

        /**
         * True when the move is an item the game holds down for a while rather than one it spends on
         * the first tick. A held item has to keep its use action running, because the action pack
         * releases whatever the bot is using on the tick it does not execute it.
         */
        public boolean held()
        {
            return this == EAT || this == DRINK_BUFF;
        }

        /** True when the move needs the main hand and the view rather than only the inventory. */
        public boolean inHand()
        {
            return this != FIGHT && this != TOTEM && this != SWAP_ARMOR;
        }

        /** True when the move needs the view turned away from the target. */
        public boolean aimsOffTarget()
        {
            return this == HEAL_THROW || this == BUFF_THROW || this == MEND || this == PEARL
                    || this == WEB || this == BUCKET;
        }

        /** True while the bot may not start another move, because this one has it. */
        public boolean busy()
        {
            return this != FIGHT;
        }
    }

    /**
     * The buff the bot carries potions for and how much of it is left. The survival policy is asked
     * about this one buff at a time, so the style picks the one that runs out first.
     *
     * @param effect    which buff
     * @param ticksLeft ticks the running effect has left, 0 when there is none
     * @param window    ticks before it runs out at which a fresh one is worth having
     * @param count     potions of that buff in the bot's hotbar
     * @param amplifier amplifier those potions grant
     * @param duration  ticks those potions grant
     * @param splash    true when they are splash potions, false for drinkable ones
     */
    public record Buffs(SurvivalPolicy.Buff effect, int ticksLeft, int window, int count, int amplifier,
                        int duration, boolean splash)
    {
        /** No buff, nothing to top up. */
        public static Buffs none()
        {
            return new Buffs(SurvivalPolicy.Buff.STRENGTH, 0, 0, 0, 0, 0, true);
        }

        /** True while the bot has a potion and the running effect is close to running out. */
        public boolean due()
        {
            return count > 0 && ticksLeft <= window;
        }
    }

    /**
     * What the ground under the fight asks for. Both are read off the bot's own surroundings and
     * from what it has seen of the target, never from the target itself.
     *
     * @param web      a cobweb could be dropped under a target that is running
     * @param water    the bot wants water under its own feet
     * @param bucket   a water bucket is in the hotbar
     */
    public record Hazard(boolean web, boolean water, boolean bucket)
    {
        /** Nothing to do about the ground. */
        public static Hazard none()
        {
            return new Hazard(false, false, false);
        }
    }

    private final SmpGates gates;
    private Move move = Move.FIGHT;
    private String reason = "";

    public SmpPlan(SmpGates gates)
    {
        this.gates = gates;
    }

    public SmpGates gates()
    {
        return gates;
    }

    /** The move chosen on the last call to {@link #choose}. */
    public Move move()
    {
        return move;
    }

    /** Why it was chosen, in the words of the survival model. */
    public String reason()
    {
        return reason;
    }

    /**
     * Chooses the move for this tick.
     *
     * @param plan        what the survival model scored this tick
     * @param buffs       the buff the model was asked about, and what the bot carries for it
     * @param hazard      what the ground asks for
     * @param totemReady  false while a totem is still inside the wait after a pop, or before the first
     *                    one has been in the offhand long enough for a player to have moved it there
     */
    public Move choose(SurvivalPolicy.Decision plan, Buffs buffs, Hazard hazard, boolean totemReady)
    {
        move = planned(plan, buffs, hazard, totemReady);
        reason = plan.chosen.reason;
        return move;
    }

    private Move planned(SurvivalPolicy.Decision plan, Buffs buffs, Hazard hazard, boolean totemReady)
    {
        if (gates.totem() && plan.totemToOffhand && totemReady)
        {
            return Move.TOTEM;
        }
        if (gates.bucket() && hazard.water() && hazard.bucket())
        {
            return Move.BUCKET;
        }
        SurvivalPolicy.Action action = plan.chosen.action;
        if (action == SurvivalPolicy.Action.EAT_GOLDEN_APPLE
                || action == SurvivalPolicy.Action.EAT_ENCHANTED_GOLDEN_APPLE)
        {
            if (gates.eat())
            {
                return Move.EAT;
            }
        }
        else if (action == SurvivalPolicy.Action.THROW_HEALING_POTION)
        {
            if (gates.splashHeal())
            {
                return Move.HEAL_THROW;
            }
        }
        else if (action == SurvivalPolicy.Action.THROW_BUFF)
        {
            if (gates.buff())
            {
                return Move.BUFF_THROW;
            }
        }
        else if (action == SurvivalPolicy.Action.DRINK_BUFF)
        {
            if (gates.buff())
            {
                return Move.DRINK_BUFF;
            }
        }
        else if (action == SurvivalPolicy.Action.MEND_ARMOR)
        {
            if (gates.mend())
            {
                return Move.MEND;
            }
        }
        else if (action == SurvivalPolicy.Action.RETREAT)
        {
            if (gates.pearl())
            {
                return Move.PEARL;
            }
        }
        else if (action == SurvivalPolicy.Action.SWAP_ARMOR)
        {
            if (gates.armorSwap())
            {
                return Move.SWAP_ARMOR;
            }
        }
        Move buff = refresh(buffs);
        if (buff != Move.FIGHT)
        {
            return buff;
        }
        // The cobweb is the last thing a bot does with its hands: anything the fight itself calls for
        // is worth more than dropping one on a target that is running.
        if (gates.web() && hazard.web())
        {
            return Move.WEB;
        }
        return Move.FIGHT;
    }

    /**
     * Trading or closing the distance again, the bot still tops a buff up that is about to run out:
     * the strength that would expire mid-fight is worth more than the seconds of offense it costs.
     */
    private Move refresh(Buffs buffs)
    {
        if (!gates.buff() || !buffs.due())
        {
            return Move.FIGHT;
        }
        return buffs.splash() ? Move.BUFF_THROW : Move.DRINK_BUFF;
    }
}