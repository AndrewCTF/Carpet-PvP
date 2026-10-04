package carpet.pvp.smp;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.CombatUtils;
import carpet.pvp.Perception;
import carpet.pvp.look.LookController;
import carpet.pvp.sim.Effects;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.SurvivalPolicy;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The hands of an SMP bot: the item it has in its main hand, the way it looks while it uses it, and
 * the one thing it has in flight at a time.
 *
 * <p>Everything goes through the body. The hotbar change is the body's, which takes effect a tick
 * later; the view is the body's look controller, so turning onto the spot a throw has to land on
 * costs the reaction time and the mouse steps it would cost a player; the item use is the action
 * pack's own USE action, which is what a right click is. Nothing here reaches into the world, so the
 * bot can only do what a player holding the same items in the same slots could.</p>
 */
public final class SmpHands
{
    /** What the style has to do with the body while the hands are busy. */
    public enum Take
    {
        /** The hands are free: the style fights. */
        FIGHT,
        /** The hands need the body to apply a hotbar change and to keep looking at the target. */
        HOLD,
        /** The hands drive the view, the movement and the item use themselves this tick. */
        OWN
    }

    private enum Step { SWITCH, AIM, USE, WAIT }

    /** Ticks a hotbar change takes before the item that came up may be used. */
    public static final int SLOT_SETTLE = 1;
    /** Ticks a hotbar change may take to come about at all. */
    private static final int SLOT_TIMEOUT = 6;
    /** Ticks the view may take to come onto the spot a throw has to land on. */
    public static final int AIM_TICKS = 20;
    /** Degrees off the spot the view has to be before the bot throws. */
    public static final double AIM_TOLERANCE = 4.0D;
    /** Angular radius the look controller treats the spot as having. */
    private static final float AIM_RADIUS = 2.0F;
    /** Ticks an item use may take before the bot gives up on it. */
    private static final int USE_TICKS = 6;
    /** Ticks the hands stay out of the way after a move that could not be carried out. */
    public static final int REFUSE_TICKS = 10;
    /** How far ahead of itself a bot throws a splash potion, in blocks. */
    public static final double SPLASH_AHEAD = 0.4D;
    /** How far the bot may walk before a throw aimed from where it stood is solved again. */
    private static final double STALE_AIM = 0.4D;
    /** Blocks a retreat throw has to have of room in front of the target to clear it. */
    public static final double PEARL_MIN_REACH = 1.6D;
    /** Blocks within which a retreat throw goes over the target rather than past it. */
    public static final double PEARL_LOFT_REACH = 7.0D;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final SmpGear gear;
    private final SmpSenses senses;

    private final ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
    private final double[] point = new double[2];
    private final double[] aimFrom = new double[3];

    private SmpPlan.Move move = SmpPlan.Move.FIGHT;
    private SurvivalPolicy.Buff buff = SurvivalPolicy.Buff.STRENGTH;
    private Step step = Step.SWITCH;
    private int slot = -1;
    private double peelBack;
    private int ticks;
    private SmpPlan.Move refused = SmpPlan.Move.FIGHT;
    private int refusedFor;
    private int held;
    private double aimYaw;
    private double aimPitch;
    private boolean hasAim;
    private boolean guard;

    /** Golden apples the bot has finished. */
    public int eaten;
    /** Splash healing potions the bot has thrown at its own feet. */
    public int potions;
    /** Buffs the bot has put back on. */
    public int buffs;
    /** Experience bottles the bot has thrown at its own feet. */
    public int bottles;
    /** Pearls the bot has thrown. */
    public int pearls;
    /** Cobwebs the bot has placed. */
    public int webs;
    /** Buckets of water the bot has put down. */
    public int buckets;
    /** Pieces of armour the bot has swapped. */
    public int armorSwaps;
    /** Totems the bot has moved into the offhand. */
    public int totems;
    /** How the last move ended, for the log and for the self-test. */
    public String note = "";
    /**
     * Where the last pearl came from and what its view was when it went: feet position, then
     * velocity, then whether it was on the ground. A self-test needs all of it to work out where the
     * throw should have landed.
     */
    public final double[] pearlFrom = new double[7];
    public float pearlYaw;
    public float pearlPitch;

    public SmpHands(BotBody body, SmpGear gear, SmpSenses senses)
    {
        this.bot = body.bot();
        this.body = body;
        this.gear = gear;
        this.senses = senses;
    }

    /** True while a move has the bot's hands. */
    public boolean busy()
    {
        return move.busy();
    }

    /**
     * Whether the bot keeps its shield in the offhand and puts it up around a slow action. A player
     * holds one hand on the item and cannot hold a shield in the same one, so the shield goes up
     * while the view is still turning onto the spot the item is thrown at, and again the tick the
     * item is spent: those are the only ticks the game leaves the shield's use slot free.
     */
    public void setGuard(boolean guard)
    {
        this.guard = guard;
    }

    /**
     * Runs one tick of whatever the hands are doing, starting a new move as soon as the style asks
     * for one and nothing else is in flight.
     *
     * @param wanted   the move the plan chose this tick
     * @param buff     which buff the move's potion is for
     * @param slot     the hotbar slot the move needs, -1 when the bot carries nothing for it
     * @param peelBack the distance a retreat throw should buy
     * @param seen     the target as the bot believes it looked
     * @param target   the real target, for the place a cobweb goes and its reach
     */
    public Take tick(SmpPlan.Move wanted, SurvivalPolicy.Buff buff, int slot, double peelBack,
            Perception.Snapshot seen, LivingEntity target)
    {
        if (refusedFor > 0)
        {
            refusedFor--;
        }
        if (wanted != move)
        {
            stop();
        }
        if (move == SmpPlan.Move.FIGHT)
        {
            if (wanted == SmpPlan.Move.FIGHT)
            {
                return Take.FIGHT;
            }
            // A move that could not be carried out is left alone for a while, but a different one is
            // not held up behind it: the plan changing its mind is how the bot makes progress.
            if (refused == wanted && refusedFor > 0)
            {
                return Take.FIGHT;
            }
            if (wanted == SmpPlan.Move.TOTEM)
            {
                totem();
                return Take.FIGHT;
            }
            if (wanted == SmpPlan.Move.SWAP_ARMOR)
            {
                swapArmor();
                return Take.FIGHT;
            }
            if (slot < 0)
            {
                giveUp("nothing in the hotbar would do it");
                return Take.FIGHT;
            }
            move = wanted;
            this.buff = buff;
            this.slot = slot;
            this.peelBack = peelBack;
            step = Step.SWITCH;
            ticks = 0;
        }
        return advance(seen, target);
    }

    private Take advance(Perception.Snapshot seen, LivingEntity target)
    {
        switch (step)
        {
            case SWITCH ->
            {
                if (bot.getInventory().getSelectedSlot() == slot)
                {
                    // A hotbar change only takes effect a tick later, so on the first tick the item is
                    // in hand the bot is still working out what it picked up.
                    if (ticks >= SLOT_SETTLE)
                    {
                        step = move.aimsOffTarget() ? Step.AIM : Step.USE;
                        ticks = 0;
                    }
                    return Take.HOLD;
                }
                if (body.pendingSlot() != slot)
                {
                    body.requestSlot(slot);
                }
                if (++ticks > SLOT_TIMEOUT)
                {
                    return giveUp("the hotbar never gave it slot " + slot);
                }
                return Take.HOLD;
            }
            case AIM ->
            {
                // A throw at the bot's own feet follows the bot as it walks up to it, so it is solved
                // again every tick; a throw at a fixed point is solved once, since the bot has to
                // turn onto a point that is not moving with it.
                if ((!hasAim || moved(bot)) && !solve(seen, target))
                {
                    return giveUp("there was no throw that would have landed where it needed one");
                }
                boolean onSpot = look();
                // The throw is not away yet, so the shield's hand is free and a player would have it
                // up through the wind-up of the throw rather than after it.
                if (!onSpot && guard())
                {
                    body.raiseShield();
                }
                if (onSpot)
                {
                    step = Step.USE;
                    ticks = 0;
                    return Take.OWN;
                }
                if (++ticks > AIM_TICKS)
                {
                    return giveUp("the view never came onto the spot the throw needed");
                }
                return Take.OWN;
            }
            case USE ->
            {
                if (move.aimsOffTarget() && !look())
                {
                    return giveUp("the view moved off the spot while it threw");
                }
                if (!move.aimsOffTarget())
                {
                    aimAtTarget(seen);
                }
                use();
                step = Step.WAIT;
                ticks = 0;
                return Take.OWN;
            }
            default ->
            {
                if (spent())
                {
                    finish();
                    return Take.FIGHT;
                }
                if (++ticks > waitTicks())
                {
                    return giveUp("the item never came out of the hand, using "
                            + bot.isUsingItem() + " on " + bot.getUseItem() + " at "
                            + bot.getFoodData().getFoodLevel() + " food");
                }
                // The hands drive the view for every move they have in flight: a bot that has an item
                // raised in its hand cannot also have a shield up, and letting the body put the shield
                // back would take the item's use away again.
                if (move.aimsOffTarget())
                {
                    look();
                }
                else
                {
                    aimAtTarget(seen);
                }
                return Take.OWN;
            }
        }
    }

    private Take own()
    {
        look();
        return Take.OWN;
    }

    /** True once the bot has walked far enough that a throw aimed from where it stood has gone stale. */
    private boolean moved(EntityPlayerMPFake bot)
    {
        double from = (bot.getX() - aimFrom[0]) * (bot.getX() - aimFrom[0])
                + (bot.getZ() - aimFrom[2]) * (bot.getZ() - aimFrom[2]);
        return from > STALE_AIM * STALE_AIM;
    }

    /** Solves the throw the move needs and writes its yaw and pitch into the aim. */
    private boolean solve(Perception.Snapshot seen, LivingEntity target)
    {
        fillShooter();
        switch (move)
        {
            case HEAL_THROW, BUFF_THROW ->
            {
                // A splash a little ahead of the thrower, which it walks into: the four block splash
                // covers both, and it does not have to look straight down to place one.
                double pitch = SmpAim.aheadPitch(shooter, bot.getY(), SPLASH_AHEAD, bot.getYRot());
                if (Double.isNaN(pitch))
                {
                    return false;
                }
                aimYaw = bot.getYRot();
                aimPitch = pitch;
            }
            case MEND, BUCKET ->
            {
                // Both of these have to land on the bot itself: the experience orb has to be under it
                // to be picked up, and the water has to go where it is standing. That throw has no
                // horizontal part, so there is nothing to aim left or right at.
                double pitch = SmpAim.feetPitch(shooter, bot.getY(), bot.getYRot());
                if (Double.isNaN(pitch))
                {
                    return false;
                }
                aimYaw = bot.getYRot();
                aimPitch = pitch;
            }
            case PEARL ->
            {
                // A pearl that starts inside the target's reach has nothing to fly through but it, and
                // a pearl that goes into an entity is spent without moving its thrower at all. Close
                // in, the throw is lobbed over the target instead of thrown flat past it.
                double gap = target.distanceTo(bot);
                if (gap < PEARL_MIN_REACH)
                {
                    return false;
                }
                ProjectileAim.Aim aim = SmpAim.peelAway(shooter, seen.x, seen.z, Math.floor(bot.getY()),
                        peelBack, gap < PEARL_LOFT_REACH);
                if (!aim.solved)
                {
                    return false;
                }
                aimYaw = aim.yaw;
                aimPitch = aim.pitch;
            }
            case WEB ->
            {
                // The live target, because where the cobweb goes is the reach test of the placement.
                double gap = Math.hypot(target.getX() - bot.getX(), target.getZ() - bot.getZ());
                if (gap < SmpSenses.WEB_MIN || gap > SmpSenses.WEB_MAX)
                {
                    return false;
                }
                SmpAim.lookAt(bot.getX(), bot.getEyeY(), bot.getZ(), target.getX(), target.getY(), target.getZ(), point);
                aimYaw = point[0];
                aimPitch = point[1];
            }
            default ->
            {
                return false;
            }
        }
        aimFrom[0] = shooter.x;
        aimFrom[1] = shooter.y;
        aimFrom[2] = shooter.z;
        hasAim = true;
        return true;
    }

    /**
     * One tick of the view turning onto the aim, at the human speed of the bot's own look controller.
     *
     * @return true once the view is on the spot, so a throw can go
     */
    private boolean look()
    {
        LookController controller = body.look();
        controller.aimAt((float) aimYaw, (float) aimPitch, AIM_RADIUS);
        float fromYaw = controller.yaw();
        float fromPitch = controller.pitch();
        controller.tick();
        float yaw = controller.yaw();
        float pitch = controller.pitch();
        body.stats().recordRotationStep(wrap(yaw - fromYaw), pitch - fromPitch, body.profile().grid());
        walk();
        body.pack().look(yaw, pitch);
        return Math.abs(pitch - aimPitch) <= AIM_TOLERANCE && Math.abs(wrap(yaw - (float) aimYaw)) <= AIM_TOLERANCE;
    }

    /**
     * One tick of the view following the target, the same aim and the same mouse steps the body uses
     * while it fights, for the moves that keep the target in view.
     */
    private void aimAtTarget(Perception.Snapshot seen)
    {
        LookController controller = body.look();
        if (seen == null || !seen.seen)
        {
            controller.clearTarget();
            walk();
            body.pack().look(controller.yaw(), controller.pitch());
            return;
        }
        double dx = seen.x - bot.getX();
        double dy = seen.y + seen.height * 0.5D - bot.getEyeY();
        double dz = seen.z - bot.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1.0E-6D)));
        float radius = (float) Math.toDegrees(Math.atan2(0.45D, Math.max(flat, 0.1D)));
        controller.aimAt(yaw, pitch, radius);
        float fromYaw = controller.yaw();
        float fromPitch = controller.pitch();
        controller.tick();
        float nowYaw = controller.yaw();
        float nowPitch = controller.pitch();
        body.stats().recordRotationStep(wrap(nowYaw - fromYaw), nowPitch - fromPitch, body.profile().grid());
        walk();
        body.pack().look(nowYaw, nowPitch);
    }

    /** Keeps the bot walking on the way it was while its hands were busy, without a charge's sprint. */
    private void walk()
    {
        EntityPlayerActionPack pack = body.pack();
        pack.setForward(1.0F);
        pack.setStrafing(0.0F);
        pack.setSneaking(false);
        pack.setSprinting(false);
    }

    private void use()
    {
        body.lowerShield();
        held = gear.countOf(move, buff);
        if (move == SmpPlan.Move.PEARL)
        {
            // The throw happens inside the action pack, later in this same tick, and the view is not
            // touched again before it does, so this is the aim and the state the pearl goes with.
            pearlFrom[0] = bot.getX();
            pearlFrom[1] = bot.getY();
            pearlFrom[2] = bot.getZ();
            pearlFrom[3] = bot.getDeltaMovement().x;
            pearlFrom[4] = bot.getDeltaMovement().y;
            pearlFrom[5] = bot.getDeltaMovement().z;
            pearlFrom[6] = bot.onGround() ? 1.0D : 0.0D;
            pearlYaw = bot.getYRot();
            pearlPitch = bot.getXRot();
        }
        // An item the game holds down has to keep its use action running: the action pack lets go of
        // whatever the bot is using on any tick it does not execute the action, so a one-shot action
        // would restart the eat on every tick and the apple would never be finished.
        body.pack().start(EntityPlayerActionPack.ActionType.USE,
                move.held() ? EntityPlayerActionPack.Action.continuous()
                        : EntityPlayerActionPack.Action.onceUntilSuccess());
    }

    private boolean spent()
    {
        return gear.countOf(move, buff) < held;
    }

    private int waitTicks()
    {
        return move == SmpPlan.Move.EAT || move == SmpPlan.Move.DRINK_BUFF
                ? Effects.CONSUME_TICKS + USE_TICKS
                : USE_TICKS;
    }

    private void finish()
    {
        switch (move)
        {
            case EAT ->
            {
                senses.ate();
                eaten++;
                note = "ate a golden apple";
            }
            case HEAL_THROW ->
            {
                senses.drank();
                potions++;
                note = "threw a splash healing at its own feet";
            }
            case BUFF_THROW, DRINK_BUFF ->
            {
                senses.drank();
                buffs++;
                note = "put " + (buff == SurvivalPolicy.Buff.SPEED ? "swiftness" : "strength") + " back on";
            }
            case MEND ->
            {
                senses.mended();
                bottles++;
                note = "threw an experience bottle at its own feet";
            }
            case PEARL ->
            {
                pearls++;
                note = "pearled away";
            }
            case WEB ->
            {
                webs++;
                note = "dropped a cobweb where the target was running";
            }
            case BUCKET ->
            {
                buckets++;
                note = "put water under its own feet";
            }
            default ->
            {
                note = "";
            }
        }
        stop();
        if (guard())
        {
            // The hand the item was in is free again, so the shield goes straight back up.
            body.raiseShield();
        }
    }

    /** True while the bot is meant to keep its shield in the offhand and put it up between uses. */
    private boolean guard()
    {
        return guard && gear.shield;
    }

    private void totem()
    {
        if (CombatUtils.ensureTotemInOffhand(bot))
        {
            totems++;
            senses.wantedTotem();
            note = "moved a totem into the offhand";
        }
    }

    private void swapArmor()
    {
        int piece = senses.swapPiece(gear);
        if (piece < 0)
        {
            return;
        }
        EquipmentSlot position = SmpGear.PIECES[piece];
        ItemStack worn = bot.getItemBySlot(position);
        ItemStack fresh = bot.getInventory().getItem(gear.spareSlot(piece));
        bot.getInventory().setItem(gear.spareSlot(piece), worn);
        bot.setItemSlot(position, fresh);
        senses.swappedArmor();
        armorSwaps++;
        note = "swapped a worn piece of armour for a spare";
    }

    /** Drops whatever the move was doing, so a plan that changes its mind is not fighting an old throw. */
    private void stop()
    {
        if (bot.isUsingItem())
        {
            body.pack().start(EntityPlayerActionPack.ActionType.USE, null);
            bot.releaseUsingItem();
        }
        move = SmpPlan.Move.FIGHT;
        step = Step.SWITCH;
        ticks = 0;
        hasAim = false;
        slot = -1;
    }

    private Take giveUp(String why)
    {
        note = "gave up " + move + ": " + why;
        refused = move;
        refusedFor = REFUSE_TICKS;
        stop();
        return Take.FIGHT;
    }

    /** Forgets the move in flight and every refusal, as when the bot stops fighting altogether. */
    public void release()
    {
        refused = SmpPlan.Move.FIGHT;
        refusedFor = 0;
        stop();
    }

    private void fillShooter()
    {
        shooter.x = bot.getX();
        shooter.y = bot.getY();
        shooter.z = bot.getZ();
        shooter.vx = bot.getDeltaMovement().x;
        shooter.vy = bot.getDeltaMovement().y;
        shooter.vz = bot.getDeltaMovement().z;
        shooter.onGround = bot.onGround();
    }

    private static float wrap(float degrees)
    {
        float wrapped = degrees % 360.0F;
        return wrapped >= 180.0F ? wrapped - 360.0F : wrapped < -180.0F ? wrapped + 360.0F : wrapped;
    }
}