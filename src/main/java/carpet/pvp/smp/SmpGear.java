package carpet.pvp.smp;

import carpet.pvp.CombatUtils;
import carpet.pvp.sim.Durability;
import carpet.pvp.sim.SurvivalPolicy;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Optional;

/**
 * What an SMP bot has to spend, read once a tick. A bot can only use what it can put its hand on
 * without a tick of menu work, so the counts the survival model is given are the ones in the nine
 * hotbar slots: a golden apple in the last inventory slot is not a gap the bot could close. A totem
 * and a spare piece of armour are the two exceptions, because both are used by moving them between
 * inventory slots rather than by holding them, and both are counted over the whole main inventory.
 *
 * <p>The scan reads the bot's own inventory, which a player knows as well as the bot does. It holds
 * nothing of the target.</p>
 */
public final class SmpGear
{
    /** Armour positions in the piece order {@link Durability} numbers them. */
    public static final EquipmentSlot[] PIECES = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
    /** Slots a bot can have in its hand, which is what a hotbar change can reach. */
    private static final int HOTBAR = net.minecraft.world.entity.player.Inventory.SELECTION_SIZE;

    public int goldenApples;
    public int enchantedApples;
    public int healingPotions;
    public int healingAmplifier = -1;
    public int strengthPotions;
    public int strengthAmplifier = -1;
    public int strengthDuration;
    public int speedPotions;
    public int speedAmplifier = -1;
    public int speedDuration;
    public int bottles;
    public int pearls;
    public int cobwebs;
    public int totems;
    public boolean waterBucket;
    public boolean shield;

    /** Hotbar slot of the sword the bot returns to when it has nothing in its hands. */
    private int weaponSlot = -1;
    /** Hotbar slot of a spare piece per armour position, -1 when there is none. */
    private final int[] spare = {-1, -1, -1, -1};
    /** Durability left on that spare, which is what the model compares a worn piece against. */
    private final int[] spareRemaining = {0, 0, 0, 0};
    private int appleSlot = -1;
    private int healingSlot = -1;
    private int strengthSlot = -1;
    private int speedSlot = -1;
    private int bottleSlot = -1;
    private int pearlSlot = -1;
    private int webSlot = -1;
    private int bucketSlot = -1;

    /**
     * Reads the bot's own inventory. Called once per tick before anything is decided, so the whole
     * style works from one snapshot and cannot spend a stack it never saw.
     */
    public void scan(ServerPlayer bot)
    {
        net.minecraft.world.entity.player.Inventory inventory = bot.getInventory();
        int main = inventory.getNonEquipmentItems().size();
        goldenApples = 0;
        enchantedApples = 0;
        healingPotions = 0;
        healingAmplifier = -1;
        strengthPotions = 0;
        strengthAmplifier = -1;
        speedPotions = 0;
        speedAmplifier = -1;
        bottles = 0;
        pearls = 0;
        cobwebs = 0;
        totems = 0;
        waterBucket = false;
        weaponSlot = -1;
        appleSlot = -1;
        healingSlot = -1;
        strengthSlot = -1;
        speedSlot = -1;
        bottleSlot = -1;
        pearlSlot = -1;
        webSlot = -1;
        bucketSlot = -1;
        shield = CombatUtils.offhandHolds(bot, Items.SHIELD);
        for (int i = 0; i < PIECES.length; i++)
        {
            spare[i] = -1;
            spareRemaining[i] = 0;
        }

        for (int slot = 0; slot < main; slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            if (slot < HOTBAR)
            {
                if (stack.is(Items.GOLDEN_APPLE))
                {
                    goldenApples += stack.getCount();
                    appleSlot = first(appleSlot, slot);
                }
                else if (stack.is(Items.ENCHANTED_GOLDEN_APPLE))
                {
                    enchantedApples += stack.getCount();
                    appleSlot = first(appleSlot, slot);
                }
                else if (stack.is(Items.SPLASH_POTION) || stack.is(Items.POTION))
                {
                    potion(slot, stack);
                }
                else if (stack.is(Items.ENDER_PEARL))
                {
                    pearls += stack.getCount();
                    pearlSlot = first(pearlSlot, slot);
                }
                else if (stack.is(Items.EXPERIENCE_BOTTLE))
                {
                    bottles += stack.getCount();
                    bottleSlot = first(bottleSlot, slot);
                }
                else if (stack.is(Items.COBWEB))
                {
                    cobwebs += stack.getCount();
                    webSlot = first(webSlot, slot);
                }
                else if (stack.is(Items.WATER_BUCKET))
                {
                    waterBucket = true;
                    bucketSlot = first(bucketSlot, slot);
                }
                else if (stack.is(Items.NETHERITE_SWORD) || stack.is(Items.DIAMOND_SWORD)
                        || stack.is(Items.IRON_SWORD))
                {
                    weaponSlot = first(weaponSlot, slot);
                }
            }
            if (stack.is(Items.TOTEM_OF_UNDYING))
            {
                totems += stack.getCount();
            }
            else
            {
                spare(slot, stack);
            }
        }
    }

    /** Hotbar slot of the sword the bot fights with, or -1 when it carries none. */
    public int weaponSlot()
    {
        return weaponSlot;
    }

    /** Hotbar slot of a spare piece for an armour position, or -1 when there is none. */
    public int spareSlot(int piece)
    {
        return spare[piece];
    }

    /** Durability left on that spare, 0 when there is none. */
    public int spareRemaining(int piece)
    {
        return spareRemaining[piece];
    }

    /**
     * The hotbar slot a move needs, or -1 when the bot does not carry what the move would take. A
     * move without a slot is one the style cannot start at all.
     */
    public int slotFor(SmpPlan.Move move, SurvivalPolicy.Buff buff)
    {
        return switch (move)
        {
            case EAT -> appleSlot;
            case HEAL_THROW -> healingSlot;
            case BUFF_THROW, DRINK_BUFF -> buff == SurvivalPolicy.Buff.SPEED ? speedSlot : strengthSlot;
            case MEND -> bottleSlot;
            case PEARL -> pearlSlot;
            case WEB -> webSlot;
            case BUCKET -> bucketSlot;
            case TOTEM, SWAP_ARMOR, FIGHT -> -1;
        };
    }

    /** How many of the item a move takes the bot is carrying. */
    public int countOf(SmpPlan.Move move, SurvivalPolicy.Buff buff)
    {
        return switch (move)
        {
            case EAT -> goldenApples + enchantedApples;
            case HEAL_THROW -> healingPotions;
            case BUFF_THROW, DRINK_BUFF -> buff == SurvivalPolicy.Buff.SPEED ? speedPotions : strengthPotions;
            case MEND -> bottles;
            case PEARL -> pearls;
            case WEB -> cobwebs;
            case BUCKET -> waterBucket ? 1 : 0;
            case TOTEM, SWAP_ARMOR, FIGHT -> 0;
        };
    }

    private void potion(int slot, ItemStack stack)
    {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null)
        {
            return;
        }
        for (MobEffectInstance effect : contents.getAllEffects())
        {
            Holder<MobEffect> held = effect.getEffect();
            if (is(held, MobEffects.INSTANT_HEALTH))
            {
                healingPotions += stack.getCount();
                if (effect.getAmplifier() > healingAmplifier)
                {
                    healingAmplifier = effect.getAmplifier();
                }
                healingSlot = first(healingSlot, slot);
            }
            else if (is(held, MobEffects.STRENGTH))
            {
                strengthPotions += stack.getCount();
                if (effect.getAmplifier() >= strengthAmplifier)
                {
                    strengthAmplifier = effect.getAmplifier();
                    strengthDuration = effect.getDuration();
                }
                strengthSlot = first(strengthSlot, slot);
            }
            else if (is(held, MobEffects.SPEED))
            {
                speedPotions += stack.getCount();
                if (effect.getAmplifier() >= speedAmplifier)
                {
                    speedAmplifier = effect.getAmplifier();
                    speedDuration = effect.getDuration();
                }
                speedSlot = first(speedSlot, slot);
            }
        }
    }

    /** Keeps the first slot a thing was found in, which is the one a player would reach for first. */
    private static int first(int slot, int found)
    {
        return slot < 0 ? found : slot;
    }

    private void spare(int slot, ItemStack stack)
    {
        int piece = armourSlot(stack);
        if (piece < 0)
        {
            return;
        }
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        // Only the freshest spare of a position is worth anything, so the fullest one is kept.
        if (spare[piece] < 0 || remaining > spareRemaining[piece])
        {
            spare[piece] = slot;
            spareRemaining[piece] = remaining;
        }
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

    /** Armour position a stack would fill, or -1 when it is not a chestplate, leggings, boots or helmet. */
    private static int armourSlot(ItemStack stack)
    {
        if (stack.is(Items.NETHERITE_BOOTS) || stack.is(Items.DIAMOND_BOOTS))
        {
            return Durability.BOOTS;
        }
        if (stack.is(Items.NETHERITE_LEGGINGS) || stack.is(Items.DIAMOND_LEGGINGS))
        {
            return Durability.LEGS;
        }
        if (stack.is(Items.NETHERITE_CHESTPLATE) || stack.is(Items.DIAMOND_CHESTPLATE))
        {
            return Durability.CHEST;
        }
        if (stack.is(Items.NETHERITE_HELMET) || stack.is(Items.DIAMOND_HELMET))
        {
            return Durability.HEAD;
        }
        return -1;
    }
}