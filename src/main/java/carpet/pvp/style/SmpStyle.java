package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.Perception;
import carpet.pvp.smp.SmpGates;
import carpet.pvp.smp.SmpGear;
import carpet.pvp.smp.SmpHands;
import carpet.pvp.smp.SmpPlan;
import carpet.pvp.smp.SmpSenses;
import carpet.pvp.sim.SurvivalPolicy;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.Random;

/**
 * The SMP style: a sword fighter that knows the survival half of the game.
 *
 * <p>The melee is {@link SwordStyle}, used as it is, because a netherite pot is still a sword fight.
 * What this style adds is everything that decides a long one: the golden apples, the splash at its
 * own feet, the strength and speed, the totem in the offhand and the wait after it pops, the spare
 * piece of armour and the bottle of experience, the pearl that buys room to heal in, and the cobweb
 * and the water. Every one of those is chosen by {@link SurvivalPolicy} from what the bot perceives,
 * and carried out by {@link SmpHands} through the body, so a bot can never do any of them sooner or
 * more precisely than a player with the same items.</p>
 *
 * <p>The survival decision projects the horizon once for every action the model offers, which is
 * simulated time and is booked against the same per-tick {@link BotBudget} the planner draws on. A
 * bot that gets no share of it this tick goes back to swinging its sword rather than standing still,
 * and says so in its stats.</p>
 */
public final class SmpStyle implements BotStyle
{
    /**
     * Simulated ticks the survival decision is booked at: the model projects the horizon once per
     * action it offers, and four is what it offers in a fight with everything in reach.
     */
    private static final int DECISION_TICKS = SmpSenses.HORIZON * 4;
    /** Ticks the buff window option means: a buff with less than this left is topped up. */
    public static final int DEFAULT_BUFF_WINDOW = 240;
    /** Ticks after a pop the retotem option means, which is SurvivalPolicy's own default. */
    public static final int DEFAULT_RETOTEM = SurvivalPolicy.DEFAULT_RETOTEM_DELAY;
    /** Blocks a retreat throw aims for. */
    public static final double DEFAULT_PEEL_BACK = 12.0D;
    /** Speed in blocks a tick above which the target counts as running rather than trading. */
    private static final double RUNNING = 0.05D;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final SmpGear gear = new SmpGear();
    private final SmpSenses senses = new SmpSenses();
    private final SurvivalPolicy policy = new SurvivalPolicy();
    private final SmpHands hands;
    private final SwordStyle sword;

    private SmpGates gates;
    private SmpPlan plan;
    private SurvivalPolicy.Decision decision;
    private int retotem = DEFAULT_RETOTEM;
    private int buffWindow = DEFAULT_BUFF_WINDOW;
    private double peelBack = DEFAULT_PEEL_BACK;

    public SmpStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.body = body;
        this.hands = new SmpHands(body, gear, senses);
        this.sword = new SwordStyle(bot, body, cfg, random);
        reconfigure(cfg);
    }

    @Override
    public void reconfigure(BotPvpConfig cfg)
    {
        SmpGates fresh = SmpGates.of(cfg.difficulty, cfg::flag);
        if (plan == null || !fresh.equals(gates))
        {
            gates = fresh;
            plan = new SmpPlan(gates);
            hands.setGuard(fresh.guard());
        }
        retotem = (int) Math.max(0.0D, cfg.number(SmpGates.OPT_RETOTEM));
        buffWindow = (int) Math.max(0.0D, cfg.number(SmpGates.OPT_BUFF_WINDOW));
        peelBack = Math.max(2.0D, cfg.number(SmpGates.OPT_PEEL_BACK));
        sword.reconfigure(cfg);
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        int reaction = cfg.reactionDelay + cfg.pingTicks;
        Perception.Snapshot seen = perception.target(reaction);
        if (seen == null || !seen.seen)
        {
            hands.release();
            body.hold(null, target);
            return;
        }
        senses.tick(bot);
        gear.scan(bot);
        senses.wantedTotem();

        SmpPlan.Buffs buffs = senses.buffs(gear, effectTicks(MobEffects.STRENGTH),
                effectTicks(MobEffects.SPEED), buffWindow);
        if (senses.potionCooldown() > 0)
        {
            // A potion is on a cooldown of its own once it has been thrown, and topping a buff up
            // against that cooldown would spend the bot's turns on potions that do nothing.
            buffs = new SmpPlan.Buffs(buffs.effect(), buffs.ticksLeft(), buffs.window(), 0,
                    buffs.amplifier(), buffs.duration(), buffs.splash());
        }
        BotBudget budget = BotBudget.instance();
        if (budget.share() < DECISION_TICKS)
        {
            body.stats().starvedTicks++;
            decision = null;
            hands.release();
            fight(perception, cfg, target, pack);
            return;
        }
        decision = policy.decide(senses.read(bot, seen, gear, buffs, retotem, reaction, peelBack));
        budget.spend(DECISION_TICKS);

        boolean totemReady = senses.ticksToRetotem(retotem) == 0 && senses.ticksToFirstTotem() == 0;
        SmpPlan.Move move = plan.choose(decision, buffs, hazard(seen), totemReady);
        SmpHands.Take take = hands.tick(move, buffs.effect(), gear.slotFor(move, buffs.effect()),
                peelBack, seen, target);
        switch (take)
        {
            case OWN ->
            {
                // The hands drove the view, the movement and the use themselves.
            }
            case HOLD -> body.hold(seen, target);
            case FIGHT -> fight(perception, cfg, target, pack);
        }
    }

    @Override
    public void disengage(BotBody body)
    {
        sword.disengage(body);
        hands.release();
        senses.reset();
        decision = null;
    }

    /** What the bot's hands have done, for the self-test and for the log of a long fight. */
    public SmpHands hands()
    {
        return hands;
    }

    /** The survival model's answer on the last tick it was asked, or null while it has been starved. */
    public SurvivalPolicy.Decision decision()
    {
        return decision;
    }

    /** The state the last decision was scored against, for the log of a long fight. */
    public SurvivalPolicy.Inputs inputs()
    {
        return senses.inputs();
    }

    /** The move the last decision came to, and why. */
    public String plan()
    {
        return plan == null ? "no plan yet" : plan.move() + " (" + plan.reason() + ")";
    }

    private void fight(Perception perception, BotPvpConfig cfg, LivingEntity target, EntityPlayerActionPack pack)
    {
        backToWeapon();
        sword.engage(body, perception, cfg, target, pack);
    }

    /**
     * Puts the sword back in the main hand after a heal or a throw, unless the sword style has asked
     * for something else to hit a shield with.
     */
    private void backToWeapon()
    {
        if (body.currentSlot() == gear.weaponSlot() || gear.weaponSlot() < 0)
        {
            return;
        }
        if (bot.getMainHandItem().is(ItemTags.SWORDS) || body.pendingSlot() == gear.weaponSlot())
        {
            return;
        }
        body.requestSlot(gear.weaponSlot());
    }

    /**
     * What the ground under the fight asks for. A cobweb needs a target that is running away from the
     * bot while it is still inside the distance a block can be placed at, which the bot works out from
     * the delayed snapshot; the water is the bot's own fire, its own lava and its own fall.
     */
    private SmpPlan.Hazard hazard(Perception.Snapshot seen)
    {
        double dx = seen.x - bot.getX();
        double dz = seen.z - bot.getZ();
        double gap = Math.sqrt(dx * dx + dz * dz);
        boolean web = false;
        if (gap >= SmpSenses.WEB_MIN && gap <= SmpSenses.WEB_MAX && gear.cobwebs > 0 && gap > 1.0E-6D)
        {
            web = (seen.vx * dx + seen.vz * dz) / gap > RUNNING;
        }
        return new SmpPlan.Hazard(web, senses.waterWanted(bot), gear.waterBucket);
    }

    private int effectTicks(Holder<MobEffect> effect)
    {
        MobEffectInstance running = bot.getEffect(effect);
        return running == null ? 0 : running.getDuration();
    }
}