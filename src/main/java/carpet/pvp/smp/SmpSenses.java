package carpet.pvp.smp;

import carpet.pvp.CombatUtils;
import carpet.pvp.Perception;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.Durability;
import carpet.pvp.sim.Effects;
import carpet.pvp.sim.SurvivalPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Fills in the state the survival model scores an SMP bot against, and keeps the short memory of what
 * the bot has just done that the model asks about: the cooldowns of its own items, the tick its last
 * totem popped on, and the fall its water bucket is for.
 *
 * <p>Everything read here is either the bot's own body or the delayed {@link Perception.Snapshot} of
 * its target, which is what the whole style decides from.</p>
 */
public final class SmpSenses
{
    /**
     * Ticks of the horizon the model scores over. It has to be longer than the thirty two ticks a
     * golden apple takes, because the model compares what the bot has left at the end of an action
     * with what it would have had without it, and an action that does not fit inside the window has no
     * end to compare.
     */
    public static final int HORIZON = 40;
    /**
     * Charge cycles between two landed hits. Two fighters trading at reach land about one hit every
     * charge cycle, and a hit that lands resets the other one's knockback, so this is the rate the
     * forecast is meant to be fed.
     */
    public static final int TRADE_CYCLES = 1;
    /** Nearest a target may be for a block still to be placeable at its feet, past entity reach. */
    public static final double WEB_MIN = 3.2D;
    /** Furthest a block can be placed, as the action pack's block reach of 4.5 from the eyes. */
    public static final double WEB_MAX = 4.4D;
    /** Blocks of fall that start to hurt, so water under the feet is worth placing before that. */
    public static final double SAFE_FALL = 3.0D;
    /** Blocks the height of the ground under the bot is looked for over. */
    private static final int GROUND_SCAN = 24;
    /** Ticks before a totem may move into an empty offhand, a player needs a moment to swap. */
    public static final int FIRST_TOTEM_TICKS = 2;
    /** Blocks a player covers a tick going in, which is what sets how long a re-engagement takes. */
    private static final double CLOSING_SPEED = 0.25D;

    private final SurvivalPolicy policy = new SurvivalPolicy();
    private final SurvivalPolicy.Inputs in = new SurvivalPolicy.Inputs();
    private final Effects.Food food = new Effects.Food();
    private final float[] ownProtection = new float[Durability.PIECE_COUNT];

    private int popAge = 1000;
    private int totemWantedFor;
    private int eatingCooldown;
    private int potionCooldown;
    private int armorCooldown;
    private int pearlCooldown;
    private int mendingCooldown;
    private int fallFrom = -1;
    private float fallStartY;
    private boolean totemWasInOffhand;

    /** The inputs of the last {@link #read}, filled in place so one set of them serves a whole fight. */
    public SurvivalPolicy.Inputs inputs()
    {
        return in;
    }

    /** Ticks before the bot may throw or drink another potion, which its own use costs it. */
    public int potionCooldown()
    {
        return potionCooldown;
    }

    /** Ticks since the last totem popped, or a large number when none ever has. */
    public int ticksSinceTotemPop()
    {
        return popAge;
    }

    /** Ticks before a fresh totem may move into the offhand after a pop. */
    public int ticksToRetotem(int delay)
    {
        return Math.max(0, delay - popAge);
    }

    /** Ticks before a totem may move into an offhand that has never held one. */
    public int ticksToFirstTotem()
    {
        return Math.max(0, FIRST_TOTEM_TICKS - totemWantedFor);
    }

    /** Counts the cooldowns down and notices the two things that happen to the bot rather than to its target. */
    public void tick(ServerPlayer bot)
    {
        if (popAge < 2000)
        {
            popAge++;
        }
        eatingCooldown = down(eatingCooldown);
        potionCooldown = down(potionCooldown);
        armorCooldown = down(armorCooldown);
        pearlCooldown = down(pearlCooldown);
        mendingCooldown = down(mendingCooldown);

        boolean inOffhand = CombatUtils.offhandHolds(bot, Items.TOTEM_OF_UNDYING);
        // A totem leaves the offhand in one way only: it popped. The bot is the only thing that moves
        // one, so watching the slot is a clean record of it.
        if (totemWasInOffhand && !inOffhand)
        {
            popAge = 0;
            totemWantedFor = 0;
        }
        totemWasInOffhand = inOffhand;

        if (!bot.onGround() && bot.getDeltaMovement().y < 0.0D)
        {
            if (fallFrom < 0)
            {
                fallFrom = Math.round((float) (bot.getY() - groundHeight(bot)));
                fallStartY = (float) bot.getY();
            }
        }
        else
        {
            fallFrom = -1;
        }
    }

    /** True while the bot is burning, in lava, or falling further than is safe. */
    public boolean waterWanted(ServerPlayer bot)
    {
        if (bot.isOnFire() || bot.isInLava())
        {
            return true;
        }
        return fallFrom >= 0 && fallStartY - bot.getY() < SAFE_FALL
                && groundHeight(bot) < bot.getY() - SAFE_FALL;
    }

    /** Notes that a totem has been wanted in the offhand on this tick. */
    public void wantedTotem()
    {
        totemWantedFor++;
    }

    /** Notes that the bot has just eaten, which holds the food branch of the model off for a moment. */
    public void ate()
    {
        eatingCooldown = Effects.CONSUME_TICKS;
    }

    /** Notes that the bot has just thrown or drunk a potion. */
    public void drank()
    {
        potionCooldown = Effects.CONSUME_TICKS;
    }

    /** Notes that the bot has just thrown an experience bottle. */
    public void mended()
    {
        mendingCooldown = 6;
    }

    /** Notes that the bot has just swapped a piece of armour. */
    public void swappedArmor()
    {
        armorCooldown = SurvivalPolicy.ARMOR_SWAP_TICKS;
    }

    /** Forgets the cooldowns, as after a respawn put a fresh bot in the same body. */
    public void reset()
    {
        popAge = 1000;
        totemWantedFor = 0;
        eatingCooldown = 0;
        potionCooldown = 0;
        armorCooldown = 0;
        pearlCooldown = 0;
        mendingCooldown = 0;
        fallFrom = -1;
        totemWasInOffhand = false;
    }

    /**
     * Fills the model inputs from the bot's own body and the delayed snapshot of its target.
     *
     * @param seen     the target as the bot believes it looked
     * @param gear     what the bot has in its hotbar this tick
     * @param buffs    the buff the model is to be asked about
     * @param retotem  ticks the bot waits after a pop before it moves a fresh totem in
     * @param reaction ticks between the bot seeing the target and acting on it
     * @param peelBack the distance in blocks a retreat throw aims to buy
     */
    public SurvivalPolicy.Inputs read(ServerPlayer bot, Perception.Snapshot seen, SmpGear gear,
            SmpPlan.Buffs buffs, int retotem, int reaction, double peelBack)
    {
        in.health = bot.getHealth();
        in.maxHealth = bot.getMaxHealth();
        in.absorption = bot.getAbsorptionAmount();
        effects(bot);
        in.attackSpeed = bot.getAttributeValue(Attributes.ATTACK_SPEED);
        float chargeTicks = Math.max(1.0F, CombatMath.fullChargeTicks(in.attackSpeed));
        in.exhaustionPerTick = Effects.EXHAUSTION_ATTACK / chargeTicks;
        in.outgoingDamagePerTick = (float) (bot.getAttributeValue(Attributes.ATTACK_DAMAGE) / chargeTicks);

        in.armorValue = bot.getArmorValue();
        in.armorToughness = (float) bot.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        armour(bot, gear);
        in.epf = ownProtection[0] + ownProtection[1] + ownProtection[2] + ownProtection[3];
        in.breachLevel = 0;
        in.resistanceAmplifier = resistance();

        in.distance = Math.hypot(seen.x - bot.getX(), seen.z - bot.getZ());
        in.enemyHealth = seen.healthTotal();
        in.incomingHitDamage = (float) Math.max(1.0, seen.weaponDamage);
        in.incomingDamagePerTick = policy.hitLoss(in, in.incomingHitDamage) / (TRADE_CYCLES * chargeTicks);
        // The model reads beingHit as "the opponent is in reach and the bot can trade instead of
        // closing the distance again", which is the distance and nothing else: a swing about to land
        // changes when the hit lands, not what the bot has to choose between.
        in.beingHit = in.distance <= DuelSim.REACH;
        in.reactionTicks = reaction;
        in.travelTicks = Math.max(1, (int) Math.ceil(in.distance / CLOSING_SPEED));
        // What a pearl buys is the flight plus the walk the target still has to make back over the
        // ground the pearl covered, which is what the model's retreat branch weighs.
        in.breakTicks = SurvivalPolicy.PEARL_TRAVEL_TICKS
                + Math.max(1, (int) Math.ceil(peelBack / CLOSING_SPEED));

        in.horizonTicks = HORIZON;
        in.goldenApples = gear.goldenApples;
        in.enchantedGoldenApples = gear.enchantedApples;
        in.healingPotions = gear.healingPotions;
        in.healingPotionAmplifier = Math.max(0, gear.healingAmplifier);
        in.buff = buffs.effect();
        in.buffPotions = buffs.count();
        in.buffPotionAmplifier = Math.max(0, buffs.amplifier());
        in.buffPotionTicks = buffs.duration() > 0 ? buffs.duration() : Effects.POTION_STRENGTH_TICKS;
        in.experienceBottles = gear.bottles;
        in.pearls = gear.pearls;
        boolean offhandTotem = CombatUtils.offhandHolds(bot, Items.TOTEM_OF_UNDYING);
        in.totems = gear.totems + (offhandTotem ? 1 : 0);
        in.totemInOffhand = offhandTotem;
        in.ticksSinceTotemPop = popAge;
        in.retotemDelayTicks = retotem;

        in.eatingCooldown = eatingCooldown;
        in.potionCooldown = potionCooldown;
        in.armorCooldown = armorCooldown;
        in.pearlCooldown = pearlCooldown;
        in.mendingCooldown = mendingCooldown;
        return in;
    }

    /**
     * The buff the model should be asked about: the one that runs out first, so that its scoring of a
     * potion is the scoring of the potion the bot would actually throw.
     */
    public SmpPlan.Buffs buffs(SmpGear gear, int strengthTicks, int speedTicks, int window)
    {
        SmpPlan.Buffs strength = new SmpPlan.Buffs(SurvivalPolicy.Buff.STRENGTH, strengthTicks, window,
                gear.strengthPotions, gear.strengthAmplifier, gear.strengthDuration, true);
        SmpPlan.Buffs speed = new SmpPlan.Buffs(SurvivalPolicy.Buff.SPEED, speedTicks, window,
                gear.speedPotions, gear.speedAmplifier, gear.speedDuration, true);
        return strengthTicks <= speedTicks ? strength : speed;
    }

    /**
     * The armour position the model wants swapped: the one worn closest to breaking that has a spare,
     * and only while that spare is worth more than what is left on the piece.
     *
     * @return the piece index, or -1 while there is nothing to swap
     */
    public int swapPiece(SmpGear gear)
    {
        int worst = -1;
        int remaining = Integer.MAX_VALUE;
        for (int piece = 0; piece < SmpGear.PIECES.length; piece++)
        {
            if (gear.spareSlot(piece) < 0)
            {
                continue;
            }
            int left = in.armorRemaining(piece);
            if (left < remaining)
            {
                remaining = left;
                worst = piece;
            }
        }
        return remaining < SurvivalPolicy.SWAP_ARMOR_THRESHOLD ? worst : -1;
    }

    private void effects(ServerPlayer bot)
    {
        Collection<MobEffectInstance> active = bot.getActiveEffects();
        int at = 0;
        for (MobEffectInstance instance : active)
        {
            Effects.Effect effect = of(instance.getEffect());
            if (effect == null)
            {
                continue;
            }
            if (at == in.effects.length)
            {
                // Never shrink to nothing: the next tick's first effect has to have somewhere to go.
                in.effects = Arrays.copyOf(in.effects, Math.max(4, in.effects.length * 2));
            }
            in.effects[at++] = new Effects.Instance(effect, instance.getAmplifier(), instance.getDuration());
        }
        if (at != in.effects.length)
        {
            in.effects = Arrays.copyOf(in.effects, at);
        }
        food.food = bot.getFoodData().getFoodLevel();
        food.saturation = bot.getFoodData().getSaturationLevel();
    }

    private void armour(ServerPlayer bot, SmpGear gear)
    {
        int unbreaking = 0;
        in.armorMultiplier = Durability.NETHERITE_MULTIPLIER;
        for (int piece = 0; piece < SmpGear.PIECES.length; piece++)
        {
            ItemStack worn = bot.getItemBySlot(SmpGear.PIECES[piece]);
            in.armorDamage[piece] = worn.getDamageValue();
            ownProtection[piece] = Math.min(20.0F, level(worn, Enchantments.PROTECTION));
            in.mending[piece] = level(worn, Enchantments.MENDING);
            unbreaking = Math.max(unbreaking, level(worn, Enchantments.UNBREAKING));
            in.spareArmor[piece] = gear.spareSlot(piece) < 0 ? -1 : gear.spareRemaining(piece);
            if (!worn.isEmpty() && worn.getMaxDamage() > 0)
            {
                in.armorMultiplier = multiplierOf(worn);
            }
        }
        in.unbreakingLevel = unbreaking;
    }

    private int resistance()
    {
        for (Effects.Instance effect : in.effects)
        {
            if (effect.effect == Effects.Effect.RESISTANCE)
            {
                return effect.amplifier;
            }
        }
        return -1;
    }

    /** Height of the first solid block under the bot's feet, or its own height when there is none. */
    private static double groundHeight(ServerPlayer bot)
    {
        BlockPos pos = bot.blockPosition();
        for (int down = 0; down < GROUND_SCAN; down++)
        {
            BlockPos under = pos.below(down);
            if (!bot.level().getBlockState(under).isAir())
            {
                return under.getY() + 1.0D;
            }
        }
        return bot.getY();
    }

    /** Which effect of the model a running status effect is, or null for one the model does not use. */
    private static Effects.Effect of(Holder<MobEffect> effect)
    {
        if (is(effect, MobEffects.SPEED))
        {
            return Effects.Effect.SPEED;
        }
        if (is(effect, MobEffects.SLOWNESS))
        {
            return Effects.Effect.SLOWNESS;
        }
        if (is(effect, MobEffects.STRENGTH))
        {
            return Effects.Effect.STRENGTH;
        }
        if (is(effect, MobEffects.WEAKNESS))
        {
            return Effects.Effect.WEAKNESS;
        }
        if (is(effect, MobEffects.REGENERATION))
        {
            return Effects.Effect.REGENERATION;
        }
        if (is(effect, MobEffects.RESISTANCE))
        {
            return Effects.Effect.RESISTANCE;
        }
        if (is(effect, MobEffects.FIRE_RESISTANCE))
        {
            return Effects.Effect.FIRE_RESISTANCE;
        }
        if (is(effect, MobEffects.ABSORPTION))
        {
            return Effects.Effect.ABSORPTION;
        }
        if (is(effect, MobEffects.HEALTH_BOOST))
        {
            return Effects.Effect.HEALTH_BOOST;
        }
        return null;
    }

    private static boolean is(Holder<MobEffect> effect, Holder<MobEffect> wanted)
    {
        if (effect == wanted)
        {
            return true;
        }
        Optional<ResourceKey<MobEffect>> key = effect.unwrapKey();
        return key.isPresent() && key.equals(wanted.unwrapKey());
    }

    private static int multiplierOf(ItemStack worn)
    {
        boolean netherite = worn.is(Items.NETHERITE_HELMET) || worn.is(Items.NETHERITE_CHESTPLATE)
                || worn.is(Items.NETHERITE_LEGGINGS) || worn.is(Items.NETHERITE_BOOTS);
        return netherite ? Durability.NETHERITE_MULTIPLIER : Durability.DIAMOND_MULTIPLIER;
    }

    private static int level(ItemStack stack, ResourceKey<Enchantment> key)
    {
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (Holder<Enchantment> holder : enchants.keySet())
        {
            if (holder.is(key))
            {
                return enchants.getLevel(holder);
            }
        }
        return 0;
    }

    private static int down(int counter)
    {
        return counter > 0 ? counter - 1 : 0;
    }
}