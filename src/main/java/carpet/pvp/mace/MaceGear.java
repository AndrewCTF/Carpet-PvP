package carpet.pvp.mace;

import carpet.pvp.sim.CombatMath;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * What a mace fighter has in its hotbar: the maces of the kit, the axe it breaks shields with, the item it
 * charges a launch under and the items it gets into the air with. Every slot is found by reading the inventory
 * rather than assumed, so a bot carrying a different kit simply finds less of it.
 *
 * <p>Two of these are read from the body rather than the bag. The elytra only counts worn, since a glide needs
 * it on the chest, and the chest piece it is exchanged with is what puts the wings back on, so both are asked
 * of the equipment slot every time.</p>
 */
public final class MaceGear
{
    private static final int HOTBAR = 9;
    /** The attack damage a player has with nothing in its hand, which an item's modifiers are added to. */
    private static final double BARE_HAND_DAMAGE = 1.0D;
    /** Attributes.ATTACK_SPEED of a player with nothing in its hand, which an item's modifiers take from. */
    private static final double BARE_HAND_SPEED = 4.0D;
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE,
            Items.GOLDEN_AXE, Items.WOODEN_AXE};

    private final ServerPlayer bot;
    private final Holder<Enchantment> density;
    private final Holder<Enchantment> breach;
    private final Holder<Enchantment> windBurst;
    private final Holder<Enchantment> protection;

    private int densityMace = -1;
    private int breachMace = -1;
    private int plainMace = -1;
    private int burstMace = -1;
    private int axe = -1;
    private int sword = -1;
    private int charge = -1;
    private int pearl = -1;
    private int elytra = -1;
    private int rocket = -1;
    private int densityLevel;
    private int breachLevel;
    private int burstLevel;

    public MaceGear(ServerPlayer bot)
    {
        this.bot = bot;
        RegistryAccess registries = bot.level().registryAccess();
        density = holder(registries, "density");
        breach = holder(registries, "breach");
        windBurst = holder(registries, "wind_burst");
        protection = holder(registries, "protection");
        refresh();
    }

    /** Reads the hotbar again, for the bots whose kit is handed out after they started fighting. */
    public final void refresh()
    {
        densityMace = -1;
        breachMace = -1;
        plainMace = -1;
        burstMace = -1;
        axe = -1;
        sword = -1;
        charge = -1;
        pearl = -1;
        elytra = -1;
        rocket = -1;
        densityLevel = 0;
        breachLevel = 0;
        burstLevel = 0;
        for (int slot = 0; slot < HOTBAR; slot++)
        {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            if (stack.is(Items.MACE))
            {
                int dense = level(stack, density);
                int breachy = level(stack, breach);
                int burst = level(stack, windBurst);
                if (burst > burstLevel)
                {
                    burstMace = slot;
                    burstLevel = burst;
                }
                if (dense > 0)
                {
                    densityMace = slot;
                    densityLevel = dense;
                }
                else if (breachy > 0)
                {
                    breachMace = slot;
                    breachLevel = breachy;
                }
                else if (plainMace < 0)
                {
                    plainMace = slot;
                }
            }
            else if (axe < 0 && isAxe(stack))
            {
                axe = slot;
            }
            else if (sword < 0 && stack.is(net.minecraft.tags.ItemTags.SWORDS))
            {
                sword = slot;
            }
            else if (charge < 0 && stack.is(Items.WIND_CHARGE))
            {
                charge = slot;
            }
            else if (pearl < 0 && stack.is(Items.ENDER_PEARL))
            {
                pearl = slot;
            }
            else if (elytra < 0 && stack.is(Items.ELYTRA))
            {
                elytra = slot;
            }
            else if (rocket < 0 && stack.is(Items.FIREWORK_ROCKET))
            {
                rocket = slot;
            }
        }
    }

    /** True while the bot carries a mace at all. */
    public boolean hasMace()
    {
        return densityMace >= 0 || breachMace >= 0 || plainMace >= 0;
    }

    /** Density level of the density mace, 0 when the kit has none. */
    public int densityLevel()
    {
        return densityLevel;
    }

    /** Breach level of the breach mace, 0 when the kit has none. */
    public int breachLevel()
    {
        return breachLevel;
    }

    /** Wind Burst level of the best mace in the hotbar, 0 when none of them carries it. */
    public int burstLevel()
    {
        return burstLevel;
    }

    /** Slot of the mace that carries Wind Burst, or -1 when none of them does. */
    public int burstSlot()
    {
        return burstMace;
    }

    /** Slot of the Breach mace, or -1 when the kit has none. */
    public int breachSlot()
    {
        return breachMace;
    }

    /** Slot of the axe that breaks a raised shield, or -1. */
    public int axeSlot()
    {
        return axe;
    }

    /** True while the bot carries an axe it could charge a launch with instead of the mace. */
    public boolean hasAxe()
    {
        return axe >= 0;
    }

    /**
     * The slot of the item whose cooldown a launch or a ground hit is collected under, which is what the mace is
     * swapped in on top of. A sword recharges in thirteen ticks against a mace's thirty four and its base damage
     * is higher, so it is the one worth charging under; an axe is the fallback for a kit without a sword.
     */
    public int chargerSlot()
    {
        if (sword >= 0)
        {
            return sword;
        }
        return axe;
    }

    /**
     * True while the wings are worn on the chest. A glide is a chest-slot thing, not a hotbar one, so a bot
     * that only has the elytra in its hotbar cannot dive with it until it has used it once.
     */
    public boolean wearingElytra()
    {
        return bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    /**
     * True while the chest holds anything the bot can hand back to take the wings off again. An elytra on the
     * chest is the wings themselves, and using those from the hand would put them straight back on.
     */
    public boolean wearingChestpiece()
    {
        ItemStack chest = bot.getItemBySlot(EquipmentSlot.CHEST);
        return !chest.isEmpty() && !chest.is(Items.ELYTRA) && swappable(chest);
    }

    /** Slot of the elytra in the hotbar, or -1 once it has been put on. */
    public int elytraSlot()
    {
        return elytra;
    }

    /**
     * Slot of the chest piece the wings are exchanged with, which is where the elytra's own slot ends up once
     * it is worn: using the elytra out of the hotbar puts it on the chest and hands the chest piece back, and
     * using that again takes the wings off.
     */
    public int chestpieceSlot()
    {
        for (int slot = 0; slot < HOTBAR; slot++)
        {
            ItemStack stack = bot.getInventory().getItem(slot);
            if (swappable(stack) && !stack.is(Items.ELYTRA))
            {
                return slot;
            }
        }
        return -1;
    }

    /** Slot of the firework rockets, or -1. */
    public int rocketSlot()
    {
        return rocket;
    }

    /** How many rockets are left in the hotbar. */
    public int rockets()
    {
        return rocket < 0 ? 0 : bot.getInventory().getItem(rocket).getCount();
    }

    /** Slot of the wind charges, or -1. */
    public int chargeSlot()
    {
        return charge;
    }

    /** Slot of the ender pearls, or -1. */
    public int pearlSlot()
    {
        return pearl;
    }

    /** How many wind charges are left in the hotbar. */
    public int charges()
    {
        return charge < 0 ? 0 : bot.getInventory().getItem(charge).getCount();
    }

    /** How many pearls are left in the hotbar. */
    public int pearls()
    {
        return pearl < 0 ? 0 : bot.getInventory().getItem(pearl).getCount();
    }

    /**
     * The slot of the mace to swing against the given armour: Density while the fall is long enough to
     * feed it, Breach where the armour is heavy enough that taking a slice off it is worth more.
     */
    public int maceSlot(double fallDistance, float armor, float toughness, float epf, boolean pick)
    {
        if (densityMace < 0)
        {
            return breachMace >= 0 ? breachMace : plainMace;
        }
        if (!pick || breachMace < 0)
        {
            return densityMace;
        }
        return MaceChoice.preferBreach(fallDistance, densityLevel, breachLevel, armor, toughness, epf)
                ? breachMace : densityMace;
    }

    /** When the cooldown of a launch or a ground hit is out of the way. */
    public boolean chargeReady()
    {
        return charge >= 0 && charges() > 0 && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(charge));
    }

    /** True while the bot can throw a pearl. */
    public boolean pearlReady()
    {
        return pearl >= 0 && pearls() > 0 && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl));
    }

    /** True while the bot can light a rocket: FireworkRocketItem.use only fires one with the wings open. */
    public boolean rocketReady()
    {
        return rocket >= 0 && rockets() > 0 && bot.isFallFlying()
                && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(rocket));
    }

    /** True when the cooldown of a launch is collected under the axe, which hits harder but recharges slower. */
    public boolean chargerIsAxe()
    {
        return chargerSlot() == axe;
    }

    /**
     * What the Protection on everything a fighter wears takes off a hit, as the game's protection factor. The
     * armour value says nothing about it, and against a full set of Protection IV it is nearly two thirds of
     * every hit, which is the difference between a ground swing that is worth throwing and one that is not.
     */
    public float protection(LivingEntity fighter)
    {
        int levels = 0;
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            levels += level(fighter.getItemBySlot(slot), protection);
        }
        return CombatMath.epf(levels, 0, false);
    }

    /** The base damage a swing charged in this slot carries, which is the item's own attribute. */
    public double damageOf(int slot)
    {
        return attribute(slot, Attributes.ATTACK_DAMAGE, BARE_HAND_DAMAGE);
    }

    /** The attack speed a swing charged in this slot is collected under, which is the item's own attribute. */
    public double speedOf(int slot)
    {
        return attribute(slot, Attributes.ATTACK_SPEED, BARE_HAND_SPEED);
    }

    /**
     * The base damage of the item the hand changes off on the tick of a hit, which is what a smash or a ground
     * swap is charged against. Read off the stack rather than assumed, so a kit that hands out something other
     * than a netherite sword is priced at what it actually swings with.
     */
    public double chargerDamage()
    {
        return damageOf(chargerSlot());
    }

    /** The attack speed of the item a swap smash's or a ground hit's cooldown is collected under. */
    public double chargerSpeed()
    {
        return speedOf(chargerSlot());
    }

    /** The base damage of the axe that takes a raised shield down. */
    public double axeDamage()
    {
        return damageOf(axe);
    }

    /** The attack speed of the axe that takes a raised shield down. */
    public double axeSpeed()
    {
        return speedOf(axe);
    }

    private double attribute(int slot, Holder<Attribute> attribute, double base)
    {
        if (slot < 0)
        {
            return base;
        }
        ItemAttributeModifiers modifiers = bot.getInventory().getItem(slot).get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? base : modifiers.compute(attribute, base, EquipmentSlot.MAINHAND);
    }

    private static boolean swappable(ItemStack stack)
    {
        return stack.get(DataComponents.EQUIPPABLE) != null;
    }

    private static boolean isAxe(ItemStack stack)
    {
        for (Item item : AXES)
        {
            if (stack.is(item))
            {
                return true;
            }
        }
        return false;
    }

    private static int level(ItemStack stack, Holder<Enchantment> enchantment)
    {
        return enchantment == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack);
    }

    private static Holder<Enchantment> holder(RegistryAccess registries, String name)
    {
        return registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse("minecraft:" + name)).orElse(null);
    }
}
