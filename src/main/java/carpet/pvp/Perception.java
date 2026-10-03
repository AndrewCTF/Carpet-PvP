package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.sim.CombatMath;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a bot knows of itself and of its target, one tick at a time.
 *
 * <p>The bot reads its own state without delay; the target is only ever seen as it was
 * {@code reactionDelay + pingTicks} ticks ago, which is what {@link Ring#delayed(int)} hands back. Both
 * sides are stored in fixed ring buffers of {@link #HISTORY} ticks, and the ticks are recorded in
 * place, so a bot costs no allocation per tick.</p>
 */
public final class Perception
{
    /** Ticks of history kept per side. */
    public static final int HISTORY = 40;

    /** What a fighter holds in its main hand, as far as the combat maths is concerned. */
    public enum Weapon { NONE, SWORD, AXE, MACE, OTHER }

    /**
     * One tick of a fighter's observable state. A single instance is recycled per tick by the ring,
     * so anything that needs to keep a snapshot must copy it.
     */
    public static final class Snapshot
    {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public float yaw;
        public float pitch;
        public boolean onGround;
        public boolean sprinting;
        public boolean blocking;
        public float health;
        public float absorption;
        /** Attack strength scale as the game computes it, 0 to 1. */
        public float attackCharge;
        public float fallDistance;
        /** Bounding box height, the hitbox the bot aims the middle of. */
        public float height;
        public int hurtTime;
        public int invulnerableTime;
        /** Ticks since this fighter last swung, tracked from the resets of {@link #attackCharge}. */
        public int ticksSinceSwing;
        public Weapon weapon = Weapon.NONE;
        /** Main-hand damage, that is the attack damage attribute with its item modifiers. */
        public double weaponDamage = 1.0;
        /** Main-hand attack speed, that is the attack speed attribute with its item modifiers. */
        public double attackSpeed = 1.6;
        public float armor;
        public float armorToughness;
        public float epf;
        public double knockbackResistance;
        /** False while nothing has been observed yet. */
        public boolean seen;

        public float healthTotal()
        {
            return health + absorption;
        }

        public void copyFrom(Snapshot other)
        {
            x = other.x;
            y = other.y;
            z = other.z;
            vx = other.vx;
            vy = other.vy;
            vz = other.vz;
            yaw = other.yaw;
            pitch = other.pitch;
            onGround = other.onGround;
            sprinting = other.sprinting;
            blocking = other.blocking;
            health = other.health;
            absorption = other.absorption;
            attackCharge = other.attackCharge;
            fallDistance = other.fallDistance;
            height = other.height;
            hurtTime = other.hurtTime;
            invulnerableTime = other.invulnerableTime;
            ticksSinceSwing = other.ticksSinceSwing;
            weapon = other.weapon;
            weaponDamage = other.weaponDamage;
            attackSpeed = other.attackSpeed;
            armor = other.armor;
            armorToughness = other.armorToughness;
            epf = other.epf;
            knockbackResistance = other.knockbackResistance;
            seen = other.seen;
        }

        public void clear()
        {
            seen = false;
            health = 0.0f;
            absorption = 0.0f;
            attackCharge = 0.0f;
            hurtTime = 0;
            invulnerableTime = 0;
            ticksSinceSwing = 0;
            weapon = Weapon.NONE;
            blocking = false;
        }
    }

    /**
     * Fixed-size history of snapshots, newest last. The slots are created once and overwritten, so
     * recording a tick allocates nothing.
     */
    public static final class Ring
    {
        private final Snapshot[] slots = new Snapshot[HISTORY];
        private int next;
        private int count;

        public Ring()
        {
            for (int i = 0; i < HISTORY; i++)
            {
                slots[i] = new Snapshot();
            }
        }

        /** Stores the newest tick, dropping the oldest one once the ring is full. */
        public void record(Snapshot snapshot)
        {
            slots[next].copyFrom(snapshot);
            next = (next + 1) % HISTORY;
            if (count < HISTORY)
            {
                count++;
            }
        }

        /** The most recently recorded tick. */
        public Snapshot latest()
        {
            return slots[index(0)];
        }

        /**
         * The tick recorded {@code ticksAgo} ticks back. Clamped to the oldest tick kept, and to an
         * empty snapshot while nothing has been recorded yet.
         */
        public Snapshot delayed(int ticksAgo)
        {
            if (count == 0)
            {
                return slots[0];
            }
            return slots[index(Math.max(0, Math.min(ticksAgo, count - 1)))];
        }

        public int size()
        {
            return count;
        }

        /** Drops the history, as if nothing had been seen yet. */
        public void reset()
        {
            next = 0;
            count = 0;
            for (Snapshot slot : slots)
            {
                slot.clear();
            }
        }

        private int index(int ticksAgo)
        {
            return ((next - 1 - ticksAgo) % HISTORY + HISTORY) % HISTORY;
        }
    }

    private final Ring self = new Ring();
    private final Ring target = new Ring();
    private final Snapshot scratch = new Snapshot();
    /** Ticks since each side last swung, recovered from the resets of the attack charge. */
    private int selfSwings;
    private int targetSwings;

    /** The bot's own state, current. */
    public Snapshot self()
    {
        return self.latest();
    }

    /** The target as it was {@code delayTicks} ticks ago. */
    public Snapshot target(int delayTicks)
    {
        return target.delayed(delayTicks);
    }

    /** The target's history, for swing detection and the like. */
    public Ring targetHistory()
    {
        return target;
    }

    /** True once the target has been observed at least once. */
    public boolean hasTarget()
    {
        return target.latest().seen;
    }

    /** Records this tick of the bot and, if there is one, of its target. */
    public void update(EntityPlayerMPFake bot, LivingEntity enemy)
    {
        fill(scratch, bot);
        scratch.ticksSinceSwing = countSwing(self.latest(), scratch, selfSwings);
        selfSwings = scratch.ticksSinceSwing;
        self.record(scratch);

        if (enemy == null)
        {
            target.reset();
            targetSwings = 0;
            return;
        }
        fill(scratch, enemy);
        scratch.ticksSinceSwing = countSwing(target.latest(), scratch, targetSwings);
        targetSwings = scratch.ticksSinceSwing;
        if (!(enemy instanceof Player))
        {
            // A mob has no attack strength scale to watch, so its charge is derived from the counter.
            scratch.attackCharge = chargeOf(scratch.ticksSinceSwing, scratch.attackSpeed);
        }
        target.record(scratch);
    }

    /** Forgets the history of both sides. */
    public void reset()
    {
        self.reset();
        target.reset();
        selfSwings = 0;
        targetSwings = 0;
    }

    /**
     * A swing resets the attack strength scale, so a big drop between two ticks means one happened;
     * {@code counter} is the previous tick's count. Returns the ticks since the last swing.
     */
    private static int countSwing(Snapshot previous, Snapshot current, int counter)
    {
        return previous.seen && current.attackCharge + 0.5F < previous.attackCharge ? 0 : counter + 1;
    }

    private static void fill(Snapshot out, LivingEntity entity)
    {
        out.x = entity.getX();
        out.y = entity.getY();
        out.z = entity.getZ();
        out.vx = entity.getDeltaMovement().x;
        out.vy = entity.getDeltaMovement().y;
        out.vz = entity.getDeltaMovement().z;
        out.yaw = entity.getYRot();
        out.pitch = entity.getXRot();
        out.onGround = entity.onGround();
        out.sprinting = entity.isSprinting();
        out.blocking = entity.isBlocking();
        out.health = entity.getHealth();
        out.absorption = entity.getAbsorptionAmount();
        out.attackCharge = entity instanceof Player player
                ? player.getAttackStrengthScale(0.5F)
                : 0.0F;
        out.fallDistance = (float) entity.fallDistance;
        out.height = entity.getBbHeight();
        out.hurtTime = entity.hurtTime;
        //? if >=26.3 {
        out.invulnerableTime = entity.getInvulnerableTime();
        //?} else {
        /*out.invulnerableTime = entity.invulnerableTime;
        *///?}
        out.armor = entity.getArmorValue();
        out.armorToughness = (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        out.knockbackResistance = entity.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);

        ItemStack weapon = entity instanceof Player player
                ? player.getWeaponItem()
                : entity.getMainHandItem();
        out.weapon = weaponOf(weapon);
        out.weaponDamage = entity.getAttributeValue(Attributes.ATTACK_DAMAGE);
        out.attackSpeed = entity.getAttributeValue(Attributes.ATTACK_SPEED);
        out.seen = true;
    }

    public static Weapon weaponOf(ItemStack stack)
    {
        if (stack.is(ItemTags.SWORDS))
        {
            return Weapon.SWORD;
        }
        if (stack.is(ItemTags.AXES))
        {
            return Weapon.AXE;
        }
        if (stack.is(Items.MACE))
        {
            return Weapon.MACE;
        }
        return stack.isEmpty() ? Weapon.NONE : Weapon.OTHER;
    }

    /** Attack strength scale of a fighter this many ticks past its last swing. */
    public static float chargeOf(int ticksSinceSwing, double attackSpeed)
    {
        return CombatMath.chargeScale(ticksSinceSwing, attackSpeed);
    }
}