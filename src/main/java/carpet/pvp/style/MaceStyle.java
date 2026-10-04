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
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Mace fighting: height first, the hit on the way down, and back up again before the feet have settled.
 *
 * <p>A mace does nothing on the ground, so the fight is a rhythm of arcs. {@link EngagePlanner} says, on the same
 * duel model the sword style plans with, whether an entry that needs height beats walking in against the target
 * the bot can see. The bot then turns the view down onto its own feet, throws the wind charge through the body's
 * hotbar and click rules, steers towards where the target will be and holds the swing for the last ticks of the
 * descent, where the fall it carries is longest. A smash that lands with Wind Burst throws the bot some twenty
 * blocks back up and the next one comes out of that; one that lands without it, and a descent that is not going
 * to land anything, is followed by the next charge thrown so that it bursts as the feet touch, which is what
 * keeps the bot out of a sword's reach between hits and what keeps a fall the game would charge for harmless.</p>
 *
 * <p>The hit itself is made off another item's cooldown. The game only re-reads an item's attributes and only
 * clears the swing timer once a tick, so a hotbar change on the tick of the swing lands the new item's fall
 * bonus, enchantments and Breach on top of the base damage and the charge of the item the hand was holding:
 * {@link carpet.pvp.sim.AttributeSwap} prices that, and the bot holds the sword between hits and puts the mace in
 * only for the tick of the swing. A raised shield stops a smash outright, so the axe goes in the same way a tick
 * before the mace and takes the shield out of the fall. On the ground the same trick is used the other way
 * round, with the Breach mace in the hand for the swing and the sword's base damage and its thirteen tick
 * cadence behind it. Every technique has an option of its own and is gated by the difficulty preset.</p>
 */
public final class MaceStyle implements BotStyle
{
    /** What the bot is doing: on the ground, throwing the launch, falling onto the target, or climbing for a dive. */
    private enum Phase { MELEE, AIM, FLIGHT, CLIMB }

    /** Ticks between two plans while the bot has nothing to launch with. */
    private static final int PLAN_INTERVAL = 5;
    /** Ticks a launch plan may simulate. */
    private static final int PLAN_TICKS = 40;
    /** Ticks between two reads of the bot's own hotbar. */
    private static final int GEAR_REFRESH = 20;
    /** Ticks the view may take to come round to straight down before the bot gives the launch up. */
    private static final int AIM_TIMEOUT = 16;
    /**
     * View pitch from which the wind charge counts as aimed at the bot's own feet, degrees. A charge thrown at a
     * shallower angle bursts in front of the bot and pushes it back off the target it is launching at.
     */
    private static final double AIM_PITCH = 80.0;
    /** View pitch of straight down, and how wide the aim point there is taken to be, in degrees. */
    private static final float FEET_PITCH = 90.0F;
    private static final float FEET_AIM = 3.0F;
    /** Ticks a flight may take before it counts as lost; an arc off a Wind Burst is some seventy of them. */
    private static final int FLIGHT_TIMEOUT = 200;
    /** Ticks a charge thrown at the feet is given to burst, during which standing on the ground is not a landing. */
    private static final int BURST_TICKS = 4;
    /** Ticks the stun slam plan may run. */
    private static final int STUN_TICKS = 40;
    /** Ticks before touchdown a smash is held for, so that it carries the whole fall rather than its first blocks. */
    private static final int SMASH_LEAD = 2;
    /** Ticks before touchdown from which a descent with nothing to hit turns the view down for the next charge. */
    private static final int PREPARE_TICKS = 8;
    /** Ticks the mace of a pair is given to follow the axe that opened the shield for it. */
    private static final int PAIR_TICKS = 3;
    /** Upward speed that says a smash has thrown the bot back up, which only Wind Burst does. */
    private static final double BURST_SPEED = 1.0;
    /** How far off a target the bot still throws the next charge as it comes down rather than landing. */
    private static final double CHAIN_RANGE = 4.5;
    /** Horizontal distance a swing from the last ticks of a descent still reaches across. */
    private static final double BOTTOM_REACH = 2.6;
    /** How much of the attack range a swing held for the next tick leaves unused. */
    private static final double REACH_MARGIN = 0.3;
    /** Height over the ground a burst under a falling bot may be for the throw to count as a launch. */
    private static final double RELAUNCH_GAP = 0.6;
    /** Height over the ground a burst still makes the landing harmless from, with room to spare. */
    private static final double SAVE_GAP = 2.5;
    /** Blocks a player falls for nothing. */
    private static final double SAFE_FALL = 3.0;
    /** Height over the ground, with no launch of its own behind it, at which the bot is in the air to stay. */
    private static final double ADRIFT_HEIGHT = 2.0;
    /** Ticks ahead the bot steers the target's movement to, the rest of the descent is too close to call. */
    private static final double STEER_TICKS = 10.0;
    /** Ticks ahead the bot puts its crosshair, which is as far as the descent can be called. */
    private static final double LEAD_TICKS = 6.0;
    /** How near the spot it is aiming for the bot stops pushing, along each of its two axes. */
    private static final double STEER_STOP = 0.3;
    /** Blocks below the bot the ground is looked for at. */
    private static final int GROUND_SCAN = 4;
    /** Horizontal gap a launch is still thrown from; past it the arc comes down short of the target. */
    private static final double LAUNCH_MAX_GAP = 7.0;
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
    /** View pitch a dive climbs under, in degrees: straight up, which is Minecraft's negative. */
    private static final float CLIMB_PITCH = -90.0F;
    /** How wide the aim point of the climb is taken to be, in degrees. */
    private static final float CLIMB_AIM = 5.0F;
    /** Upward speed under which a climb lights its next rocket. */
    private static final double CLIMB_SPEED = 0.8;
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
    private EntityPlayerActionPack pack;
    private int planCountdown;
    private int gearCountdown;
    private int aimTicks;
    private int flightTicks;
    private int shieldUpTicks;
    private boolean launching;
    private boolean stunWindow;
    /** Ticks since the axe took the shield down, which is how much of its cooldown has run. */
    private int stunTicks;
    /** Shields this bot's axe had taken down when it last looked, which is how a new one is noticed. */
    private int breaks;
    /** True while a charge this flight threw has not had the time to burst yet. */
    private boolean thrown;
    /** Damage this bot had dealt when its flight last looked, which is how a swing that landed is noticed. */
    private double dealt;
    /** True on the tick after the axe of a pair, when the mace that follows it is due. */
    private boolean pairing;
    private int pairTicks;
    /** Ticks the dive has been going, and the tick its last rocket went off on. */
    private int climbTicks;
    private int rocketTick = -ROCKET_GAP;
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
    /** What the target's Protection takes off a hit, read off what it wears whenever the hotbar is read. */
    private float guard;

    public MaceStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.gear = new MaceGear(bot);
        this.actions = new MaceActions(body);
        this.ground = new MaceBreachSwap(body, gear);
        this.melee = StyleIndex.create(BotPvpConfig.CombatStyle.MELEE, bot, body, cfg, random);
        this.breaks = stats.shieldBreaks;
        this.dealt = stats.damageDealt;
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
            guard = gear.protection(target);
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
        if (stats.shieldBreaks > breaks)
        {
            // The axe took a raised shield down, on the ground or on the way down: it is out of the target's
            // hands for a hundred ticks, and everything that lands in them lands unblocked.
            breaks = stats.shieldBreaks;
            stunWindow = true;
            stunTicks = 0;
        }
        else if (stunWindow && ++stunTicks >= SmashTiming.AXE_DISABLE_TICKS)
        {
            stunWindow = false;
        }
        if (phase == Phase.MELEE && !launching && adrift(me))
        {
            // In the air without a launch of its own behind it: thrown there by a hit or a burst, or back in
            // a fight it was carried out of. Whatever put it there, what is under it is a fall, and a fall is
            // what a mace is for.
            startFlight(false);
        }
        switch (phase)
        {
            case MELEE -> melee(body, perception, cfg, target, pack, me, seen);
            case AIM -> aim(body, perception, cfg, target, pack, me, seen);
            case FLIGHT -> flight(body, perception, cfg, target, pack, me, seen);
            case CLIMB -> climb(body, cfg, target, me, seen);
        }
    }

    @Override
    public void disengage(BotBody body)
    {
        phase = Phase.MELEE;
        launching = false;
        stunWindow = false;
        thrown = false;
        pairing = false;
        entry = EngagePlanner.WALK_IN;
        flightTicks = 0;
        climbTicks = 0;
        if (pack != null && pack.isNavEnabled())
        {
            pack.stopNavigation();
        }
        actions.glide(false);
        actions.fold();
    }

    // ===== on the ground =====

    /**
     * The ground phase, which a mace fighter keeps as short as it can. A raised shield inside the bot's reach is
     * taken down with the axe, a charged hand next to the target spends its one free swing, and then the bot is
     * gone again: the model is asked whether an entry that needs height beats walking in, and the launch is
     * thrown on the tick it says so. Only a bot with nothing left to launch with stays down and trades, with
     * the Breach mace in its hand for the tick of each swing.
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
        if (!launching && gear.wearingElytra() && gear.chestpieceSlot() >= 0)
        {
            // Down from a dive with the wings still on, and the chest plate they were exchanged with in the
            // hotbar: it goes back on before anything else, since the wings are no armour at all.
            want(body, gear.chestpieceSlot());
            body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false);
            if (gear.chestpieceSlot() == body.currentSlot())
            {
                actions.useMainHand();
            }
            return;
        }
        if (!launching && (seen.blocking || target.isBlocking()) && breaksOnGround(cfg, body, target, me, seen))
        {
            breakShield(body, cfg, target, me, seen);
            return;
        }
        if (!launching && freeSwing(cfg, body, target, me, seen))
        {
            // A sword is back at full charge long before an arc comes down, so a swing made here costs the
            // smash that follows it nothing.
            breachExchange(body, cfg, target, me, seen);
            return;
        }
        if (!launching && (winding(cfg, me, seen) || planCountdown-- <= 0))
        {
            planCountdown = PLAN_INTERVAL;
            plan(cfg, me, seen);
        }
        if (launching && beginAim(body, perception, cfg, target, pack, me, seen))
        {
            return;
        }
        if (breachGround(cfg, me, seen))
        {
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
     * the cooldown under, armour for Breach to cut, and the target inside the range a click reaches.
     */
    private boolean breachGround(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (!allowed(cfg, Technique.BREACH_SWAP) || launching || !me.onGround || dread > 0)
        {
            return false;
        }
        double near = horizontal(me, seen);
        return near <= cfg.meleeRange && ground.worthIt(seen.armor, seen.armorToughness, guard);
    }

    /** Whether a Breach swap can be swung on this very tick: the hand charged and the target under the crosshair. */
    private boolean freeSwing(BotPvpConfig cfg, BotBody body, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        return breachGround(cfg, me, seen) && charged(body) && body.canHit(target);
    }

    /**
     * Whether a swing off the charger would go out at full strength on this tick, by the game's own swing
     * timer. The game clears that timer on every change of the item in the hand as well as on every click, so a
     * count of the bot's own clicks runs a tick ahead of it after each swap, and a swing timed off that count is
     * thrown a tick early: under the nine tenths the game asks for before it calls a swing full strength, which
     * is what a critical hit and a sprint hit both need. The charger has to have been the item in the hand
     * since the tick before, because it is its attributes the swing is going to be made on.
     */
    private boolean charged(BotBody body)
    {
        return body.currentSlot() == ground.chargeSlot()
                && CombatMath.passesChargeGate(bot.getAttackStrengthScale(0.5F));
    }

    /**
     * The ground exchange: the hand holds the item the cooldown is collected under, whose thirteen tick cadence
     * is what the fight runs on, and the Breach mace goes in for the tick of each charged swing so the hit is
     * made through armour it has cut while carrying the charger's base damage.
     *
     * <p>The change of hand and the swing are one tick. The body puts a requested slot in the hand before it
     * swings, and the game re-reads the attributes and the swing timer of the hand only afterwards, at the end
     * of the tick, so a mace that has been in the hand for even one tick swings on its own attack speed with a
     * timer that has just been cleared, and lands for a fraction of a point.</p>
     */
    private void breachExchange(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int breaker = ground.hitSlot(seen.armor, seen.armorToughness, guard);
        boolean swing = breaker >= 0 && charged(body) && body.canHit(target);
        want(body, swing ? breaker : ground.chargeSlot());
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
        if (!allowed(cfg, Technique.ELYTRA) || !allowed(cfg, Technique.ROCKET) || launching
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

    /** Whether the bot has a launch to throw from where it stands: a charge off its cooldown and a target in its arc. */
    private boolean winding(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        return allowed(cfg, Technique.WIND_CHARGE) && me.onGround && gear.chargeReady()
                && horizontal(me, seen) <= LAUNCH_MAX_GAP;
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
     * Whether a raised shield is taken down from the ground. A bot that knows the pair inside one fall takes
     * the shield down on the way down instead and only spends a ground swing on it when the target is already
     * under its crosshair; every other bot has to walk in, since a smash into a raised shield is worth nothing.
     */
    private boolean breaksOnGround(BotPvpConfig cfg, BotBody body, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        if (!opensWindow(cfg, me, seen))
        {
            return false;
        }
        return body.canHit(target) || !allowed(cfg, Technique.FALL_STUN_SLAM) || !winding(cfg, me, seen);
    }

    /**
     * Walks in and puts the axe into a raised shield, the only hit that takes it out of the target's hands. The
     * axe goes into the hand on the tick of the swing and the swing does not wait for a charge: what takes a
     * shield down is the item, not how hard it was swung.
     */
    private void breakShield(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        int before = stats.shieldBreaks;
        boolean swing = body.canHit(target);
        want(body, swing ? gear.axeSlot() : charging(cfg, me, seen));
        int forward = horizontal(me, seen) > cfg.meleeRange * 0.8 ? 1 : 0;
        body.tick(seen, target, DuelSim.action(forward, 0, false, forward > 0, swing), false);
        if (stats.shieldBreaks > before && winding(cfg, me, seen))
        {
            // The shield is on cooldown now, so a smash has a window to land in, and a launch gets there well
            // inside it.
            launching = true;
            entry = EngagePlanner.WIND_CHARGE;
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
        sim.b.epf = guard;
        fillMace(sim.a, cfg);
        return SmashTiming.planStunSlam(sim, 0, true, -shieldUpTicks, STUN_TICKS).lands();
    }

    /**
     * Asks the model which entry beats walking in and remembers it until the bot has launched. A plan that
     * costs more simulated ticks than this bot has a share of is dropped and the ground phase goes on.
     *
     * <p>The model stops every entry at its first hit and scores it per tick, which prices a swing the bot
     * could throw this tick as if it could throw one every tick. Walking in is therefore charged the cadence of
     * the hand it is swung with, which is what one ground swing really costs; without that the model answers
     * "walk in and hit" from any range a swing reaches at all and the bot never leaves the ground.</p>
     *
     * @return whether an entry was found worth flying
     */
    private boolean plan(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
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
        sim.b.epf = guard;
        fillMace(sim.a, cfg);
        fillThreat(seen);
        EngagePlanner.Option[] options = EngagePlanner.choose(sim, 0, gear.breachLevel(), threat, PLAN_TICKS);
        budget.spend(PLAN_TICKS);
        stats.plannerCalls++;
        stats.simulatedTicks += PLAN_TICKS;
        EngagePlanner.Option walk = options[EngagePlanner.WALK_IN];
        float bar = walk.feasible ? rate(walk, sim.a.gateTicks) : 0.0F;
        EngagePlanner.Option best = null;
        for (int approach = EngagePlanner.WIND_CHARGE; approach <= EngagePlanner.PEARL; approach++)
        {
            EngagePlanner.Option option = options[approach];
            // A pearl ends on a ground swing like walking in does, and is charged the same cadence for it.
            int cadence = approach == EngagePlanner.PEARL ? sim.a.gateTicks : 1;
            if (option.feasible && wanted(cfg, me, approach) && rate(option, cadence) > bar)
            {
                best = option;
                bar = rate(option, cadence);
            }
        }
        if (canDive(cfg, me, seen))
        {
            EngagePlanner.Option dive = dive(sim, bar);
            best = dive != null ? dive : best;
        }
        if (best == null)
        {
            return false;
        }
        entry = best.approach;
        launching = true;
        return true;
    }

    /** What an entry is worth a tick, over at least as many ticks as the hit it ends on can be thrown again in. */
    private static float rate(EngagePlanner.Option option, int cadence)
    {
        return (option.dealt - option.taken) / Math.max(option.ticks, Math.max(cadence, 1));
    }

    /**
     * Prices the dive against what the other entries are worth right now. The planner cannot open a glide off
     * the ground, so the dive is asked from the height a climb reaches and what comes back is compared with the
     * best of the entries that can be started where the bot is standing: that is the question the task is, of
     * whether a fall from higher up is worth the ticks the climb costs.
     *
     * @param bar what the best entry so far is worth a tick
     * @return the dive when it beats that, or null
     */
    private EngagePlanner.Option dive(DuelSim sim, float bar)
    {
        BotBudget budget = BotBudget.instance();
        if (budget.join() < PLAN_TICKS)
        {
            stats.starvedTicks++;
            return null;
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
            return null;
        }
        return (dive.dealt - dive.taken) / (Math.max(dive.ticks, 1) + DIVE_CLIMB_TICKS) > bar ? dive : null;
    }

    /** Whether this bot's difficulty knows the given entry and it has what it takes to start it from here. */
    private boolean wanted(BotPvpConfig cfg, Perception.Snapshot me, int approach)
    {
        return switch (approach)
        {
            case EngagePlanner.WIND_CHARGE -> allowed(cfg, Technique.WIND_CHARGE) && me.onGround && gear.chargeReady();
            case EngagePlanner.PEARL -> allowed(cfg, Technique.PEARL) && gear.pearlSlot() >= 0 && gear.pearlReady();
            default -> false;
        };
    }

    // ===== the launch =====

    /**
     * Starts the entry the model chose. The wind charge has to be thrown straight down onto the bot's own
     * feet, so this is where the view goes down; the pearl is thrown at the target instead. Returns whether
     * the entry is under way, in which case this tick is spent on it.
     */
    private boolean beginAim(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack, Perception.Snapshot me, Perception.Snapshot seen)
    {
        switch (entry)
        {
            case EngagePlanner.PEARL -> {
                int pearl = gear.pearlSlot();
                want(body, pearl);
                body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false);
                if (pearl >= 0 && pearl == body.currentSlot() && gear.pearlReady() && actions.useMainHand())
                {
                    launching = false;
                    aimTicks = 0;
                    return true;
                }
                if (++aimTicks > AIM_TIMEOUT)
                {
                    launching = false;
                    aimTicks = 0;
                }
                return true;
            }
            case EngagePlanner.ELYTRA -> {
                if (!bot.onGround())
                {
                    phase = Phase.CLIMB;
                    climbTicks = 0;
                    rocketTick = -ROCKET_GAP;
                    gear.refresh();
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
                phase = Phase.AIM;
                aimTicks = 0;
                aim(body, perception, cfg, target, pack, me, seen);
                return true;
            }
            default -> {
                launching = false;
                return false;
            }
        }
    }

    /**
     * Starts a flight.
     *
     * @param charge whether a charge has just left the hand, which takes a few ticks to burst
     */
    private void startFlight(boolean charge)
    {
        flightTicks = 0;
        thrown = charge;
        pairing = false;
        dealt = stats.damageDealt;
        phase = Phase.FLIGHT;
    }

    /**
     * One tick of aiming. The snapshot handed to the body is a point under the bot's own feet, so the
     * body's own aim path turns the view down at the controller's human speed, and the charge leaves the
     * hand once the view is down and the hotbar has caught up.
     *
     * <p>The same aim is taken in the air, on the way down from a flight. There the charge is held until the
     * tick from which it bursts as the feet touch: thrown any earlier it goes off under a bot that is still
     * falling and only breaks the fall. A landing the game would charge for is the exception, since the burst
     * is what makes it free and it has to come before the feet do.</p>
     */
    private void aim(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack, Perception.Snapshot me, Perception.Snapshot seen)
    {
        int charge = gear.chargeSlot();
        if (charge < 0)
        {
            launching = false;
            phase = Phase.MELEE;
            melee(body, perception, cfg, target, pack, me, seen);
            return;
        }
        want(body, charge);
        // Straight down, with the yaw left where it is. The aim point is given a width of its own: read off a
        // point at the feet it would be half the sky wide, and a view that counts as on it from anywhere stops
        // turning well short of straight down for every bot that is not at the top of the ladder.
        body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false,
                new BotBody.Aim(bot.getYRot(), FEET_PITCH, FEET_AIM));
        aimTicks++;
        if (bot.getXRot() >= AIM_PITCH && charge == body.currentSlot() && timed(me) && actions.useMainHand())
        {
            startFlight(true);
            return;
        }
        if (aimTicks > AIM_TIMEOUT)
        {
            launching = false;
            if (me.onGround)
            {
                phase = Phase.MELEE;
            }
            else
            {
                startFlight(false);
            }
        }
    }

    /** Whether a charge thrown at the feet on this tick bursts where the bot wants it to. */
    private boolean timed(Perception.Snapshot me)
    {
        if (me.onGround)
        {
            return true;
        }
        double above = heightAboveGround(me);
        double gap = MaceLaunch.burstGap(above, me.vy);
        if (!landingHurts(me))
        {
            return gap <= RELAUNCH_GAP;
        }
        double later = MaceLaunch.burstGap(above + me.vy, (me.vy - DuelSim.GRAVITY) * DuelSim.VERTICAL_DRAG);
        return gap >= 0.0 && gap <= SAVE_GAP && (later < 0.0 || gap <= RELAUNCH_GAP);
    }

    /**
     * Whether coming down from here costs health. The game charges a player for a fall only as far as it has
     * come down past the place its last burst caught it at, a wind charge's or a smash's, so a launch thrown
     * from the ground lands on the ground for nothing however high it went.
     */
    private boolean landingHurts(Perception.Snapshot me)
    {
        double fall = me.fallDistance + Math.max(heightAboveGround(me), 0.0);
        Vec3 burst = bot.currentImpulseImpactPos;
        if (burst != null && bot.isIgnoringFallDamageFromCurrentImpulse())
        {
            fall = Math.min(fall, burst.y - ground0);
        }
        return fall > SAFE_FALL;
    }

    /**
     * The flight. The bot steers towards where the target will be and keeps the item it charges under in its
     * hand. The swing is held until the last ticks of the descent, or the last tick the target is in reach on,
     * because every block fallen before it is damage; the mace goes in on that tick. Against a shield a bot
     * that knows the pair puts the axe in a tick earlier and the mace follows out of the same fall.
     *
     * <p>What happens after the swing is read off the bot itself. A smash that carried Wind Burst has thrown
     * it back up and there is nothing to do but steer; one that did not leaves it hanging a block over the
     * ground, and a descent that has nothing to hit leaves it falling: both go to the next charge.</p>
     */
    private void flight(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack, Perception.Snapshot me, Perception.Snapshot seen)
    {
        flightTicks++;
        boolean waiting = thrown && flightTicks <= BURST_TICKS;
        if ((me.onGround && !waiting) || flightTicks > FLIGHT_TIMEOUT)
        {
            // On its feet again, or a charge that never lifted it: the flight is over and the tick is the ground's.
            phase = Phase.MELEE;
            launching = false;
            thrown = false;
            pairing = false;
            melee(body, perception, cfg, target, pack, me, seen);
            return;
        }
        fill(sim.a, me);
        fill(sim.b, seen);
        sim.b.epf = guard;
        fillMaceLevels(sim.a);
        double above = heightAboveGround(me);
        boolean falling = !me.onGround && me.vy < 0.0;
        boolean smash = !bot.isFallFlying() && me.fallDistance > CombatMath.SMASH_FALL_THRESHOLD;
        boolean close = DuelSim.inReach(sim.a, sim.b);
        int toGround = (int) MaceLaunch.drop(above, me.vy, 0.0)[0];
        // The game calls a swing a critical hit only at full strength, so a hand that is not there yet is given
        // every tick the descent has left to get there.
        boolean full = CombatMath.passesChargeGate(bot.getAttackStrengthScale(0.5F));
        boolean last = toGround <= (full ? SMASH_LEAD : 1) || !reachNext(me, seen) || !aimNext(me, seen);
        boolean shielded = allowed(cfg, Technique.READ) && seen.blocking;
        boolean pairs = allowed(cfg, Technique.FALL_STUN_SLAM) && gear.axeSlot() >= 0;
        boolean landed = stats.damageDealt > dealt;
        dealt = stats.damageDealt;

        if (pairing)
        {
            // The axe went in a tick ago and the shield is out of the way, so the mace follows it out of the
            // same fall. Its swing is not charged, but the fall is added to it whole.
            boolean due = smash && close && body.canHit(target);
            want(body, due ? heldMace(cfg, me, seen) : charging(cfg, me, seen));
            body.tick(leading(me, seen), target, fly(me, seen, due), false);
            pairing = !due && ++pairTicks < PAIR_TICKS;
            return;
        }
        if (pairs && shielded && falling && smash && close && (toGround <= SMASH_LEAD + 1 || last)
                && body.canHit(target))
        {
            want(body, gear.axeSlot());
            body.tick(leading(me, seen), target, fly(me, seen, true), false);
            pairing = true;
            pairTicks = 0;
            return;
        }
        // A shield this bot cannot open from the air is opened from the ground, with the axe, and a bot that
        // threw its next charge under it instead would never be down there to do it.
        boolean barred = shielded && !pairs;
        boolean chance = !barred && ahead(me, seen, toGround) <= BOTTOM_REACH;
        boolean hang = landed && me.vy < BURST_SPEED;
        // A landing that is going to cost health is prepared for from twice as far up: the charge that makes
        // it free has to burst before the feet touch, and out of a long fall that is a throw from ten blocks.
        boolean lost = falling && !chance && toGround <= (landingHurts(me) ? 2 * PREPARE_TICKS : PREPARE_TICKS);
        if ((hang || lost) && relaunches(cfg, me, seen, barred))
        {
            phase = Phase.AIM;
            aimTicks = 0;
            launching = true;
            entry = EngagePlanner.WIND_CHARGE;
            aim(body, perception, cfg, target, pack, me, seen);
            return;
        }
        boolean due = falling && smash && close && last && !shielded && body.canHit(target);
        want(body, due ? heldMace(cfg, me, seen) : charging(cfg, me, seen));
        body.tick(leading(me, seen), target, fly(me, seen, due), false);
    }

    /**
     * Whether the bot throws the next charge on its way down instead of landing: a target still inside the
     * range an arc comes down in, for a bot that knows the chain, or a landing that would otherwise cost it
     * health, for any bot that knows how to save one.
     *
     * @param barred whether the target is behind a shield this bot can only take down from the ground
     */
    private boolean relaunches(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen, boolean barred)
    {
        if (!allowed(cfg, Technique.WIND_CHARGE) || !gear.chargeReady())
        {
            return false;
        }
        return (!barred && allowed(cfg, Technique.CHAIN) && horizontal(me, seen) <= CHAIN_RANGE)
                || (allowed(cfg, Technique.SAFE_LANDING) && landingHurts(me));
    }

    /** Whether the target is still inside the swing's reach, with room to spare, once this tick has moved both. */
    private boolean reachNext(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double reach = DuelSim.REACH - REACH_MARGIN;
        double x = me.x + me.vx;
        double eye = me.y + me.vy + DuelSim.EYE_HEIGHT;
        double z = me.z + me.vz;
        double tx = seen.x + seen.vx;
        double ty = seen.y + (seen.onGround ? 0.0 : seen.vy);
        double tz = seen.z + seen.vz;
        double dx = Math.max(Math.max(tx - DuelSim.HALF_WIDTH - x, x - (tx + DuelSim.HALF_WIDTH)), 0.0);
        double dy = Math.max(Math.max(ty - eye, eye - (ty + DuelSim.HEIGHT)), 0.0);
        double dz = Math.max(Math.max(tz - DuelSim.HALF_WIDTH - z, z - (tz + DuelSim.HALF_WIDTH)), 0.0);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** Whether the bot is off the ground for longer than a jump lasts, with a fall under it worth swinging out of. */
    private boolean adrift(Perception.Snapshot me)
    {
        return !me.onGround && !bot.isFallFlying()
                && (heightAboveGround(me) > ADRIFT_HEIGHT || me.fallDistance > CombatMath.SMASH_FALL_THRESHOLD);
    }

    /**
     * Whether the view the bot has now would still be on the target once this tick has moved both. A bot
     * falling past a target it is nearly on top of has to turn its view half round within a tick or two, and
     * the view turns at a human's speed, so the swing that waits for the last tick there is a swing at nothing.
     */
    private boolean aimNext(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double yaw = Math.toRadians(bot.getYRot());
        double pitch = Math.toRadians(bot.getXRot());
        double tx = seen.x + seen.vx;
        double ty = seen.y + (seen.onGround ? 0.0 : seen.vy);
        double tz = seen.z + seen.vz;
        return BotBody.rayHitsBox(me.x + me.vx, me.y + me.vy + DuelSim.EYE_HEIGHT, me.z + me.vz,
                -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch),
                tx - DuelSim.HALF_WIDTH, ty, tz - DuelSim.HALF_WIDTH,
                tx + DuelSim.HALF_WIDTH, ty + DuelSim.HEIGHT, tz + DuelSim.HALF_WIDTH);
    }

    /** How far apart the two will be, on the ground plane, by the time the bot is down. */
    private static double ahead(Perception.Snapshot me, Perception.Snapshot seen, int ticks)
    {
        double lead = Math.min(ticks, STEER_TICKS);
        double dx = seen.x + seen.vx * lead - me.x - me.vx * lead;
        double dz = seen.z + seen.vz * lead - me.z - me.vz * lead;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The elytra dive. The wings go on by using the elytra out of the hotbar, which exchanges it with the
     * worn chest plate, and come off the same way, by using the chest plate they were exchanged with. Between
     * those two the bot goes straight up on a rocket, and what is left once the wings are off is a fall like
     * any other: the flight steers it onto the target and makes the smash.
     *
     * <p>Straight up is the point. A glide that is aimed at the target leaves the wings with close to a block
     * a tick of speed along the ground, and a bot that folds them at that speed lands ten blocks past what it
     * was diving at.</p>
     */
    private void climb(BotBody body, BotPvpConfig cfg, LivingEntity target, Perception.Snapshot me,
            Perception.Snapshot seen)
    {
        climbTicks++;
        if (bot.isFallFlying())
        {
            boolean high = seen.y - me.y <= -DIVE_HEIGHT;
            if (high || horizontal(me, seen) > DIVE_ABANDON || climbTicks > CLIMB_TIMEOUT)
            {
                // High enough over the target for the drop to be worth it, or a climb that is not going to get
                // there. Either way the wings have to go, because a smash does not count while they are open,
                // and they only go by using the chest piece they were exchanged with: that goes back on the
                // chest and the elytra lands in the hand.
                int chest = gear.chestpieceSlot();
                want(body, chest);
                body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false, climbAim(me, seen));
                if (chest < 0 || (chest == body.currentSlot() && actions.useMainHand()))
                {
                    actions.fold();
                    launching = false;
                    startFlight(false);
                }
                return;
            }
            rocket(body, me);
            // The view goes straight up and the yaw stays on the target, so that the view has less far to come
            // back down once the wings are off.
            body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false, climbAim(me, seen));
            return;
        }
        if (bot.onGround())
        {
            // The wings come off on the landing tick: a glide that keeps them out leaves the bot sailing along
            // the floor with nothing it can do, which is what a dive that could not get off the ground is.
            actions.fold();
            phase = Phase.MELEE;
            launching = false;
            return;
        }
        // Still getting off the ground. The wings go on the first tick they are reachable.
        if (!gear.wearingElytra())
        {
            int elytra = gear.elytraSlot();
            want(body, elytra);
            body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false);
            if (elytra < 0 || (elytra == body.currentSlot() && actions.useMainHand()))
            {
                actions.deploy();
            }
            return;
        }
        actions.deploy();
        body.tick(seen, target, DuelSim.action(0, 0, false, false, false), false, climbAim(me, seen));
    }

    /** The target's heading with the view straight up, which is what a rocket under the wings climbs along. */
    private BotBody.Aim climbAim(Perception.Snapshot me, Perception.Snapshot seen)
    {
        double dx = seen.x - me.x;
        double dz = seen.z - me.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        return new BotBody.Aim(yaw, CLIMB_PITCH, CLIMB_AIM);
    }

    /** One rocket whenever the climb has run out of the last one, as often as a launched firework allows. */
    private void rocket(BotBody body, Perception.Snapshot me)
    {
        if (me.vy >= CLIMB_SPEED || climbTicks - rocketTick < ROCKET_GAP || gear.rocketSlot() < 0
                || !gear.rocketReady())
        {
            return;
        }
        want(body, gear.rocketSlot());
        if (gear.rocketSlot() == body.currentSlot() && actions.useMainHand())
        {
            rocketTick = climbTicks;
        }
    }

    /**
     * The keys to hold in the air: towards where the target will be when the bot comes down, counting what
     * the bot is already carrying. Air control is weak and speed in the air is lost at nine percent a tick, so a
     * fighter that keeps pushing until it is over the target coasts well past it; the push is taken off, and
     * turned round, by as much as the speed it has will carry it on its own.
     *
     * @param click whether this tick swings
     */
    private int fly(Perception.Snapshot me, Perception.Snapshot seen, boolean click)
    {
        double ticks = MaceLaunch.drop(heightAboveGround(me), me.vy, 0.0)[0];
        double lead = Math.min(ticks, STEER_TICKS);
        double coast = (1.0 - Math.pow(DuelSim.AIR_DRAG, ticks)) / (1.0 - DuelSim.AIR_DRAG);
        double dx = seen.x + seen.vx * lead - me.x - me.vx * coast;
        double dz = seen.z + seen.vz * lead - me.z - me.vz * coast;
        double yaw = Math.toRadians(bot.getYRot());
        double along = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double across = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        int forward = Math.abs(along) < STEER_STOP ? 0 : along > 0.0 ? 1 : -1;
        int strafe = Math.abs(across) < STEER_STOP ? 0 : across > 0.0 ? 1 : -1;
        return DuelSim.action(forward, strafe, false, false, click);
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

    /** The mace this bot should be swinging: the Density one unless the target's armour wants Breach. */
    private int heldMace(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (allowed(cfg, Technique.BOUNCE) && gear.burstSlot() >= 0)
        {
            // A smash that carries Wind Burst throws the bot some twenty blocks back up, and the fall out of
            // that is worth several of the hit that started it, so the mace that carries it is the one a
            // fighter who knows the bounce swings in the air whatever the target is wearing.
            return gear.burstSlot();
        }
        return gear.maceSlot(me.fallDistance + Math.max(heightAboveGround(me), 0.0), seen.armor,
                seen.armorToughness, guard, picks(cfg));
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
        // The model keeps a facing as (-sinYaw, cosYaw), which is where the game looks from that yaw.
        fighter.sinYaw = Math.sin(yaw);
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
