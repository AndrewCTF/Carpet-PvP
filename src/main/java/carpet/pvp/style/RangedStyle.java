package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.ranged.Bow;
import carpet.pvp.ranged.Kiting;
import carpet.pvp.ranged.Loadout;
import carpet.pvp.ranged.Spear;
import carpet.pvp.ranged.Throw;
import carpet.pvp.ranged.TntCart;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.ProjectileSim;
import carpet.pvp.sim.SpearMath;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Fighting from outside sword range: a bow, a crossbow, a trident, a spear and a tnt minecart, with the sword for
 * whatever gets inside anyway.
 *
 * <p>Each tick the style reads what it perceives of the target, asks which weapon is worth playing against it at
 * the distance the bot believes they are apart, and hands the result to the body. Nothing is fired, thrown or
 * placed from here: a bow shot is the bot letting go of a bow it has been holding at an aim the solver gave it, a
 * trident throw is the same, a crossbow is a load and a second click, and the rail and the cart are uses of the
 * items in its hand. The aim goes through the look controller, so the view turns at the bot's own speed and the
 * release happens on the tick the view is actually on the point.</p>
 *
 * <p>Which techniques a bot has is its difficulty and its own options. A beginner gets a bow and little else; a
 * crossbow needs casual hands, a trident and a spear need average ones, and only a skilled bot lays a tnt
 * minecart, because that is the one it has to run away from. Every one of them can be switched off per bot with
 * {@code /bot option <name> ranged.<technique> false}.</p>
 */
public final class RangedStyle implements BotStyle
{


    /**
     * How close the bot lets a target come before it puts the sword up. A sword reaches three blocks from the
     * eyes to the box, and the bot only knows where the target was a couple of ticks ago, which at closing
     * speed is another half block, so it commits a little before the reach rather than exactly on it.
     */
    public static final double SWORD_RANGE = 4.0;
    /** How close a target has to be for the spear to be the right weapon rather than the bow. */
    public static final double SPEAR_APPROACH = 7.5;
    /** Beyond this the bow is the better weapon than a trident, which drops off much faster. */
    public static final double TRIDENT_RANGE = 18.0;
    /**
     * How close a bot has to be before it prefers a crossbow to a trident. A crossbow shot leaves at the same
     * speed as a bow's but takes a second and a quarter to load, so it is a weapon for the short distance
     * rather than the far one, and a trident or a plain bow is the better answer anywhere else.
     */
    public static final double CROSSBOW_RANGE = 8.0;
    /** Ticks a target has to be inside sword range before the bot puts its sword up, which is its reaction. */
    private static final int MELEE_REACTION = 6;
    /**
     * Degrees of slack on the cart shot's release gate. The aim point's own radius is what the look controller
     * stops correcting at, and the two are measured differently, so a gate asking for exactly that radius can
     * leave the bot drawing at a cart forever. A cart is a block wide a few blocks away, so a couple of degrees
     * is still well inside it.
     */
    private static final double CART_SHOT_SLACK = 5.0D;
    /** Ticks between two flips of the sidestep, so a bot that is being circled does not walk one circle. */
    private static final int STRAFE_TICKS = 40;

    /**
     * What the bot last let go of at its target, so that the next hit the target takes is counted against it.
     * A projectile that flies twenty blocks lands a good many ticks after it left the hand, so the counters
     * cannot be booked at the moment of the throw: the throw is what is counted there, and the hit is counted
     * when the health of the target goes, while this still says what was fired at it.
     */
    private enum Shot
    {
        NONE, ARROW, CROSSBOW, TRIDENT, SPEAR
    }

    private final EntityPlayerMPFake bot;
    private final BotStats stats;
    private final Loadout loadout;
    private final Bow bow;
    private final Throw throwing;
    private final Spear spear;
    private final TntCart cart;
    private final Kiting kiting;

    private BotBody body;
    private BotPvpConfig cfg;
    private LivingEntity target;
    private Perception.Snapshot seen;
    private Perception.Snapshot me;
    private double gap;
    private double live;
    private int meleeTicks;
    private int strafeTicks;
    /** True while the bot is committed to a run in with a charged spear, which is what a thrust is behind. */
    private boolean spearRun;
    private Shot fired = Shot.NONE;
    private ProjectileAim.Aim cartAimHeld;
    private Vec3 cartAimMark;
    private float lastTargetHealth = Float.NaN;

    public RangedStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.loadout = new Loadout(bot);
        this.bow = new Bow(bot, body);
        this.throwing = new Throw(bot, body);
        this.spear = new Spear(bot, body);
        this.cart = new TntCart(bot, body);
        this.kiting = new Kiting(bot);
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        this.body = body;
        this.cfg = cfg;
        this.target = target;
        this.seen = perception.target(cfg.reactionDelay + cfg.pingTicks);
        this.me = perception.self();
        if (seen == null || !seen.seen)
        {
            body.hold(null, target);
            return;
        }
        loadout.refresh();
        cart.tick();
        gap = horizontal(me, seen);
        live = bot.distanceTo(target);
        landed();
        if (cart.lit())
        {
            stats.cartsLit++;
        }
        if (!cart.armed())
        {
            cartAimHeld = null;
        }
        if (cart.spent(loadout))
        {
            // A cart with nothing to set it off with is a live minecart next to the target and nothing else, so
            // the bot stops putting its back to it and goes back to fighting with what it can shoot.
            cart.forget();
        }

        if (gap <= SWORD_RANGE && loadout.sword >= 0)
        {
            melee();
        }
        else if (cart.armed())
        {
            setCart();
        }
        else if (wantsCart() || cart.laying())
        {
            layCart();
        }
        else if (wantsSpear() && gap <= SPEAR_APPROACH)
        {
            thrustSpear();
        }
        else if (wantsCrossbow() && gap <= CROSSBOW_RANGE)
        {
            shootCrossbow();
        }
        else if (wantsTrident() && gap <= TRIDENT_RANGE)
        {
            throwTrident();
        }
        else if (wantsBow())
        {
            shootBow();
        }
        else if (wantsCrossbow())
        {
            shootCrossbow();
        }
        else
        {
            keepDistance(false);
        }
    }

    /**
     * The bot is not fighting anyone for a tick. That is not the same as the fight being over: a cart laid beside
     * an opponent is still standing when the opponent steps out of sight for a tick, and a trap the bot threw
     * away over that would never be lit. The weapon and the loadout are let go, and the cart is left to
     * {@link TntCart#rail()}, which forgets a cart that is really gone.
     */
    @Override
    public void disengage(BotBody body)
    {
        bow.lower();
        loadout.forget();
        meleeTicks = 0;
        strafeTicks = 0;
        spearRun = false;
    }

    // --- the techniques

    /**
     * Books the hit the target took, against whatever the bot last let go of at it. A projectile that is still
     * in the air has not hit anything yet, so this is the only place a hit on a non-melee weapon is counted,
     * and it is counted against the shot that was fired rather than against the weapon in the hand.
     */
    private void landed()
    {
        float health = target.getHealth() + target.getAbsorptionAmount();
        if (health < lastTargetHealth)
        {
            switch (fired)
            {
                case ARROW -> stats.arrowsHit++;
                case SPEAR -> stats.spearThrusts++;
                default -> { }
            }
            fired = Shot.NONE;
        }
        lastTargetHealth = health;
    }

    /** The sword, for a target that has come inside its reach. */
    private void melee()
    {
        holdSlot(loadout.sword);
        body.releaseItem();
        if (++strafeTicks >= STRAFE_TICKS)
        {
            strafeTicks = 0;
            kiting.turn();
        }
        // The reaction is counted while the target is inside sword range and reset only when it is not, so a
        // bot that has to wait for the swing to charge up still gets to swing.
        boolean close = live <= SWORD_RANGE;
        boolean charged = bot.getAttackStrengthScale(0.5F) > CombatMath.CHARGE_GATE;
        boolean wants = close && charged && ++meleeTicks >= MELEE_REACTION;
        if (!close)
        {
            meleeTicks = 0;
        }
        int action = DuelSim.action(live > 2.0D ? 1 : 0, 0, false, live > 2.0D && !wants, wants);
        drive(null, action, false);
    }

    /** A bow shot at a draw the solver picked for the distance, let go once the view is on the point. */
    private void shootBow()
    {
        holdSlot(loadout.bow);
        ProjectileAim.Aim aim = bow.aimAt(seen, drawWanted());
        if (!aim.solved)
        {
            bow.lower();
            keepDistance(false);
            return;
        }
        BotBody.Aim point = Bow.pointOf(aim, gap);
        drive(point, distance(false), false);
        fired(Shot.ARROW, bow.hold(point, aim.drawTicks));
    }

    /**
     * A crossbow: hold the button for the load, then click again to fire, exactly as a player does. The load
     * takes a second and a quarter, so the bot spends it walking backwards.
     */
    private void shootCrossbow()
    {
        ItemStack crossbow = loadout.stack(loadout.crossbow);
        holdSlot(loadout.crossbow);
        ProjectileAim.Aim aim = bow.crossbowAt(seen);
        if (!aim.solved)
        {
            body.releaseItem();
            keepDistance(false);
            return;
        }
        BotBody.Aim point = Bow.pointOf(aim, gap);
        boolean charged = CrossbowItem.isCharged(crossbow);
        // The load takes a second and a quarter, so the bot spends it walking backwards.
        drive(point, charged ? distance(false) : distance(true), false);
        if (!charged)
        {
            body.holdItem();
            return;
        }
        if (!bow.aimedAt(point))
        {
            body.holdItem();
            return;
        }
        if (body.holdingItem())
        {
            // A loaded crossbow is still held, and the game will not shoot it until the button is let go.
            body.releaseItem();
        }
        else
        {
            body.pack().start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.once());
            fired(Shot.CROSSBOW, true);
        }
    }

    /** A trident: charge it, solve the throw, and let go. With Riptide it is the bot itself that is thrown. */
    private void throwTrident()
    {
        ItemStack trident = loadout.stack(loadout.trident);
        holdSlot(loadout.trident);
        if (Throw.riptide(bot, trident))
        {
            // Riptide launches the thrower along its own view, so the body aims at the target itself and the
            // charge only has to be long enough for the game to accept it.
            drive(null, DuelSim.action(1, 0, false, true, false), false);
            fired(Shot.TRIDENT, throwing.cast(null, TridentItem.THROW_THRESHOLD_TIME, true));
            return;
        }
        ProjectileAim.Aim[] arcs = throwing.solve(seen);
        ProjectileAim.Aim aim = arcs[0].solved ? arcs[0] : arcs[1];
        if (!aim.solved)
        {
            body.releaseItem();
            keepDistance(false);
            return;
        }
        BotBody.Aim point = Bow.pointOf(aim, gap);
        drive(point, distance(false), false);
        fired(Shot.TRIDENT, throwing.cast(point, TridentItem.THROW_THRESHOLD_TIME, true));
    }

    /**
     * A spear: charge it and run at the target, which is how a player lands one. The charge does not slow a
     * player down ({@code USE_EFFECTS} lets a spear be used at full speed and sprinted with), so the closing
     * speed the thrust is behind is the run itself.
     *
     * <p>That makes the run the whole technique, and it has to start outside the spear's own reach: a thrust
     * taken from inside it has no speed behind it, so a bot that is already standing there backs out of its own
     * window and comes at the target again. Once the run has started it is not given up half way, because a
     * bot that turns round at the edge of the reach never builds up the speed the thrust is behind.</p>
     */
    private void thrustSpear()
    {
        ItemStack stack = loadout.stack(loadout.spear);
        holdSlot(loadout.spear);
        if (spear.spent())
        {
            // The charge has run out and no thrust of it can land any more, so it is let go and begun again.
            spear.charge(false);
            drive(null, DuelSim.NOOP, false);
            return;
        }
        spear.charge(true);
        double reach = spear.reachOf(target);
        if (reach < SpearMath.minReach())
        {
            // Inside the near limit no thrust reaches at all, so the ground goes back and the next run starts.
            spearRun = false;
            drive(null, DuelSim.action(-1, 0, false, false, false), false);
            return;
        }
        // A thrust lands while the target is inside the reach of the spear and the two are closing on each
        // other faster than the weapon's gate, and the run keeps going while it has not landed one yet.
        boolean landing = reach <= SpearMath.maxReach() && spear.fast(target, stack);
        if (landing)
        {
            // The game sweeps the spear's reach on every tick of the charge, so a thrust can land on any of
            // them: what is counted is the hit, against a thrust the bot is in a position to land.
            fired = Shot.SPEAR;
        }
        spearRun = spearRun ? !landing : reach > SpearMath.maxReach();
        if (spearRun || landing)
        {
            drive(null, DuelSim.action(1, 0, false, true, false), false);
            return;
        }
        drive(null, DuelSim.action(-1, 0, false, false, false), false);
    }

    /**
     * Lays a tnt minecart next to the target when the plan says the blast is worth the bot's time.
     *
     * <p>It lays the trap from where it stands rather than walking in to arm one: the plan looks a few blocks
     * around the bot, so a cell next to the target is nearly always within reach of it, and a bot that closes to
     * lay a cart walks into the sword range of the very fighter it was trying to keep away from. Putting one
     * down takes several ticks — a hotbar change reaches the hand on the tick after it is asked for, and each
     * of the two clicks waits for the view to be on the block and for the bot's own click rate — so the cell is
     * worked on until the cart is down or the plan gives it up.</p>
     */
    private void layCart()
    {
        // A cell is several ticks of the bot's time: nothing else is charged while it is laying one.
        body.releaseItem();
        drive(cart.aim(), DuelSim.NOOP, false);
        if (cart.place(loadout))
        {
            stats.cartsLaid++;
        }
    }

    /**
     * A cart that is already down: the bot walks backwards out of its own blast first, because a cart goes down
     * within a few blocks of it, and only shoots the flaming arrow in once the plan says the blast cannot reach
     * where it is standing. It keeps the ground it has gained for the whole shot.
     */
    private void setCart()
    {
        ProjectileSim.Target mark = cart.cart();
        double range = Math.hypot(mark.x - bot.getX(), mark.z - bot.getZ());
        if (!cart.readyToLight(loadout, difficulty()))
        {
            // Either the bot is still standing in the blast of the cart it laid, or it has nothing to light it
            // with. Walking backwards is out of the blast; the other case is settled in engage.
            body.releaseItem();
            drive(null, DuelSim.action(-1, 0, false, false, false), false);
            return;
        }
        // Standing still for the whole shot, and not sidestepping while it is drawn: a cart is a box less than
        // a block wide a few blocks away, so a bot that walks sideways while it aims keeps sliding the point
        // its view has to reach sideways faster than the view can turn, and every arrow it lets go of goes past.
        int action = DuelSim.NOOP;
        holdSlot(loadout.flameBow);
        if (body.currentSlot() != loadout.flameBow && body.pendingSlot() != loadout.flameBow)
        {
            body.requestSlot(loadout.flameBow);
            body.releaseItem();
            drive(null, action, false);
            return;
        }
        // A full draw and not the shortest one that reaches: the cart is a small box a few blocks away, and a
        // barely drawn arrow is the one the aim is least sure about, because a tick of draw either side of it
        // is a fifth of the arrow's speed. A player holds a cart shot at full draw for the same reason.
        ProjectileAim.Aim aim = cartAim(bow.aimAt(mark, ProjectileSim.BOW_FULL_DRAW), mark);
        if (!aim.solved)
        {
            body.releaseItem();
            drive(null, action, false);
            return;
        }
        BotBody.Aim point = Bow.pointOf(aim, range);
        drive(point, action, false);
        // Drawn and let go only once the view is actually on the cart. The patience a shot at a moving target
        // gets is not wanted here: the cart is not going anywhere, so waiting for the aim costs a draw and
        // firing without it spends an arrow on the floor beside the cart.
        body.holdItem();
        if (bow.aimedAt(point, CART_SHOT_SLACK) && bow.hold(point, aim.drawTicks, false, CART_SHOT_SLACK))
        {
            fired(Shot.ARROW, true);
            cart.shot();
        }
    }

    /**
     * The aim the bot is working on for a cart shot, which it keeps until the shot goes off. A cart is a fixed
     * point a few blocks away, so a fresh solution every tick is a view that keeps being sent somewhere slightly
     * different and never arrives: one solution, held until the arrow has left.
     */
    private ProjectileAim.Aim cartAim(ProjectileAim.Aim solved, ProjectileSim.Target mark)
    {
        Vec3 at = new Vec3(mark.x, mark.y, mark.z);
        if (cartAimHeld != null && cartAimMark != null && cartAimMark.distanceToSqr(at) < 0.5D)
        {
            return cartAimHeld;
        }
        cartAimHeld = solved.solved ? solved : null;
        cartAimMark = solved.solved ? at : null;
        return solved;
    }

    /**
     * Books a shot that has just been let go of, and notes what it was so that the hit it goes on to land can
     * be counted against it.
     *
     * @param what the weapon the shot came from
     * @param gone whether anything actually left the hand this tick
     */
    private void fired(Shot what, boolean gone)
    {
        if (!gone)
        {
            return;
        }
        fired = what;
        switch (what)
        {
            case ARROW -> stats.arrowsShot++;
            case CROSSBOW -> stats.crossbowShots++;
            case TRIDENT -> stats.tridentsThrown++;
            default -> { }
        }
    }

    // --- the movement

    /**
     * Walks out of reach, sidesteps while it holds the range it wanted, and gives up running when it has run
     * into something it cannot get past.
     */
    private void keepDistance(boolean backingOff)
    {
        // Whatever it was holding is let go: a bot that is walking has no use for a charge or a draw.
        body.releaseItem();
        drive(null, distance(backingOff), false);
    }

    private int distance(boolean backingOff)
    {
        double keep = cfg.number("ranged.keep");
        double near = Math.min(SWORD_RANGE + 2.0D, keep - 1.0D);
        boolean cornered = backingOff && !kiting.canRetreat(seen.x, seen.z);
        return kiting.move(gap, near, keep, cfg.strafe, cornered);
    }

    /** The one place a tick becomes a body tick, so every technique drives the bot the same way. */
    private void drive(BotBody.Aim aim, int action, boolean block)
    {
        body.tick(seen, target, action, block, aim);
    }

    // --- what the bot is allowed

    private boolean wantsBow()
    {
        return cfg.flag("ranged.bow") && loadout.canShoot();
    }

    private boolean wantsCrossbow()
    {
        return cfg.flag("ranged.crossbow") && loadout.crossbow >= 0 && loadout.arrows > 0
                && atLeast(BotPvpConfig.Difficulty.CASUAL);
    }

    private boolean wantsTrident()
    {
        return cfg.flag("ranged.trident") && loadout.trident >= 0 && atLeast(BotPvpConfig.Difficulty.AVERAGE);
    }

    private boolean wantsSpear()
    {
        return cfg.flag("ranged.spear") && loadout.spear >= 0 && atLeast(BotPvpConfig.Difficulty.AVERAGE);
    }

    /**
     * A cart is only offered when there is somewhere to lay one that reaches the target from where the bot
     * stands: the plan looks a few blocks around it, so a target out at bow range is out of reach of any cell
     * and the technique hands the tick back to the bow rather than walking the bot into a fight to arm one.
     */
    private boolean wantsCart()
    {
        return cfg.flag("ranged.tntcart") && atLeast(BotPvpConfig.Difficulty.SKILLED)
                && loadout.cart >= 0 && loadout.rails >= 0 && loadout.ignites && loadout.arrows > 0
                && cart.plan(me, seen, difficulty(), loadout);
    }

    private boolean atLeast(BotPvpConfig.Difficulty wanted)
    {
        return cfg.difficulty.ordinal() >= wanted.ordinal();
    }

    /**
     * The vanilla difficulty the blast maths scales by, read from the world rather than from the preset: an
     * explosion does nothing at all on a peaceful server, whatever the bot thinks of itself.
     */
    private int difficulty()
    {
        return bot.level().getDifficulty().getId();
    }

    // --- odds and ends

    private void holdSlot(int slot)
    {
        if (slot >= 0 && body.currentSlot() != slot && body.pendingSlot() != slot)
        {
            body.requestSlot(slot);
        }
    }

    /** The draw the bot commits to, or -1 when the solver is to take the shortest one that reaches. */
    private int drawWanted()
    {
        int draw = (int) cfg.number("ranged.draw");
        return draw < 0 || draw > ProjectileSim.BOW_FULL_DRAW ? -1 : draw;
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
