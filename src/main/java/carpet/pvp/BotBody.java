package carpet.pvp;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotStats.ClickOutcome;
import carpet.pvp.look.LookController;
import carpet.pvp.look.LookProfile;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.Random;
import java.util.UUID;

/**
 * The body of a combat bot: the one place where a decision becomes a game action.
 *
 * <p>Aiming goes through a {@link LookController} driven by a profile of the bot's skill, so the view
 * turns at human speed in whole mouse steps and reacts like a human. Movement, sprint, sneak and
 * jump go through the setters of the bot's {@link EntityPlayerActionPack}. An attack only happens when
 * the game itself would allow it: the target inside the server's attack range, and a ray from the
 * bot's eyes along the view direction it currently has, not the one it aims for, hitting the target.
 * A click that fails either check is a miss and swings at nothing.</p>
 */
public final class BotBody
{
    /** Rate limiter for mouse clicks. */
    public static final class ClickLimiter
    {
        private final int period;
        private int cooldown;

        public ClickLimiter(double clicksPerSecond)
        {
            period = clicksPerSecond <= 0.0 ? 1 : Math.max(1, (int) Math.round(20.0 / clicksPerSecond));
        }

        /** Minimum ticks between two clicks. */
        public int period()
        {
            return period;
        }

        /** Ticks left before the next click is allowed. */
        public int cooldown()
        {
            return cooldown;
        }

        /** Counts one tick; returns true when a click may be made now. */
        public boolean tick()
        {
            if (cooldown > 0)
            {
                cooldown--;
                return false;
            }
            cooldown = period - 1;
            return true;
        }

        public void reset()
        {
            cooldown = 0;
        }
    }

    /**
     * The sprint reset of a real client: the server clears the sprint of an attacker that lands a
     * sprint hit, and a client that keeps the sprint key down does not get it back until it released
     * the key. The body never presses sprint while the lock is on, so a style has to spend a tick
     * without sprint to get it back, which is the W-tap or S-tap of the melee meta.
     */
    public static final class SprintLock
    {
        private boolean locked;

        /** True while sprint must stay off. */
        public boolean locked()
        {
            return locked;
        }

        /** Called after a sprint hit, which clears the sprint of the attacker server side. */
        public void onSprintHit()
        {
            locked = true;
        }

        /**
         * Applies the rule for one tick: a style that does not want sprint releases the lock, a style
         * that does not get nothing. Returns whether sprint may be pressed.
         */
        public boolean apply(boolean wantsSprint)
        {
            if (!wantsSprint)
            {
                locked = false;
                return false;
            }
            return !locked;
        }

        public void clear()
        {
            locked = false;
        }
    }

    private final EntityPlayerMPFake bot;
    private final EntityPlayerActionPack pack;
    private final LookProfile profile;
    private final LookController look;
    private final BotStats stats = new BotStats();
    private final ClickLimiter clicks;
    private final SprintLock sprintLock = new SprintLock();

    private int pendingSlot = -1;
    private int slot;
    private boolean shieldRaised;
    private int lastAction;
    private boolean lastBlock;
    private float previousHealth;
    private UUID targetId;

    public BotBody(EntityPlayerMPFake bot, EntityPlayerActionPack pack, LookProfile profile, Random rng,
            double clicksPerSecond)
    {
        this.bot = bot;
        this.pack = pack;
        this.profile = profile;
        this.look = new LookController(profile, rng);
        this.clicks = new ClickLimiter(clicksPerSecond);
        this.slot = bot.getInventory().getSelectedSlot();
        this.look.reset(bot.getYRot(), bot.getXRot());
        this.previousHealth = bot.getHealth() + bot.getAbsorptionAmount();
    }

    public EntityPlayerMPFake bot()
    {
        return bot;
    }

    public EntityPlayerActionPack pack()
    {
        return pack;
    }

    public BotStats stats()
    {
        return stats;
    }

    public LookController look()
    {
        return look;
    }

    public LookProfile profile()
    {
        return profile;
    }

    public boolean sprintLocked()
    {
        return sprintLock.locked();
    }

    public int clickPeriod()
    {
        return clicks.period();
    }

    /** Look profile of a bot with the given skill, in the 0 to 1 range. */
    public static LookProfile profileFor(double skill, float sensitivity)
    {
        return LookProfile.ofSkill(skill, sensitivity);
    }

    /**
     * One tick of the body: look at where the target was seen, move as asked, hold the shield as
     * asked and swing if a click is due and the game allows the hit.
     *
     * @param seen   the target as the bot believes it looked {@code delayTicks} ago, or {@code null}
     * @param target the real target entity, used for the reach test and the attack itself
     * @param action forward, strafe, jump, sprint and attack, encoded by {@link DuelSim#action}
     * @param block  whether the style wants the shield up this tick
     */
    public void tick(Perception.Snapshot seen, LivingEntity target, int action, boolean block)
    {
        lastAction = action;
        lastBlock = block;
        applyPendingSlot();
        followTarget(target);
        aim(seen);
        move(action);
        shield(block);
        strike(target, DuelSim.attack(action));
    }

    /**
     * A tick with nothing new planned: the body keeps looking at the target and repeats its movement,
     * but does not swing. This is what a bot does when it had no share of the simulation budget.
     */
    public void hold(Perception.Snapshot seen, LivingEntity target)
    {
        tick(seen, target, DuelSim.action(DuelSim.forward(lastAction), DuelSim.strafe(lastAction),
                DuelSim.jump(lastAction), DuelSim.sprint(lastAction), false), lastBlock);
    }

    /**
     * True when the game would let this bot hit the target now: inside the server's attack range, and
     * a ray from the eyes along the view direction it currently has hits the target's box.
     */
    public boolean canHit(LivingEntity target)
    {
        return inReach(target) && rayHits(target);
    }

    /**
     * Slab test of a ray against an axis-aligned box: true when the ray meets the box in front of the
     * origin. Straightforward maths with no Minecraft types, so the self-test's aim checks and the
     * unit tests can both use it.
     */
    public static boolean rayHitsBox(double ox, double oy, double oz, double dx, double dy, double dz,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
    {
        double enter = -Double.MAX_VALUE;
        double leave = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++)
        {
            double origin = switch (axis)
            {
                case 0 -> ox;
                case 1 -> oy;
                default -> oz;
            };
            double direction = switch (axis)
            {
                case 0 -> dx;
                case 1 -> dy;
                default -> dz;
            };
            double lo = switch (axis)
            {
                case 0 -> minX;
                case 1 -> minY;
                default -> minZ;
            };
            double hi = switch (axis)
            {
                case 0 -> maxX;
                case 1 -> maxY;
                default -> maxZ;
            };
            if (Math.abs(direction) < 1.0E-9)
            {
                if (origin < lo || origin > hi)
                {
                    return false;
                }
                continue;
            }
            double t1 = (lo - origin) / direction;
            double t2 = (hi - origin) / direction;
            if (t1 > t2)
            {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > enter)
            {
                enter = t1;
            }
            if (t2 < leave)
            {
                leave = t2;
            }
            if (enter > leave)
            {
                return false;
            }
        }
        return leave >= 0.0;
    }

    /** Requests a hotbar change, which as for a real client takes effect on the next tick. */
    public void requestSlot(int slot0)
    {
        pendingSlot = slot0;
    }

    /** Raises the offhand shield through the action pack's item use action. */
    public void raiseShield()
    {
        if (!shieldRaised)
        {
            pack.start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.continuous());
            shieldRaised = true;
        }
        stats.blockTicks++;
    }

    /** Drops the shield, as a player has to before attacking. */
    public void lowerShield()
    {
        if (shieldRaised)
        {
            pack.start(EntityPlayerActionPack.ActionType.USE, null);
            bot.releaseUsingItem();
            shieldRaised = false;
        }
    }

    /** The hotbar slot a weapon sits in, or -1 when the bot does not carry one. */
    public static int findSlot(Player bot, Item item)
    {
        for (int slot = 0; slot < 9; slot++)
        {
            if (bot.getInventory().getItem(slot).is(item))
            {
                return slot;
            }
        }
        return -1;
    }

    /** Clears every held input and the pending hotbar change. */
    public void reset()
    {
        pack.stopMovement();
        lowerShield();
        sprintLock.clear();
        clicks.reset();
        pendingSlot = -1;
        lastAction = DuelSim.NOOP;
        lastBlock = false;
    }

    /** True if the bot carries a shield in its offhand. */
    public boolean hasShield()
    {
        return CombatUtils.offhandHolds(bot, Items.SHIELD);
    }

    /** The hotbar slot the bot is holding. */
    public int currentSlot()
    {
        return slot;
    }

    /** The hotbar slot a change is waiting for, or -1 when none is. */
    public int pendingSlot()
    {
        return pendingSlot;
    }

    /** True if a shield is up this tick. */
    public boolean shieldRaised()
    {
        return shieldRaised;
    }

    private void applyPendingSlot()
    {
        if (pendingSlot < 0)
        {
            return;
        }
        pack.setSlot(pendingSlot + 1);
        slot = pendingSlot;
        pendingSlot = -1;
    }

    private void move(int action)
    {
        pack.setForward(DuelSim.forward(action));
        pack.setStrafing(DuelSim.strafe(action));
        pack.setSneaking(false);
        pack.setSprinting(sprintLock.apply(DuelSim.sprint(action) && DuelSim.forward(action) > 0));
        if (DuelSim.jump(action) && bot.onGround())
        {
            pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
        }
    }

    private void shield(boolean block)
    {
        if (block)
        {
            raiseShield();
        }
        else
        {
            lowerShield();
        }
    }

    private void strike(LivingEntity target, boolean wants)
    {
        if (!wants)
        {
            clicks.tick();
            return;
        }
        if (!clicks.tick())
        {
            stats.throttledClicks++;
            return;
        }
        stats.clicks++;
        long now = bot.level().getGameTime();
        double distance = target == null ? Double.NaN : bot.distanceTo(target);
        float charge = bot.getAttackStrengthScale(0.5F);
        double[] aim = aimError(target);
        if (!inReach(target))
        {
            stats.recordClick(now, distance, charge, aim[0], aim[1], ClickOutcome.OUT_OF_REACH);
            pack.swing();
            return;
        }
        if (!rayHits(target))
        {
            stats.recordClick(now, distance, charge, aim[0], aim[1], ClickOutcome.OFF_AIM);
            pack.swing();
            return;
        }
        boolean gate = CombatMath.passesChargeGate(charge);
        boolean sprintHit = bot.isSprinting() && gate;
        boolean crit = CombatMath.isCritical(bot.fallDistance > 0.0F, bot.onGround(), false, false,
                false, false, true, bot.isSprinting(), gate);
        if (target.isBlocking() && Perception.weaponOf(bot.getMainHandItem()) == Perception.Weapon.AXE)
        {
            // An axe hit is what takes a shield down in vanilla, charged or not.
            stats.shieldBreaks++;
        }
        float before = target.getHealth() + target.getAbsorptionAmount();
        pack.attackEntity(target);
        stats.damageDealt += Math.max(0.0F, before - (target.getHealth() + target.getAbsorptionAmount()));
        stats.recordClick(now, distance, charge, aim[0], aim[1],
                gate ? ClickOutcome.HIT : ClickOutcome.UNCHARGED);
        if (!gate)
        {
            return;
        }
        if (crit)
        {
            stats.crits++;
        }
        if (sprintHit)
        {
            stats.sprintHits++;
            sprintLock.onSprintHit();
        }
    }

    /** True when the game would let this bot reach the target with the item it is holding. */
    private boolean inReach(LivingEntity target)
    {
        return target != null && target.isAlive()
                && bot.isWithinAttackRange(bot.getWeaponItem(), target.getBoundingBox(), 0.0);
    }

    /**
     * How far the view is from the middle of the target, sideways and up and down, in degrees. It is what says
     * whether a click that missed was pointed to the side or pointed over or under the target.
     */
    private double[] aimError(LivingEntity target)
    {
        if (target == null)
        {
            return new double[] {Double.NaN, Double.NaN};
        }
        double dx = target.getX() - bot.getX();
        double dy = target.getY() + target.getBbHeight() * 0.5 - bot.getEyeY();
        double dz = target.getZ() - bot.getZ();
        double flat = Math.max(Math.sqrt(dx * dx + dz * dz), 1.0E-6);
        double wantedYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double off = wantedYaw - bot.getYRot();
        off -= 360.0F * Math.round(off / 360.0F);
        return new double[] {Math.abs(off),
                Math.abs(Math.toDegrees(Math.atan2(dy, flat)) + bot.getXRot())};
    }

    /** True when a ray from the eyes along the view the bot has right now meets the target's box. */
    private boolean rayHits(LivingEntity target)
    {
        AABB box = target.getBoundingBox();
        return rayHitsBox(bot.getX(), bot.getEyeY(), bot.getZ(),
                -Mth.sin(Math.toRadians(bot.getYRot())) * Mth.cos(Math.toRadians(bot.getXRot())),
                -Mth.sin(Math.toRadians(bot.getXRot())),
                Mth.cos(Math.toRadians(bot.getYRot())) * Mth.cos(Math.toRadians(bot.getXRot())),
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /** Turns the view onto the seen position of the target, at the profile's speed. */
    private void aim(Perception.Snapshot seen)
    {
        if (seen == null || !seen.seen)
        {
            look.clearTarget();
            pack.look(look.yaw(), look.pitch());
            return;
        }
        double dx = seen.x - bot.getX();
        double dy = seen.y + seen.height * 0.5 - bot.getEyeY();
        double dz = seen.z - bot.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 1.0E-6)));
        float radius = (float) Math.toDegrees(Math.atan2(0.45, Math.max(flat, 0.1)));
        look.aimAt(yaw, pitch, radius);
        float beforeYaw = look.yaw();
        float beforePitch = look.pitch();
        look.tick();
        float yawNow = look.yaw();
        float pitchNow = look.pitch();
        stats.recordRotationStep(wrapDegrees(yawNow - beforeYaw), pitchNow - beforePitch, profile.grid());
        pack.look(yawNow, pitchNow);
    }

    /** Restarts the aim when the target changed, and books the damage the bot has taken. */
    private void followTarget(LivingEntity target)
    {
        float health = bot.getHealth() + bot.getAbsorptionAmount();
        if (previousHealth > health)
        {
            stats.damageTaken += previousHealth - health;
        }
        previousHealth = health;
        if (target != null && (targetId == null || !targetId.equals(target.getUUID())))
        {
            look.reset(bot.getYRot(), bot.getXRot());
        }
        targetId = target == null ? null : target.getUUID();
    }

    private static float wrapDegrees(float value)
    {
        float wrapped = value % 360.0F;
        return wrapped >= 180.0F ? wrapped - 360.0F : wrapped < -180.0F ? wrapped + 360.0F : wrapped;
    }

    }