package carpet.pvp.mace;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * What a mace fighter has in its hotbar: the two maces of the kit, the axe it breaks shields with and
 * the items it gets into the air with. Every slot is found by reading the inventory rather than assumed,
 * so a bot carrying a different kit simply finds less of it. The sword it falls back on is left to the
 * sword style, and the elytra only counts worn, since a glide needs it on the chest.
 */
public final class MaceGear
{
    private static final int HOTBAR = 9;
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE,
            Items.GOLDEN_AXE, Items.WOODEN_AXE};

    private final ServerPlayer bot;
    private final Holder<Enchantment> density;
    private final Holder<Enchantment> breach;
    private final Holder<Enchantment> windBurst;

    private int densityMace = -1;
    private int breachMace = -1;
    private int plainMace = -1;
    private int axe = -1;
    private int charge = -1;
    private int pearl = -1;
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
        refresh();
    }

    /** Reads the hotbar again, for the bots whose kit is handed out after they started fighting. */
    public final void refresh()
    {
        densityMace = -1;
        breachMace = -1;
        plainMace = -1;
        axe = -1;
        charge = -1;
        pearl = -1;
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
                burstLevel = Math.max(burstLevel, level(stack, windBurst));
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
            else if (charge < 0 && stack.is(Items.WIND_CHARGE))
            {
                charge = slot;
            }
            else if (pearl < 0 && stack.is(Items.ENDER_PEARL))
            {
                pearl = slot;
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
     * True while the wings are worn on the chest. A glide is a chest-slot thing, not a hotbar one, so a
     * bot that only has the elytra in its hotbar cannot dive with it.
     */
    public boolean wearingElytra()
    {
        return bot.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
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

    /** True while the bot can throw its wind charges: the stack is there, has one left and is ready. */
    public boolean chargeReady()
    {
        return charge >= 0 && charges() > 0 && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(charge));
    }

    /** True while the bot can throw a pearl. */
    public boolean pearlReady()
    {
        return pearl >= 0 && pearls() > 0 && !bot.getCooldowns().isOnCooldown(bot.getInventory().getItem(pearl));
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
        return enchantment == null ? 0 : stack.getEnchantments().getLevel(enchantment);
    }

    private static Holder<Enchantment> holder(RegistryAccess registries, String name)
    {
        return registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse("minecraft:" + name)).orElse(null);
    }
}
