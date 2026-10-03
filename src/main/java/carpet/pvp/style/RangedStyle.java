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
    /** Ticks a target has to be inside sword range before the bot puts its sword up, which is its reaction. */
    private static final int MELEE_REACTION = 6;
    /** Ticks between two flips of the sidestep, so a bot that is being circled does not walk one circle. */
    private static final int STRAFE_TICKS = 40;

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

    public RangedStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.loadout = new Loadout(bot);
        this.bow = new Bow(bot, body);
        this.throwing = new Throw(bot, body);
        this.spear = new Spear(bot, body);
        this.cart = new TntCart(bot, body, bow);
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

        if (gap <= SWORD_RANGE && loadout.sword >= 0)
        {
            melee();
        }
        else if (cart.rail() != null)
        {
            setCart();
        }
        else if (wantsCart())
        {
            layCart();
        }
        else if (wantsSpear() && gap <= SPEAR_APPROACH)
        {
            thrustSpear();
        }
        else if (wantsTrident() && gap <= TRIDENT_RANGE)
        {
            throwTrident();
        }
        else if (wantsCrossbow())
        {
            shootCrossbow();
        }
        else if (wantsBow())
        {
            shootBow();
        }
        else
        {
            keepDistance(false);
        }
    }

    @Override
    public void disengage(BotBody body)
    {
        bow.lower();
        loadout.forget();
        cart.forget();
        meleeTicks = 0;
        strafeTicks = 0;
    }

    // --- the techniques

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
        bow.hold(point, aim.drawTicks);
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
            throwing.cast(null, TridentItem.THROW_THRESHOLD_TIME, true);
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
        throwing.cast(point, TridentItem.THROW_THRESHOLD_TIME, true);
    }

    /**
     * A spear: charge it and run at the target, but only while the model says the closing speed is worth the
     * run. Inside the spear's own window the bot holds the charge and waits for the target to come to it.
     */
    private void thrustSpear()
    {
        ItemStack stack = loadout.stack(loadout.spear);
        holdSlot(loadout.spear);
        if (spear.held() > Spear.chargeWindow(stack))
        {
            // The charge has run out and no thrust of it can land any more, so it is let go and begun again.
            spear.charge(false);
            drive(null, DuelSim.NOOP, false);
            return;
        }
        if (live < SpearMath.minReach())
        {
            // Too close to thrust, which is the near limit a spear has, so give the ground back.
            spear.charge(true);
            drive(null, DuelSim.action(-1, 0, false, false, false), false);
            return;
        }
        boolean inside = live <= SpearMath.maxReach();
        if (inside && !spear.worthIt(target, stack))
        {
            spear.charge(true);
            drive(null, DuelSim.action(0, 0, false, false, false), false);
            return;
        }
        spear.charge(true);
        drive(null, DuelSim.action(1, 0, false, true, false), false);
    }

    /**
     * Lays a tnt minecart next to the target when the plan says the blast is worth the bot's time. A cart has to
     * be laid within reach, so the bot walks in while there is nowhere to put one.
     */
    private void layCart()
    {
        if (cart.place(me, seen, difficulty(), loadout))
        {
            drive(null, DuelSim.NOOP, false);
            return;
        }
        drive(null, DuelSim.action(1, 0, false, false, false), false);
    }

    /**
     * Sets a cart that is already down off, and keeps walking away the whole time, which is what a player
     * does with one: the shot only goes in once the blast can no longer reach the bot.
     */
    private void setCart()
    {
        int action = distance(true);
        ProjectileSim.Target mark = cart.cart();
        double range = Math.hypot(mark.x - bot.getX(), mark.z - bot.getZ());
        if (!cart.readyToLight(loadout, difficulty()))
        {
            body.releaseItem();
            drive(null, action, false);
            return;
        }
        holdSlot(loadout.flameBow);
        if (body.currentSlot() != loadout.flameBow && body.pendingSlot() != loadout.flameBow)
        {
            body.requestSlot(loadout.flameBow);
            body.releaseItem();
            drive(null, action, false);
            return;
        }
        ProjectileAim.Aim aim = bow.aimAt(mark, -1);
        if (!aim.solved)
        {
            body.releaseItem();
            drive(null, action, false);
            return;
        }
        BotBody.Aim point = Bow.pointOf(aim, range);
        drive(point, action, false);
        bow.hold(point, aim.drawTicks);
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

    private boolean wantsCart()
    {
        return cfg.flag("ranged.tntcart") && atLeast(BotPvpConfig.Difficulty.SKILLED)
                && loadout.cart >= 0 && loadout.rails >= 0 && loadout.ignites && loadout.arrows > 0;
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
