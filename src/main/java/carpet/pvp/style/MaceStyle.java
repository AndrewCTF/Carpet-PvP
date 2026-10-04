package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.mace.MaceActions;
import carpet.pvp.mace.MaceBreachSwap;
import carpet.pvp.mace.MaceChoice;
import carpet.pvp.mace.MaceChoice.Technique;
import carpet.pvp.mace.MaceGear;
import carpet.pvp.mace.MaceLaunch;
import carpet.pvp.mace.MaceSwap;
import carpet.pvp.sim.AttributeSwap;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.EngagePlanner;
import carpet.pvp.sim.SmashTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Mace fighting: height first, the hit on the way down.
 *
 * <p>A mace does nothing on the ground, so the fight is a choice between getting off the ground and
 * eating hits while up there. {@link EngagePlanner} decides, on the same duel model the sword style
 * plans with, which of the four entries — walk in, wind charge jump, pearl or elytra dive — beats the
 * others against the target the bot can see, and {@link SmashTiming} says when the resulting arc puts a
 * charged smash on the target and how much it hurts through its armour. The bot then spends its turns
 * turning the view down onto its own feet, throws the launch through the body's hotbar and click rules,
 * steers towards where the target will be and swings on the tick the model gave, with whichever of its
 * two maces the target's armour is better served by. Between launches it hands the tick back to the
 * sword style, and a launch that will not land anything ends with the charge that keeps the fall
 * harmless.</p>
 *
 * <p>The hit itself is made off another item's cooldown. The game only re-reads an item's attributes
 * and only clears the swing timer once a tick, so a hotbar change on the tick of the swing lands the
 * new item's fall bonus, enchantments and Breach on top of the base damage and the charge of the item
 * the hand was holding: {@link AttributeSwap} prices that, and the bot holds the sword between hits and
 * puts the mace in only for the tick of the swing. On the ground, where there is no fall to smash from,
 * the same trick is used the other way round, with the Breach mace in the hand for the swing and the
 * sword's base damage and its thirteen tick cadence behind it. Every technique has an option of its own
 * and is gated by the difficulty preset.</p>
 */
public final class MaceStyle implements BotStyle
{
    /** What the bot is doing: on the ground, throwing the launch, falling onto the target, or climbing for a dive. */
    private enum Phase { MELEE, AIM, FLIGHT, CLIMB }

    /** Ticks between two launch plans while the bot fights on the ground. */
    private static final int PLAN_INTERVAL = 5;
    /** Ticks a launch plan may simulate. */
    private static final int PLAN_TICKS = 40;
    /** Ticks between two reads of the bot's own hotbar. */
    private static final int GEAR_REFRESH = 20;
    /** Ticks the view may take to come round to straight down before the bot gives the launch up. */
    private static final int AIM_TIMEOUT = 16;
    /** View pitch within which the wind charge counts as aimed at the bot's own feet, degrees. */
    private static final double AIM_PITCH = 70.0;
    /** Ticks a launch may take before it counts as lost. */
    private static final int FLIGHT_TIMEOUT = 70;
    /** Ticks the stun slam plan may run. */
    private static final int STUN_TICKS = 40;
    /** Horizontal distance within which a glider folds its wings and drops onto the target. */
    private static final double DIVE_CLOSE = 1.5;
    /** Height above the ground within which the bot throws the charge that saves it from the fall. */
    private static final double SAVE_HEIGHT = 2.3;
    /** Wind charges one flight may throw below the ground, the chain or the save, but never both. */
    private static final int FLIGHT_CHARGES = 1;
    /** How far off a target the bot still counts a launch as able to land on it. */
    private static final double CHAIN_RANGE = 4.5;
    /** Ticks ahead the bot steers the target's movement to, the rest of the descent is too close to call. */
    private static final double STEER_TICKS = 10.0;
    /** Ticks ahead the bot puts its crosshair, which is as far as the descent can be called. */
    private static final double LEAD_TICKS = 6.0;
    /** How near the spot it is aiming for the bot stops pushing forward. */
    private static final double STEER_STOP = 0.9;
    /** How much earlier than the last block the bot asks for the charge, which takes a tick to arrive. */
    private static final double HOTBAR_TICK = 1.2;
    /** Blocks below the bot the ground is looked for at. */
    private static final int GROUND_SCAN = 4;
    /**
     * Horizontal gap a launch is thrown at. The model reaches for the wind charge from about four blocks
     * out and is happy to take it from closer still as long as the cooldown is spent, so the bot keeps the
     * target at the far end of that range rather than trading hits from inside it.
     */
    private static final double LAUNCH_MIN_GAP = 1.2;
    private static final double LAUNCH_MAX_GAP = 7.0;
    /** Horizontal gap the bot holds while it winds a launch up. */
    private static final double HOLD_RANGE = 4.0;
    /** Gap inside which a launch is out of the question, so the bot trades hits instead of backing off. */
    private static final double FORCED_GAP = 2.6;
    /** Ticks of cooldown a mace needs before a swing crits, the gate of its own attack speed. */
    private static final int MACE_GATE_TICKS = CombatMath.minTicksForGate(SmashTiming.MACE_ATTACK_SPEED);
    /** Height above the target an elytra dive climbs to before it turns in, in blocks. */
    private static final double DIVE_HEIGHT = 7.0;
    /**
     * Gap a dive is opened at. A climb takes longer than the fight it is meant to win, so it is only worth
     * starting against a target far enough away that the launch would cost the bot several of them.
     */
    private static final double DIVE_NEAR = 11.0;
    private static final double DIVE_FAR = 34.0;
    /** Ticks one dive may spend putting the wings on, lighting rockets and getting above the target. */
    private static final int CLIMB_TIMEOUT = 120;
    /** Ticks between two rockets, which is how long a launched firework takes to go off. */
    private static final int ROCKET_GAP = 3;
    /** Ticks the wings stay folded before the bot is sure it is really falling onto the target. */
    private static final int FOLD_SETTLE = 2;
    /** Gap within which a dive closes its wings and drops the rest of the way. */
    private static final double DIVE_CLOSE_DROP = 2.0;
    /** Ticks the axe of a stun slam inside one fall is allowed to take to reach a blocking target. */
    private static final int FALL_STUN_TICKS = 40;
    /** Ticks between the axe that opens the shield window and the mace that follows it into the same fall. */
    private static final int STUN_GAP = 1;
    /** Height above the target a target has to be at before the bot gets out from under it rather than climbing into it. */
    private static final double DREAD_HEIGHT = 4.0;
    /** Ticks of warning the bot gives an airborne target before it starts moving off the fall line. */
    private static final int DREAD_TICKS = 4;

    private final EntityPlayerMPFake bot;
    private final BotStats stats;
    private final MaceGear gear;
    private final MaceActions actions;
    private final MaceBreachSwap ground;
    private final DuelSim sim = new DuelSim();
    private final EngagePlanner.Threat threat = new EngagePlanner.Threat();
    private final Perception.Snapshot feet = new Perception.Snapshot();

    private final BotStyle melee;

    private Phase phase = Phase.MELEE;
    private int entry = EngagePlanner.WALK_IN;
    private int entryTicks;
    private double entryFall;
    private EntityPlayerActionPack pack;
    private int planCountdown;
    private int gearCountdown;
    private int aimTicks;
    private int flightTicks;
    private int smashTick = -1;
    private int hitsAtLaunch;
    private int shieldUpTicks;
    private boolean launching;
    private boolean stunWindow;
    private boolean chained;
    private int flightCharges;
    private boolean airborne;
    private int bounceLeft;
    /** Ticks the dive has been going, and the tick its last rocket went off on. */
    private int climbTicks;
    private int rocketTick = -ROCKET_GAP;
    /** Ticks the wings have been folded, which is how long the fall distance needs before a smash counts. */
    private int folded;
    /** Ticks the axe of a stun slam inside one fall landed on, or -1 while there is no window open. */
    private int fallStunAxe = -1;
    /** Ticks a target has been seen above the bot with a mace in its hand. */
    private int dread;
    /**
     * Ticks since this bot last clicked, which is what its attack cooldown runs on. Perception cannot say:
     * it reads a swing off the attack strength scale dropping, and every hotbar change drops that scale
     * too, so a bot that swaps between its mace and its sword would look like it is swinging every tick.
     */
    private int sinceClick;
    private int clicks;
    /** Height the duel model's own ground sits at, which is zero, relative to the world the bot is in. */
    private double ground0;

    public MaceStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.gear = new MaceGear(bot);
        this.actions = new MaceActions(body);
        this.ground = new MaceBreachSwap(body, gear);
        this.melee = StyleIndex.create(BotPvpConfig.CombatStyle.MELEE, bot, body, cfg, random);
    }

    @Override
    public void reconfigure(BotPvpConfig cfg)
    {
        melee.reconfigure(cfg);
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        Perception.Snapshot seen = perception.target(cfg.reactionDelay + cfg.pingTicks);
        Perception.Snapshot me = perception.self();
        if (seen == null || !seen.seen || !gear.hasMace())
        {
            body.hold(null, target);
            return;
        }
        this.pack = pack;
        ground0 = groundOf(me);
        sinceClick = stats.clicks == clicks ? sinceClick + 1 : 0;
        clicks = stats.clicks;
        if (--gearCountdown <= 0)
        {
            gear.refresh();
            gearCountdown = GEAR_REFRESH;
        }
        if (seen.blocking)
        {
            shieldUpTicks++;
        }
        else
        {
            shieldUpTicks = 0;
        }
        dread = dreaded(cfg, me, seen) ? dread + 1 : 0;
        switch (phase)
        {
            case MELEE -> melee(body, perception, cfg, target, pack, me, seen);
            case AIM -> aim(body, target, me, seen);
            case FLIGHT -> flight(body, cfg, target, me, seen);
            case CLIMB -> climb(body, cfg, target, me, seen);
        }
    }

    @Override
    public void disengage(BotBody body)
    {
        phase = Phase.MELEE;
        launching = false;
        stunWindow = false;
        entry = EngagePlanner.WALK_IN;
        entryTicks = 0;
        smashTick = -1;
        flightTicks = 0;
        climbTicks = 0;
        folded = 0;
        fallStunAxe = -1;
        if (pack != null && pack.isNavEnabled())
        {
            pack.stopNavigation();
        }
        actions.glide(false);
        actions.fold();
    }

    // ===== on the ground =====

    /**
     * The ground phase. The bot asks the model which entry beats walking in and waits for it without
     * swinging, since every swing would spend the cooldown the smash needs. Against a target that is
     * blocking, the axe opens the window a smash cannot open for itself, and when the target's armour is
     * heavy enough to be worth cutting the bot trades hits with the Breach mace on the tick of each swing.
     */
    private void melee(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (horizontal(me, seen) > cfg.plannerRange)
        {
            approach(body, pack, cfg, target, seen);
            return;
        }
        if (pack.isNavEnabled())
        {
            pack.stopNavigation();
        }
        if (dread > 0)
        {
            // Something with a mace is coming down on the bot's head. Standing under it is how a mace fight is
            // lost, so the bot gives the fall line up rather than trading.
            evade(body, cfg, target, me, seen);
            return;
        }
        if ((seen.blocking || target.isBlocking()) && opensWindow(cfg, me, seen))
        {
            breakShield(body, cfg, target, me, seen);
            return;
        }
        if (stunWindow && horizontal(me, seen) <= cfg.meleeRange)
        {
            // Inside its own reach a launch has nothing to arc over, so the bot spends the window the axe
            // opened on the mace it is already holding rather than standing off to start one.
            maceRush(body, cfg, target, me, seen);
            return;
        }
        boolean winding = winding(cfg, me, seen);
        if (breachGround(cfg, me, seen))
        {
            breachExchange(body, cfg, target, me, seen);
            return;
        }
        if (beginDive(cfg, me, seen))
        {
            // A dive is opened with the wings still in the bag and the bot still on the ground, so it starts
            // with the same wind charge launch every other entry does and puts the elytra on in the air.
            entry = EngagePlanner.WIND_CHARGE;
            entryTicks = MaceLaunch.ARC_TICKS;
            entryFall = MaceLaunch.APEX[0] / 2.0;
            launching = true;
            phase = Phase.AIM;
            aimTicks = 0;
            aim(body, target, me, seen);
            return;
        }
        if (winding || planCountdown-- <= 0)
        {
            planCountdown = winding ? 0 : PLAN_INTERVAL;
            plan(cfg, me, seen);
        }
        if (launching)
        {
            if (!beginAim(body, target, me, seen))
            {
                hold(cfg, body, target, me, seen);
            }
            return;
        }
        if (winding && (sinceClick < MACE_GATE_TICKS || horizontal(me, seen) >= FORCED_GAP))
        {
            // The launch wants a charged mace and every swing would spend the charge again, so the bot
            // stands at the range the model launches from and lets its cooldown come up. It only gives up
            // the standoff when the target is inside its own reach, where a launch could not reach at all.
            hold(cfg, body, target, me, seen);
            return;
        }
        melee.engage(body, perception, cfg, target, pack);
    }

    /**
     * Whether the bot should give an incoming mace fall its head space. A target with a mace in its hand that
     * is above the bot and coming down is worth about twenty of the bot's own health, and nothing the bot can
     * do while standing under it comes close, so it backs off or raises a shield instead.
     */
    private boolean dreaded(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        return allowed(cfg, Technique.READ) && seen.weapon == Perception.Weapon.MACE && !me.onGround
                && seen.y > me.y + 1.0 && seen.vy < 0.0 && horizontal(me, seen) <= cfg.meleeRange * 1.5;
    }

    /** A step out of the fall line, or a shield up if there is nowhere to go. */
    private void evade(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        want(body, heldMace(cfg, me, seen));
        double gap = horizontal(me, seen);
        int side = Math.abs(gap) < 0.2 ? 1 : seen.x > me.x ? 1 : -1;
        int action = DuelSim.action(gap > cfg.meleeRange ? 1 : 0, side, me.onGround, false, false);
        body.tick(seen, target, action, dread > DREAD_TICKS && body.hasShield());
    }

    /**
     * Whether the fight has come down to trading on the ground: a Breach mace in the kit, a charger to collect
     * the cooldown under, armour for Breach to cut, and no wind charge left to launch with. A charge in hand
     * makes the launch worth more than any ground hit, so the swap waits for the last of them.
     */
    private boolean breachGround(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.BREACH_SWAP) || stunWindow || launching || !me.onGround || dread > 0
                || gear.charges() > 0)
        {
            return false;
        }
        double near = horizontal(me, seen);
        return near <= cfg.meleeRange && ground.worthIt(seen.armor, seen.armorToughness, seen.epf);
    }

    /**
     * The ground exchange: the hand holds the sword, whose thirteen tick cadence is what the fight runs on,
     * and the Breach mace goes in for the tick of each charged swing so the hit is made through armour it has
     * cut while carrying the sword's base damage. The hotbar change lands on the next body tick, which is the
     * tick before the swing, and the sword goes back in on the one after it.
     */
    private void breachExchange(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int breaker = ground.hitSlot(seen.armor, seen.armorToughness, seen.epf);
        boolean out = breaker >= 0 && breaker == body.currentSlot();
        fill(sim.a, me);
        fillCharger(sim.a);
        boolean swing = out && sinceClick >= sim.a.gateTicks && body.canHit(target);
        want(body, swing ? ground.chargeSlot() : breaker);
        // The exchange closes to reach and then stands its ground: backing off would take the bot out of the
        // window the exchange is for and hand the fight back to the launch it has no charge left for.
        double near = horizontal(me, seen);
        int forward = near > cfg.meleeRange * 0.8 ? 1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, forward > 0, swing), false);
    }

    /**
     * Whether a dive is worth opening. It needs the wings and something to climb with, and it is only worth
     * the climb when the target is far enough away that walking to it would cost more than the flight.
     */
    private boolean beginDive(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.ELYTRA) || stunWindow || !me.onGround || dread > 0
                || (gear.elytraSlot() < 0 && !gear.wearingElytra())
                || (gear.rocketSlot() < 0 && gear.rockets() < 1) || !allowed(cfg, Technique.ROCKET))
        {
            return false;
        }
        double gap = horizontal(me, seen);
        return gap >= DIVE_NEAR && gap <= DIVE_FAR && gear.charges() > 0 && !winding(cfg, me, seen);
    }

    /**
     * Whether the bot is close enough for a launch to be worth planning. Inside that window it stops
     * swinging, so that its cooldown is full by the time the arc comes down, which is what the model asks
     * of it: with a half spent cooldown the launch is worth more than walking in at any range at all.
     */
    private boolean winding(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.WIND_CHARGE) || gear.chargeSlot() < 0 || !gear.chargeReady())
        {
            return false;
        }
        double gap = horizontal(me, seen);
        return gap >= LAUNCH_MIN_GAP && gap <= LAUNCH_MAX_GAP;
    }

    /**
     * Walks the last stretch with the navigation controller while the target is out of the planner's
     * range, the way the sword style does, and lets the body keep the view on it.
     */
    private void approach(BotBody body, EntityPlayerActionPack pack, BotPvpConfig cfg, LivingEntity target,
            Perception.Snapshot seen)
    {
        if (!target.getUUID().equals(pack.getNavChaseTarget()))
        {
            pack.setNavChase(target.getUUID(), false, cfg.meleeRange, cfg.attackCooldown);
            // The chase would attack on its own, and only the body may decide that a click is a hit.
            pack.start(EntityPlayerActionPack.ActionType.ATTACK, null);
        }
        body.hold(seen, target);
    }

    /**
     * Walks in with the axe and swings at a raised shield, the only hit that takes it out of the target's
     * hands. The swing waits for a charged axe and for the crosshair to be on the target, because the
     * body's strike is the only thing that decides a hit landed.
     */
    private void breakShield(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int axe = gear.axeSlot();
        int breaks = stats.shieldBreaks;
        want(body, axe);
        fill(sim.a, me);
        fillAxe(sim.a);
        boolean swing = sinceClick >= sim.a.gateTicks && body.canHit(target);
        int forward = horizontal(me, seen) > cfg.meleeRange ? 1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, forward > 0, swing), false);
        if (stats.shieldBreaks > breaks)
        {
            // The shield is on cooldown now, so a smash has a window to land in.
            stunWindow = true;
            launching = true;
            entry = EngagePlanner.WIND_CHARGE;
            // A launch the model did not plan for, so the smash is given the length of an arc.
            entryTicks = MaceLaunch.ARC_TICKS;
            entryFall = MaceLaunch.APEX[0] / 2.0;
        }
    }

    /**
     * Walks in and swings the mace while the shield the axe took down is still on cooldown. Nothing else
     * is going to get past the target for the length of that window, so the bot takes it.
     */
    private void maceRush(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int hits = stats.hits;
        fill(sim.a, me);
        fillMace(sim.a, cfg);
        want(body, heldMace(cfg, me, seen));
        boolean swing = sinceClick >= sim.a.gateTicks && body.canHit(target);
        int forward = horizontal(me, seen) > cfg.meleeRange * 0.8 ? 1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, forward > 0, swing), false);
        if (stats.hits > hits)
        {
            // The window is spent; back to the launch rhythm.
            stunWindow = false;
            launching = false;
        }
    }

    /**
     * Whether the two hits of a stun slam fit: the axe puts the shield on cooldown for a hundred ticks and
     * the mace has to come down inside them. The model walks the bot in and says which tick each lands on.
     */
    private boolean opensWindow(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (stunWindow || !allowed(cfg, Technique.STUN_SLAM) || gear.axeSlot() < 0)
        {
            return false;
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        fillMace(sim.a, cfg);
        return SmashTiming.planStunSlam(sim, 0, true, -shieldUpTicks, STUN_TICKS).lands();
    }

    /**
     * Asks the model which entry beats walking in and remembers it until the bot has launched. A plan that
     * costs more simulated ticks than this bot has a share of is dropped and the ground phase goes on.
     */
    private void plan(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        System.out.println("[macedbg] PLAN gap=" + horizontal(me, seen) + " starved=" + stats.starvedTicks
                + " calls=" + stats.plannerCalls + " entry=" + entry + " sinceClick=" + sinceClick);
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < PLAN_TICKS)
        {
            stats.starvedTicks++;
            return;
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        fillMace(sim.a, cfg);
        fillThreat(seen);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, gear.breachLevel(), threat, PLAN_TICKS);
        budget.spend(PLAN_TICKS);
        stats.plannerCalls++;
        stats.simulatedTicks += PLAN_TICKS;
        EngagePlanner.Option best = EngagePlanner.best(options);
        if (best == null || best == options[EngagePlanner.WALK_IN] || !wanted(cfg, best.approach))
        {
            return;
        }
        entry = best.approach;
        entryTicks = best.ticks;
        entryFall = best.fallDistance;
        launching = true;
    }

    /** Whether this bot's difficulty knows the given entry and it has what it takes to fly it. */
    private boolean wanted(BotPvpConfig cfg, int approach)
    {
        return switch (approach)
        {
            case EngagePlanner.WIND_CHARGE -> allowed(cfg, Technique.WIND_CHARGE) && gear.chargeSlot() >= 0;
            case EngagePlanner.PEARL -> allowed(cfg, Technique.PEARL) && gear.pearlSlot() >= 0;
            case EngagePlanner.ELYTRA -> allowed(cfg, Technique.ELYTRA) && gear.wearingElytra();
            default -> false;
        };
    }

    /**
     * Standing off for a launch: the mace stays out, the gap is held at the range the model launches from
     * and nothing is swung, so the cooldown the smash needs is still there when the arc comes down.
     */
    private void hold(BotPvpConfig cfg, BotBody body, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        want(body, heldMace(cfg, me, seen));
        double gap = horizontal(me, seen);
        int forward = gap > HOLD_RANGE ? 1 : gap < HOLD_RANGE - 1.0 ? -1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, false, false), false);
    }

    // ===== the launch =====

    /**
     * Starts the entry the model chose. The wind charge has to be thrown straight down onto the bot's own
     * feet, so this is where the view goes down; the pearl is thrown at the target instead. Returns whether
     * the entry is under way, in which case this tick is spent on it.
     */
    private boolean beginAim(BotBody body, LivingEntity target, Perception.Snapshot me, Perception.Snapshot seen)
    {
        switch (entry)
        {
            case EngagePlanner.PEARL -> {
                int pearl = gear.pearlSlot();
                want(body, pearl);
                if (pearl >= 0 && pearl == body.currentSlot() && gear.pearlReady())
                {
                    actions.useMainHand();
                    launching = false;
                    return true;
                }
                if (++aimTicks > AIM_TIMEOUT)
                {
                    launching = false;
                    return false;
                }
                body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false);
                return true;
            }
            case EngagePlanner.WIND_CHARGE -> {
                if (!me.onGround || !gear.chargeReady())
                {
                    launching = false;
                    return false;
                }
                want(body, gear.chargeSlot());
                phase = Phase.AIM;
                aimTicks = 0;
                aim(body, target, me, seen);
                return true;
            }
            default -> {
                launching = false;
                return false;
            }
        }
    }

    /** Starts a flight: the tick the model gave is the one the smash has to land on. */
    private void startFlight()
    {
        smashTick = entryTicks;
        flightTicks = 0;
        hitsAtLaunch = stats.hits;
        flightCharges = 0;
        airborne = false;
        bounceLeft = 0;
        chained = false;
        fallStunAxe = -1;
        phase = Phase.FLIGHT;
    }

    /**
     * One tick of aiming. The snapshot handed to the body is a point under the bot's own feet, so the
     * body's own aim path turns the view down at the controller's human speed, and the charge leaves the
     * hand once the view is down and the hotbar has caught up.
     */
    private void aim(BotBody body, LivingEntity target, Perception.Snapshot me, Perception.Snapshot seen)
    {
        int charge = gear.chargeSlot();
        atFeet(me);
        body.tick(feet, target, DuelSim.action(0, 0, false, false, false), false);
        aimTicks++;
        if (aimTicks > 1 && bot.getXRot() >= AIM_PITCH && charge == body.currentSlot())
        {
            if (actions.useMainHand())
            {
                startFlight();
                return;
            }
        }
        if (aimTicks > AIM_TIMEOUT)
        {
            phase = Phase.MELEE;
            launching = false;
        }
    }

    /**
     * The flight. The bot steers towards where the target will be, swings on the tick the model gave as
     * long as the crosshair is on the target, folds its wings before a dive lands, and throws the charge
     * that makes the landing harmless as soon as it is clear that nothing is going to be hit.
     *
     * <p>The hand holds the item the cooldown is collected under, which is the sword rather than the mace
     * wherever a measurement says the swap pays, and the mace goes in only for the tick of the swing. A
     * raised shield stops a smash outright, so when one is coming down at the bot the axe takes it out of
     * the way first and the mace follows into the same fall.</p>
     */
    private void flight(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        flightTicks++;
        // The charge takes a tick or two to burst, so the bot is still on its feet for the first ticks of
        // its own flight; the flight is over when it has been up and come back down.
        airborne |= !me.onGround;
        if (stats.hits > hitsAtLaunch)
        {
            hitsAtLaunch = stats.hits;
            // A smash launches the attacker back up when the mace carries Wind Burst, and the bot may follow
            // that with one more hit before it comes down; any further one has to wait for its cooldown.
            bounceLeft = 1;
            chained = true;
            fallStunAxe = -1;
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        fillMaceLevels(sim.a);
        int mace = heldMace(cfg, me, seen);
        boolean gliding = bot.isFallFlying();
        boolean smash = !gliding && me.fallDistance > CombatMath.SMASH_FALL_THRESHOLD;
        boolean close = DuelSim.inReach(sim.a, sim.b);
        boolean bounce = allowed(cfg, Technique.BOUNCE) && bounceLeft > 0 && gear.burstLevel() > 0;

        if (fallStun(cfg, body, target, me, seen, smash, close))
        {
            return;
        }
        fillCharger(sim.a);
        boolean charged = sinceClick >= sim.a.gateTicks;
        boolean due = smash && close && (charged || bounce) && body.canHit(target);
        // The swap is the hotbar change on the tick of the hit, so the mace is asked for as soon as the swing
        // is due and the charger is put straight back on the tick after it.
        want(body, due ? mace : charging(cfg, me, seen));
        if (bounce && due)
        {
            bounceLeft--;
        }
        // The launch has missed once the bot is on the way down with enough fall for a smash and the target
        // is out of its reach, which is a question about where the bot is and not about which tick the model
        // expected it to arrive: the arc it actually flew is not the arc the model planned.
        boolean missed = !chained && flightTicks > 2 && !close && me.vy < 0.0 && smash;
        // The charge that keeps the fall harmless goes into the hand while there is still room for the hotbar
        // change to land, and leaves it in the last blocks, where the burst can still reach the bot.
        double above = heightAboveGround(me);
        boolean winding = winding(cfg, me, seen, due, close, missed, above);
        System.out.println("[macedbg] t=" + flightTicks + " above=" + String.format(java.util.Locale.ROOT, "%.2f", above)
                + " fall=" + me.fallDistance + " vy=" + me.vy + " g=" + me.onGround + " close=" + close
                + " wind=" + winding + " missed=" + missed + " fc=" + flightCharges + " slot=" + body.currentSlot()
                + " cslot=" + gear.chargeSlot() + " ready=" + gear.chargeReady());
        boolean prepare = winding && above <= SAVE_HEIGHT + HOTBAR_TICK;
        boolean throwIt = winding && above <= SAVE_HEIGHT;
        if (prepare)
        {
            atFeet(me);
            want(body, gear.chargeSlot());
        }
        body.tick(prepare ? feet : leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, due), false);
        if (throwIt && gear.chargeSlot() == body.currentSlot() && gear.chargeReady() && actions.useMainHand())
        {
            flightCharges++;
        }
        if (gliding && close && me.vy < 0.0 && horizontal(me, seen) <= DIVE_CLOSE)
        {
            // The smash does not count while the wings are open, so they go away before the hit.
            actions.glide(false);
            actions.fold();
        }
        System.out.println("[macedbg] FEND t=" + flightTicks + " airborne=" + airborne + " onGround=" + me.onGround
                + " chained=" + chained + " hits=" + stats.hits + " hp=" + bot.getHealth());
        if ((airborne && me.onGround) || flightTicks > FLIGHT_TIMEOUT)
        {
            phase = Phase.MELEE;
            launching = false;
            aimTicks = 0;
            fallStunAxe = -1;
        }
    }

    /**
     * The stun slam inside one fall: the axe opens the shield window on the way down and the mace follows a
     * tick later out of the same fall, where its fall bonus survives the cooldown the axe hit just spent.
     *
     * @return whether this tick was spent on the pair, in which case no other swing is due
     */
    private boolean fallStun(BotPvpConfig cfg, BotBody body, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen, boolean smash, boolean close)
    {
        if (!allowed(cfg, Technique.FALL_STUN_SLAM) || gear.axeSlot() < 0 || !smash)
        {
            return false;
        }
        if (fallStunAxe > 0)
        {
            // The axe landed a moment ago, so the mace goes in now. The swing is charged at the axe's rate and
            // carries the axe's base damage, because that is what the hand still holds.
            fillCharger(sim.a);
            boolean due = close && body.canHit(target);
            want(body, heldMace(cfg, me, seen));
            body.tick(leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, due), false);
            fallStunAxe = 0;
            return true;
        }
        if (fallStunAxe == 0 || !allowed(cfg, Technique.READ) || !seen.blocking || !close
                || body.currentSlot() != gear.axeSlot())
        {
            return false;
        }
        fillCharger(sim.a);
        if (sinceClick < sim.a.gateTicks || !body.canHit(target))
        {
            return false;
        }
        fallStunAxe = STUN_GAP;
        want(body, heldMace(cfg, me, seen));
        body.tick(leading(me, seen), target, DuelSim.action(0, 0, false, false, true), false);
        return true;
    }

    /**
     * The elytra dive. The wings go on by using the elytra out of the hotbar, which exchanges it with the
     * worn chest plate, and come off the same way, by using the chest plate they were exchanged with. Between
     * those two the bot climbs on rockets until it is above the target, then closes the wings and smashes off
     * the sword's charge out of the drop.
     */
    private void climb(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        climbTicks++;
        if (bot.isFallFlying())
        {
            if (folded > 0)
            {
                dive(body, cfg, target, me, seen);
                return;
            }
            if (seen.y - me.y <= 0.0 && horizontal(me, seen) <= DIVE_CLOSE_DROP)
            {
                // Above the target and on top of it. The wings have to go, because a smash does not count
                // while they are open; using the chest plate that is in the hand puts them back where they
                // were and the fold state makes the fall start counting on this very tick.
                if (gear.wearingChestpiece() && actions.useMainHand())
                {
                    actions.fold();
                }
                else
                {
                    actions.fold();
                }
                folded = FOLD_SETTLE;
                return;
            }
            rocket(body);
            body.tick(leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, false), false);
            return;
        }
        if (bot.onGround())
        {
            phase = Phase.MELEE;
            launching = false;
            return;
        }
        // Still climbing off the launch. The wings go on the first tick they are reachable.
        if (gear.elytraSlot() >= 0 && !gear.wearingElytra())
        {
            want(body, gear.elytraSlot());
            if (gear.elytraSlot() == body.currentSlot() && actions.useMainHand())
            {
                actions.deploy();
            }
            return;
        }
        actions.deploy();
        body.tick(seen, target, DuelSim.action(steer(me, seen), 0, false, false, false), false);
    }

    /** One rocket, as often as a launched firework allows. */
    private void rocket(BotBody body)
    {
        if (climbTicks - rocketTick < ROCKET_GAP || gear.rocketSlot() < 0 || !gear.rocketReady())
        {
            return;
        }
        want(body, gear.rocketSlot());
        if (gear.rocketSlot() == body.currentSlot() && actions.useMainHand())
        {
            rocketTick = climbTicks;
        }
    }

    /** The drop at the end of a dive: the same smash a wind charge flight makes, off the sword's charge. */
    private void dive(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        folded--;
        fill(sim.a, me);
        fill(sim.b, seen);
        fillCharger(sim.a);
        fillMaceLevels(sim.a);
        int mace = heldMace(cfg, me, seen);
        boolean smash = me.fallDistance > CombatMath.SMASH_FALL_THRESHOLD;
        boolean close = DuelSim.inReach(sim.a, sim.b);
        boolean due = smash && close && sinceClick >= sim.a.gateTicks && body.canHit(target);
        want(body, due ? mace : charging(cfg, me, seen));
        body.tick(leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, due), false);
        if (bot.onGround() || climbTicks > CLIMB_TIMEOUT)
        {
            phase = Phase.MELEE;
            launching = false;
            folded = 0;
        }
    }


    /**
     * How far forward to hold while it is in the air: towards where the target will be when the bot comes
     * down, and no further, since air control is weak and a fighter that keeps pushing flies past the
     * target it was aiming at.
     */
    private int steer(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double ticks = Math.min(MaceLaunch.drop(heightAboveGround(me), me.vy, 0.0)[0], STEER_TICKS);
        double dx = seen.x + seen.vx * ticks - me.x;
        double dz = seen.z + seen.vz * ticks - me.z;
        if (Math.sqrt(dx * dx + dz * dz) < STEER_STOP)
        {
            return 0;
        }
        double yaw = Math.toRadians(bot.getYRot());
        return dx * -Math.sin(yaw) + dz * Math.cos(yaw) > 0.0 ? 1 : -1;
    }

    /**
     * Whether the bot wants a wind charge at its own feet on the way down. A charge's own ten tick
     * cooldown has the bot four blocks up by the time it may throw another, which is past the burst's
     * reach, so the second charge of a launch can only be a hop near the ground: either it keeps the fall
     * harmless once the smash is clearly not going to land, or, while the target is still in reach of a
     * second arc, the model prices the extra fall and the bot takes it.
     */
    private boolean winding(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen, boolean due,
            boolean close, boolean missed, double above)
    {
        if (flightCharges >= FLIGHT_CHARGES || !missed || me.onGround || me.vy >= 0.0
                || gear.chargeSlot() < 0 || above > SAVE_HEIGHT + HOTBAR_TICK)
        {
            return false;
        }
        if (allowed(cfg, Technique.SAFE_LANDING))
        {
            return true;
        }
        return allowed(cfg, Technique.CHAIN) && horizontal(me, seen) <= CHAIN_RANGE
                && MaceChoice.chainWorthIt(MaceLaunch.APEX[0] - above, gear.densityLevel(), seen.armor,
                        seen.armorToughness, 0.0F);
    }

    /**
     * A snapshot of where the target will be by the time the bot comes down, which is where a fighter
     * falling onto it puts its crosshair: the snapshot it can see is a couple of ticks old, and the
     * target has moved since.
     */
    private Perception.Snapshot leading(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double ticks = Math.min(MaceLaunch.drop(heightAboveGround(me), me.vy, 0.0)[0], LEAD_TICKS);
        feet.copyFrom(seen);
        feet.x += seen.vx * ticks;
        feet.z += seen.vz * ticks;
        return feet;
    }

    // ===== helpers =====

    /**
     * Asks the body for a hotbar slot. A change takes effect on the next tick, so a style has to ask a
     * tick before it needs the item, the same way a player's scroll wheel does.
     */
    private static void want(BotBody body, int slot)
    {
        if (slot >= 0 && slot != body.currentSlot() && slot != body.pendingSlot())
        {
            body.requestSlot(slot);
        }
    }

    /**
     * A snapshot of a point just under the bot's own feet, so that the body's own aim path turns the view
     * down onto them: the yaw stays where it is and the pitch goes to straight down.
     */
    private void atFeet(Perception.Snapshot me)
    {
        feet.copyFrom(me);
        double yaw = Math.toRadians(bot.getYRot());
        feet.x = me.x - Math.sin(yaw) * 0.05;
        feet.z = me.z + Math.cos(yaw) * 0.05;
        feet.y = me.y - 3.0;
    }

    /** The mace this bot should be swinging: the Density one unless the target's armour wants Breach. */
    private int heldMace(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        return gear.maceSlot(picks(cfg) ? entryFall : me.fallDistance, seen.armor, seen.armorToughness, 0.0F,
                picks(cfg));
    }

    /**
     * The slot the hand is given between hits: the item the cooldown is collected under, which is the sword
     * or the axe wherever the swap pays and the mace itself otherwise, since a mace held through can always
     * make its own smash.
     */
    /**
     * The slot the hand is given between hits. A mace held through can always make its own smash, and the
     * bot only leaves the mace out of the hand for the launch wind-up, where the model wants the mace's own
     * thirty four tick charge and not a faster item's.
     */
    private int charging(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        return heldMace(cfg, me, seen);
    }

    /** Whether the bot may charge the launch under a faster item and swap to the mace on the hit. */
    private boolean swapping(BotPvpConfig cfg)
    {
        return false;
    }

    /** A technique the bot's difficulty knows and whose option has not been switched off. */
    private static boolean allowed(BotPvpConfig cfg, Technique technique)
    {
        return MaceChoice.allows(cfg.difficulty, technique) && cfg.flag(optionOf(technique));
    }

    private static boolean picks(BotPvpConfig cfg)
    {
        return allowed(cfg, Technique.ENCHANT_PICK);
    }

    private static String optionOf(Technique technique)
    {
        return switch (technique)
        {
            case WIND_CHARGE -> "mace.windcharge";
            case CHAIN -> "mace.chain";
            case PEARL -> "mace.pearl";
            case ELYTRA -> "mace.elytra";
            case ROCKET -> "mace.rocket";
            case STUN_SLAM -> "mace.stunslam";
            case FALL_STUN_SLAM -> "mace.fallstunslam";
            case ENCHANT_PICK -> "mace.enchants";
            case BOUNCE -> "mace.bounce";
            case SAFE_LANDING -> "mace.safeland";
            case SWAP -> "mace.swap";
            case BREACH_SWAP -> "mace.breachswap";
            case READ -> "mace.read";
        };
    }

    /** Writes what the bot knows of a fighter into the model. */
    private void fill(DuelSim.Fighter fighter, Perception.Snapshot snapshot)
    {
        double yaw = Math.toRadians(snapshot.yaw);
        fighter.x = snapshot.x;
        // The model walks on a flat floor at y = 0, so both fighters go in relative to the ground the bot
        // is standing over, which leaves the heights between them exactly as they are.
        fighter.y = snapshot.y - ground0;
        fighter.z = snapshot.z;
        fighter.vx = snapshot.vx;
        fighter.vy = snapshot.vy;
        fighter.vz = snapshot.vz;
        fighter.sinYaw = -Math.sin(yaw);
        fighter.cosYaw = Math.cos(yaw);
        fighter.onGround = snapshot.onGround;
        fighter.sprinting = false;
        fighter.health = snapshot.health;
        fighter.ticksSinceSwing = snapshot.ticksSinceSwing;
        fighter.invulTime = snapshot.invulnerableTime;
        fighter.armor = snapshot.armor;
        fighter.toughness = snapshot.armorToughness;
        fighter.knockbackResistance = snapshot.knockbackResistance;
        fighter.fallDistance = snapshot.fallDistance;
        // How much the last hit was worth is not observable from outside, so the model always takes the
        // next one as a fresh one.
        fighter.lastHurt = 0.0F;
    }

    /**
     * The mace the bot would swing, priced at the base damage and the charge of the item the hand holds
     * between hits. When the swap is on that is the sword, whose eight base damage and thirteen tick cadence
     * are both better than the mace's six and thirty four, and the mace's own fall bonus and Density are added
     * on top of them by {@link SmashTiming#damageDealt}.
     */
    private void fillMace(DuelSim.Fighter fighter, BotPvpConfig cfg)
    {
        fillMaceLevels(fighter);
        fighter.setLoadout(SmashTiming.MACE_BASE_DAMAGE, SmashTiming.MACE_ATTACK_SPEED, 0.0F, fighter.armor,
                fighter.toughness, fighter.epf, fighter.knockbackResistance);
        fighter.ticksSinceSwing = sinceClick;
    }

    /** The mace's own enchantments, which is all the hand ever contributes to the swing that lands it. */
    private void fillMaceLevels(DuelSim.Fighter fighter)
    {
        fighter.densityLevel = gear.densityLevel();
        fighter.windBurstLevel = gear.burstLevel();
    }

    /** The loadout of the item a swing's base damage and cooldown come from when the hand changes on the hit. */
    private void fillCharger(DuelSim.Fighter fighter)
    {
        double damage = gear.chargerIsAxe() ? AttributeSwap.AXE_BASE_DAMAGE : AttributeSwap.SWORD_BASE_DAMAGE;
        double speed = gear.chargerIsAxe() ? AttributeSwap.AXE_ATTACK_SPEED : AttributeSwap.SWORD_ATTACK_SPEED;
        fighter.setLoadout(damage, speed, 0.0F, fighter.armor, fighter.toughness, fighter.epf,
                fighter.knockbackResistance);
        // The cooldown the model charges at is the bot's own, which only its clicks reset.
        fighter.ticksSinceSwing = sinceClick;
    }

    private void fillAxe(DuelSim.Fighter fighter)
    {
        fighter.setLoadout(SmashTiming.AXE_BASE_DAMAGE, 1.0, 0.0F, fighter.armor, fighter.toughness, fighter.epf,
                fighter.knockbackResistance);
        fighter.ticksSinceSwing = sinceClick;
    }

    /** What the target can do to the bot while it is in the air, from what perception knows of it. */
    private void fillThreat(Perception.Snapshot seen)
    {
        threat.baseDamage = (float) seen.weaponDamage;
        threat.attackSpeed = seen.attackSpeed;
        threat.swingPeriod = Math.round(CombatMath.fullChargeTicks(seen.attackSpeed));
        threat.ticksSinceSwing = seen.ticksSinceSwing;
    }

    /** How far above the surface the bot would land on it is. */
    private double heightAboveGround(Perception.Snapshot me)
    {
        return me.y - ground0;
    }

    /**
     * The height of the surface under the bot, found by looking down the way a player who wants to land
     * would look, up to {@link #GROUND_SCAN} blocks. What the duel model calls its ground is y = 0, so
     * everything it is told about a fighter is relative to this.
     */
    private double groundOf(Perception.Snapshot me)
    {
        BlockPos feetPos = BlockPos.containing(me.x, me.y - 0.05, me.z);
        for (int down = 0; down <= GROUND_SCAN; down++)
        {
            BlockPos pos = feetPos.below(down);
            BlockState state = bot.level().getBlockState(pos);
            if (!state.isAir() && state.isFaceSturdy(bot.level(), pos, Direction.UP))
            {
                return pos.getY();
            }
        }
        return me.y - GROUND_SCAN - 1.0;
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
