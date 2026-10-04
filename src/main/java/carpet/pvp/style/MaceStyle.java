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
    /**
     * Wind charges one flight may throw at its own feet below the apex. The save is worth two: the burst off a
     * fall is far stronger than the launch that started the flight, so the bot is often higher after one than
     * before, and the impulse behind the second one is what makes that landing harmless. A chain is only taken
     * when nothing has been spent, so a charge is never asked for both.
     */
    private static final int FLIGHT_CHARGES = 2;
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
    /**
     * Height above the target a dive turns in at, and the height the model is asked to price it from, since the
     * planner cannot open a glide from the ground: a dive is the one entry a player starts off the floor, which
     * is why it is only the bot's when the climb gets it higher than a wind charge launch does.
     */
    private static final double DIVE_HEIGHT = 7.0;
    /**
     * How far off a dive may be started. Players take off next to the opponent, so there is no lower bound:
     * what decides the range is that the climb has to end over the target, which it only does from the range a
     * wind charge launch is thrown from anyway. Past that the climb is a way of losing the fight on the way up.
     */
    private static final double DIVE_FAR = 7.0;
    /** Rockets a dive needs before it can be called worth starting. */
    private static final int DIVE_ROCKETS = 2;
    /**
     * Ticks a dive is charged for before it is compared with anything else. The planner opens a glide off the
     * height the climb reaches, so what it reports is what the drop is worth and nothing about getting up
     * there; the climb is ticks spent in the target's reach with an empty hand, and a wind charge launch is
     * worth none of them, so this is what keeps a dive from looking free.
     */
    private static final int DIVE_CLIMB_TICKS = 24;
    /** View pitch a glide climbs under, in degrees looking up, which is Minecraft's negative. */
    private static final float CLIMB_PITCH = -50.0F;
    /**
     * How far the target may get away during a climb before the bot gives it up. A glide climbs faster than a
     * sword bot walks, but not faster than one that is running, and a dive against a target that is leaving is
     * a hundred ticks of being hit by it with nothing in the bot's hand.
     */
    private static final double DIVE_ABANDON = 12.0;
    /** Ticks one dive may spend putting the wings on, lighting rockets and getting above the target. */
    private static final int CLIMB_TIMEOUT = 120;
    /** Ticks between two rockets, which is how long a launched firework takes to go off. */
    private static final int ROCKET_GAP = 3;
    /** Ticks the wings stay folded before the bot is sure it is really falling onto the target. */
    private static final int FOLD_SETTLE = 2;
    /**
     * Fall distance below which the bot has stopped falling rather than arrived at the target. A smash leaves
     * the attacker hanging where the hit was made, so a chain that follows one up a block or two is still a
     * chain, and the bot should not read the hang as a launch that has missed.
     */
    private static final double HANGING_FALL = 0.3;
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
    private double ground0 = Double.NaN;

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
        if (stunWindow)
        {
            // The shield is down for a hundred ticks and a launch would spend them getting up, so the bot walks
            // in and spends the window on the mace it is already holding.
            maceRush(body, cfg, target, me, seen);
            return;
        }
        boolean winding = winding(cfg, me, seen);
        if (!launching)
        {
            // The entry that was chosen is the entry that gets flown: re-planning while one is under way would
            // overwrite it, and a dive in particular is chosen over a launch and would lose to the next tick's.
            // A stun window is not spent on a launch either: it is a hundred ticks of the target's shield being
            // down and nothing else in this kit opens one.
            if (winding)
            {
                // Standing off for a launch: only the entries that need height are on the table, and a standoff
                // with nothing to launch and a charged hand is over, because otherwise the bot would hold that
                // range for ever.
                // Inside the range a launch cannot arc over the bot is not standing off at all: it walks in
                // and trades, so walking in is one of the answers the model is asked for there.
                boolean standoff = horizontal(me, seen) >= FORCED_GAP;
                if (!plan(cfg, me, seen, standoff) && sinceClick >= launchGate(cfg))
                {
                    winding = false;
                }
            }
            else if (planCountdown-- <= 0)
            {
                planCountdown = PLAN_INTERVAL;
                plan(cfg, me, seen, false);
            }
        }
        if (launching)
        {
            if (!beginAim(body, target, me, seen))
            {
                hold(cfg, body, target, me, seen);
            }
            return;
        }
        if (winding && (sinceClick < launchGate(cfg) || horizontal(me, seen) >= FORCED_GAP))
        {
            // The launch wants a charged hand and every swing would spend the charge again, so the bot
            // stands at the range the model launches from and lets its cooldown come up. It only gives up
            // the standoff when the target is inside its own reach, where a launch could not reach at all.
            hold(cfg, body, target, me, seen);
            return;
        }
        if (breachGround(cfg, me, seen))
        {
            // The launch rhythm comes first, so a Breach swap on the ground can never stand between this bot
            // and the launch the model just asked for.
            breachExchange(body, cfg, target, me, seen);
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
     * the cooldown under, armour for Breach to cut, and the target inside the range a click reaches. The
     * swap costs nothing but the hotbar change the game gives one tick of room for, so it is not held back for
     * a launch to come first, and a bot with wind charges left is trading exactly like one without.
     */
    private boolean breachGround(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.BREACH_SWAP) || stunWindow || launching || !me.onGround || dread > 0)
        {
            return false;
        }
        double near = horizontal(me, seen);
        return near <= cfg.meleeRange && ground.worthIt(seen.armor, seen.armorToughness, seen.epf);
    }

    /**
     * The ground exchange: the hand holds the item the cooldown is collected under, whose thirteen tick cadence
     * is what the fight runs on, and the Breach mace goes in for the tick of each charged swing so the hit is
     * made through armour it has cut while carrying the charger's base damage. The hotbar change lands on the
     * next body tick, which is the tick before the swing, and the charger goes back in on the one after it.
     */
    private void breachExchange(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int breaker = ground.hitSlot(seen.armor, seen.armorToughness, seen.epf);
        fill(sim.a, me);
        fillCharger(sim.a);
        // A hotbar change lands on the next tick, so the Breach mace has to be asked for on the tick before the
        // swing: the swing is made with the mace in the hand and the charger's charge behind it.
        boolean inReach = breaker >= 0 && body.canHit(target);
        boolean swing = inReach && body.currentSlot() == breaker && sinceClick >= sim.a.gateTicks;
        want(body, inReach ? breaker : ground.chargeSlot());
        // The exchange closes to reach and then stands its ground: backing off would take the bot out of the
        // window the exchange is for and hand the fight back to the launch it has no charge left for.
        double near = horizontal(me, seen);
        int forward = near > cfg.meleeRange * 0.8 ? 1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, forward > 0, swing), false);
    }

    /**
     * Whether this bot could dive at this target at all: the wings, enough rockets to climb with, and a target
     * it can get above rather than one that is already above it.
     *
     * <p>No wind charges left is part of it, and that is what a dive really is for. A launch costs no exposure
     * at all and lands something every time, so a bot that still has charges spends them; once they are gone the
     * mace does nothing on the ground and the only way left to put the target on the floor is to come down on it
     * from above. Whether to spend the climb on this particular target is then the model's question, and
     * {@link #plan} asks it from the height the climb reaches.</p>
     */
    private boolean canDive(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.ELYTRA) || !allowed(cfg, Technique.ROCKET) || stunWindow || launching
                || !me.onGround || dread > 0 || gear.rocketSlot() < 0 || gear.rockets() < DIVE_ROCKETS
                || gear.charges() > 0)
        {
            return false;
        }
        if (gear.elytraSlot() < 0 && !gear.wearingElytra())
        {
            return false;
        }
        return horizontal(me, seen) <= DIVE_FAR && seen.y < me.y + DREAD_HEIGHT;
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
     *
     * <p>While the bot is standing off, walking in is not one of the answers: a ground swing there would spend
     * the very charge the launch is waiting for, and a pearl is not one either, since the standoff only exists
     * inside the range a wind charge reaches from. What is left is the two entries that need height. A model
     * that is asked for a free swing it is not going to throw answers "walk in and hit" forever, which is how a
     * bot ends up holding a charged hand at three and a half blocks for ever; asking it instead for the entries
     * the standoff exists for is what puts the bot back in the air.</p>
     *
     * @param standingOff whether the bot is holding its charge for a launch rather than willing to swing
     * @return whether an entry was found worth flying
     */
    private boolean plan(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen, boolean standingOff)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < PLAN_TICKS)
        {
            stats.starvedTicks++;
            return false;
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        fillMace(sim.a, cfg);
        fillThreat(seen);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, gear.breachLevel(), threat, PLAN_TICKS);
        budget.spend(PLAN_TICKS);
        stats.plannerCalls++;
        stats.simulatedTicks += PLAN_TICKS;
        EngagePlanner.Option best = null;
        if (standingOff && canDive(cfg, me, seen))
        {
            best = dive(sim, best);
        }
        for (int approach = 0; approach < EngagePlanner.APPROACHES; approach++)
        {
            if (standingOff && approach != EngagePlanner.WIND_CHARGE && approach != EngagePlanner.ELYTRA)
            {
                continue;
            }
            EngagePlanner.Option option = options[approach];
            if (!option.feasible || !wanted(cfg, approach) || (best != null && option.score() <= best.score()))
            {
                continue;
            }
            best = option;
        }
        if (best == null || best.approach == EngagePlanner.WALK_IN || !wanted(cfg, best.approach))
        {
            return false;
        }
        entry = best.approach;
        entryTicks = best.ticks;
        entryFall = best.fallDistance;
        launching = true;
        return true;
    }

    /**
     * Prices the dive against what the other entries are worth right now. The planner cannot open a glide off
     * the ground, so the dive is asked from the height a climb reaches and what comes back is compared with the
     * best of the entries that can be started where the bot is standing: that is the question the task is, of
     * whether a fall from higher up is worth the ticks the climb costs.
     *
     * @param best the best entry so far, or null when there is not one
     * @return the best entry including the dive
     */
    private EngagePlanner.Option dive(DuelSim sim, EngagePlanner.Option best)
    {
        BotBudget budget = BotBudget.instance();
        if (budget.join() < PLAN_TICKS)
        {
            stats.starvedTicks++;
            return best;
        }
        double standing = sim.a.y;
        sim.a.y = sim.b.y + DIVE_HEIGHT;
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, gear.breachLevel(), threat, PLAN_TICKS);
        sim.a.y = standing;
        budget.spend(PLAN_TICKS);
        stats.plannerCalls++;
        stats.simulatedTicks += PLAN_TICKS;
        EngagePlanner.Option dive = options[EngagePlanner.ELYTRA];
        if (!dive.feasible)
        {
            return best;
        }
        double score = dive.score() * dive.ticks / (dive.ticks + DIVE_CLIMB_TICKS);
        return best == null || score > best.score() ? dive : best;
    }

    /** Whether this bot's difficulty knows the given entry and it has what it takes to fly it. */
    private boolean wanted(BotPvpConfig cfg, int approach)
    {
        return switch (approach)
        {
            case EngagePlanner.WIND_CHARGE -> allowed(cfg, Technique.WIND_CHARGE) && gear.chargeSlot() >= 0;
            case EngagePlanner.PEARL -> allowed(cfg, Technique.PEARL) && gear.pearlSlot() >= 0;
            case EngagePlanner.ELYTRA -> allowed(cfg, Technique.ELYTRA)
                    && (gear.wearingElytra() || gear.elytraSlot() >= 0);
            default -> false;
        };
    }

/**
     * Standing off for a launch: the gap is held at the range the model launches from and nothing is swung, so
     * the charge the smash will be made off is still there when the arc comes down. The hand holds that item,
     * which is the charger's when the bot swaps the mace in on the hit and the mace itself when it does not.
     */
    private void hold(BotPvpConfig cfg, BotBody body, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        want(body, charging(cfg, me, seen));
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
            case EngagePlanner.ELYTRA -> {
                if (!bot.onGround())
                {
                    phase = Phase.CLIMB;
                    return true;
                }
                if (!gear.wearingElytra())
                {
                    // The wings go on from the hotbar: using the elytra exchanges it with the worn chest
                    // piece, which is the only way a bot that is not already wearing them ever opens a glide.
                    int elytra = gear.elytraSlot();
                    if (elytra < 0)
                    {
                        launching = false;
                        return false;
                    }
                    want(body, elytra);
                    if (elytra == body.currentSlot())
                    {
                        actions.useMainHand();
                    }
                }
                // A glide only starts off the ground, so the bot jumps with the wings on.
                body.tick(seen, target, DuelSim.action(0, 0, true, false, false), false);
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
            // A smash leaves the attacker hanging where the hit was made, and one carrying Wind Burst throws
            // the target up, so the bot may follow it with a second hit out of the same fall; any further one
            // has to wait for its cooldown.
            bounceLeft = 1;
            chained = true;
            // The axe of a stun slam is one hit in a fall like any other, so this would close the window it
            // has just opened. Only the hit that is not the axe ends the pair.
            if (fallStunAxe <= 0)
            {
                fallStunAxe = -1;
            }
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        fillMaceLevels(sim.a);
        int mace = heldMace(cfg, me, seen);
        boolean gliding = bot.isFallFlying();
        boolean smash = !gliding && me.fallDistance > CombatMath.SMASH_FALL_THRESHOLD;
        boolean close = DuelSim.inReach(sim.a, sim.b);
        boolean bounce = allowed(cfg, Technique.BOUNCE) && bounceLeft > 0 && gear.burstLevel() > 0;

        // The axe of a stun slam has to be in the hand before the shield window opens, since it is the axe hit
        // that takes the shield down and the mace follows it out of the same fall.
        boolean window = fallStunAxe < 0 && allowed(cfg, Technique.FALL_STUN_SLAM) && gear.axeSlot() >= 0
                && smash && allowed(cfg, Technique.READ) && seen.blocking;
        if (fallStun(cfg, body, target, me, seen, smash, close))
        {
            return;
        }
        fillCharger(sim.a);
        boolean charged = sinceClick >= sim.a.gateTicks;
        boolean due = smash && close && (charged || bounce) && body.canHit(target);
        // The swap is the hotbar change on the tick of the hit, so the mace is asked for as soon as the swing
        // is due and the charger is put straight back on the tick after it.
        want(body, due ? mace : window ? gear.axeSlot() : charging(cfg, me, seen));
        if (bounce && due)
        {
            bounceLeft--;
        }
        // The launch has missed once the bot is on the way down with enough fall for a smash and the target
        // is out of its reach, which is a question about where the bot is and not about which tick the model
        // expected it to arrive: the arc it actually flew is not the arc the model planned.
        boolean missed = !chained && flightTicks > 2 && !close && me.vy < 0.0 && smash
                && me.fallDistance > HANGING_FALL;
        // The charge that keeps the fall harmless goes into the hand while there is still room for the hotbar
        // change to land, and leaves it in the last blocks, where the burst can still reach the bot.
        double above = heightAboveGround(me);
        // An open shield window is worth more than the charge that saves the fall, and off its own launch the
        // fall is harmless anyway, so while the axe is wanted the hand is the axe's for as long as it takes.
        boolean winding = !window && winding(cfg, me, seen, due, close, missed, above);
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
            if (fallStunAxe > SmashTiming.AXE_DISABLE_TICKS)
            {
                // The shield is back up and the window the axe opened is gone.
                fallStunAxe = 0;
                return false;
            }
            // The axe landed a moment ago, so the mace goes in now. It is asked for on every tick until it
            // lands, because the axe has just been swung and the bot may still be a block or two above its
            // reach; the swing is charged at the axe's rate and carries the axe's base damage, because that is
            // what the hand still holds.
            fillAxe(sim.a);
            boolean due = close && body.canHit(target);
            want(body, heldMace(cfg, me, seen));
            body.tick(leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, due), false);
            fallStunAxe = due ? 0 : fallStunAxe + 1;
            return true;
        }
        if (fallStunAxe == 0 || !allowed(cfg, Technique.READ) || !seen.blocking || !close
                || body.currentSlot() != gear.axeSlot())
        {
            return false;
        }
        fillAxe(sim.a);
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
        if (folded > 0)
        {
            // The wings are folded for the drop, and nothing opens them again until this dive has landed: a
            // smash does not count while they are out, which is the whole reason they came off.
            dive(body, cfg, target, me, seen);
            return;
        }
        if (bot.isFallFlying())
        {
            if (overhead(me, seen))
            {
                // Above the target by enough to be worth dropping onto it. The wings have to go, because a
                // smash does not count while they are open, and they only go by using the chest piece they were
                // exchanged with: that goes back on the chest and the elytra lands in the hand.
                int chest = gear.chestpieceSlot();
                if (chest >= 0 && chest != body.currentSlot())
                {
                    want(body, chest);
                    body.tick(leading(me, seen), target, DuelSim.action(steer(me, seen), 0, false, false, false), false);
                    return;
                }
                if (chest < 0 || actions.useMainHand())
                {
                    actions.fold();
                }
                folded = FOLD_SETTLE;
                return;
            }
            if (horizontal(me, seen) > DIVE_ABANDON)
            {
                // The target is leaving faster than the climb can catch it, so there is nothing to come down on.
                phase = Phase.MELEE;
                launching = false;
                actions.fold();
                return;
            }
            rocket(body);
            // A glide only climbs while the bot looks up, but its heading still has to be the target's or the
            // climb walks it away from the fight: the aim carries the yaw to the target and the pitch up.
            body.tick(seen, target, DuelSim.action(steer(me, seen), 0, false, false, false), false,
                    climbAim(me, seen));
            return;
        }
        if (bot.onGround())
        {
            // The wings come off on the landing tick: a glide that keeps them out leaves the bot sailing along
            // the floor with nothing it can do, which is what a dive that could not get above its target is.
            actions.fold();
            phase = Phase.MELEE;
            launching = false;
            return;
        }
        // Still getting off the ground. The wings go on the first tick they are reachable.
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

    /**
     * Whether the bot is high enough over the target to be worth dropping onto it, which is what a dive is
     * for: the wings are folded the moment there is more fall left than there was height.
     */
    private boolean overhead(Perception.Snapshot me, Perception.Snapshot seen)
    {
        // The height is what the dive is worth, and it has to be turned in from over the target rather than
        // from beside it: a drop from seven blocks up only reaches a few blocks away.
        return seen.y - me.y <= -DIVE_HEIGHT && horizontal(me, seen) <= DIVE_CLOSE_DROP;
    }

    /** The target's heading with the view tilted up, which is what a glide climbs under. */
    private BotBody.Aim climbAim(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double dx = seen.x - me.x;
        double dz = seen.z - me.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float radius = (float) Math.toDegrees(Math.atan2(0.45, Math.max(Math.hypot(dx, dz), 0.1)));
        return new BotBody.Aim(yaw, CLIMB_PITCH, radius);
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
            actions.fold();
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
        if (me.onGround || me.vy >= 0.0 || gear.chargeSlot() < 0 || above > SAVE_HEIGHT + HOTBAR_TICK)
        {
            return false;
        }
        if (missed && allowed(cfg, Technique.SAFE_LANDING))
        {
            // A save is worth spending on every descent that needs one. The charge it bursts off is far
            // stronger than the launch that started the flight, because it goes off into a fall, so the bot
            // is often higher after one than before: its own impulse only lasts forty ticks, and the landing
            // after that is not free.
            return true;
        }
        // A hit has already been made out of this flight and the target is still inside the chain range, so
        // what is on offer is a second arc onto it: the smash leaves the attacker hanging and the Wind Burst
        // throws the target up, and a charge at the feet is what puts the bot back on top of it.
        return flightCharges < FLIGHT_CHARGES && chained && allowed(cfg, Technique.CHAIN)
                && horizontal(me, seen) <= CHAIN_RANGE
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
     * make its own smash. A wind charge has to leave the hand for the launch wind-up, and that is the one
     * place the bot asks for it, so the charger's charge survives the launch and the smash is made off it.
     */
    private int charging(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        return swapping(cfg) ? gear.chargerSlot() : heldMace(cfg, me, seen);
    }

    /**
     * Whether the bot may charge a launch under a faster item and swap the mace in on the tick of the hit. It
     * needs the technique, a charger to collect the cooldown under, and the measurement of the swap on this
     * version saying the smash it buys is worth more than holding the mace through.
     */
    private boolean swapping(BotPvpConfig cfg)
    {
        return allowed(cfg, Technique.SWAP) && MaceSwap.allowed() && gear.chargerSlot() >= 0;
    }

    /**
     * The ticks the hand has to be left alone before a launch is worth starting. A launch is paid for with the
     * charge of the item the hit will be made off, which is the charger's own when the bot swaps and the mace's
     * own when it does not, so the standoff is as long as that charge takes and no longer.
     */
    private int launchGate(BotPvpConfig cfg)
    {
        if (!swapping(cfg))
        {
            return MACE_GATE_TICKS;
        }
        fillCharger(sim.a);
        return sim.a.gateTicks;
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
     * between hits. When the swap is on that is the sword, whose base damage and cadence are both better than
     * the mace's, and the mace's own fall bonus and Density are added on top of them by
     * {@link SmashTiming#damageDealt}.
     */
    private void fillMace(DuelSim.Fighter fighter, BotPvpConfig cfg)
    {
        fillMaceLevels(fighter);
        if (swapping(cfg))
        {
            // The swing that lands the smash is made off the charger's base damage and charge, so that is what
            // the model has to price the launch at.
            fillCharger(fighter);
            return;
        }
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
        fighter.setLoadout(gear.chargerDamage(), gear.chargerSpeed(), 0.0F, fighter.armor, fighter.toughness,
                fighter.epf, fighter.knockbackResistance);
        // The cooldown the model charges at is the bot's own, which only its clicks reset.
        fighter.ticksSinceSwing = sinceClick;
    }

    private void fillAxe(DuelSim.Fighter fighter)
    {
        fighter.setLoadout(gear.axeDamage(), gear.axeSpeed(), 0.0F, fighter.armor, fighter.toughness, fighter.epf,
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
     *
     * <p>A launch carries the bot further up than the scan looks, and a chain of them further still, so the
     * last surface found is held while the bot is still above it. Without that the height of the bot would
     * stop growing at {@link #GROUND_SCAN} blocks and read the same from there on, which is the height every
     * decision of a flight is made from: when to save the fall, when to steer and how long the descent is
     * going to take. The held height is dropped as soon as the bot is under it, which is what falling off
     * an edge does, and a bot that has never found one falls back to the scan.</p>
     */
    private double groundOf(Perception.Snapshot me)
    {
        if (ground0 > me.y)
        {
            ground0 = Double.NaN;
        }
        BlockPos feetPos = BlockPos.containing(me.x, me.y - 0.05, me.z);
        for (int down = 0; down <= GROUND_SCAN; down++)
        {
            BlockPos pos = feetPos.below(down);
            BlockState state = bot.level().getBlockState(pos);
            if (!state.isAir() && state.isFaceSturdy(bot.level(), pos, Direction.UP))
            {
                // The walkable surface is the top face of the block, one above the block itself, and the model
                // counts height from the surface, so that is the zero the duel model is given.
                ground0 = pos.getY() + 1.0;
                break;
            }
        }
        return Double.isNaN(ground0) ? me.y - GROUND_SCAN - 1.0 : ground0;
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
