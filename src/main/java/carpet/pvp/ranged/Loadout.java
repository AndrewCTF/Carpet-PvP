package carpet.pvp.ranged;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.tags.ItemTags;

/**
 * The weapons a combat bot is carrying, and where they sit in its hotbar.
 *
 * <p>A hotbar is looked at rather than remembered: a bot swaps weapons while it fights and runs out of arrows
 * while it does, so the scan repeats every {@link #SCAN_TICKS} ticks and again the moment the arrows run out.
 * Only the hotbar is searched, because only the hotbar is a click away, which is all any of these techniques
 * need.</p>
 */
public final class Loadout
{
    /** Ticks between two looks at the hotbar. */
    public static final int SCAN_TICKS = 20;

    private final EntityPlayerMPFake bot;
    private int untilScan;

    /** Hotbar slot of a plain bow, or -1. */
    public int bow = -1;
    /** Hotbar slot of a bow with Flame on it, which is what sets a tnt minecart off, or -1. */
    public int flameBow = -1;
    public int crossbow = -1;
    public int sword = -1;
    public int trident = -1;
    public int spear = -1;
    /** Hotbar slot of a tnt minecart and of a rail, or -1. */
    public int cart = -1;
    public int rails = -1;
    /** Arrows in the whole inventory, which is where a bow draws them from. */
    public int arrows;
    /** True while the trident the bot carries has Riptide on it. */
    public boolean riptide;
    /** True while the bot holds a bow that shoots flaming arrows, which is the only way to light a cart. */
    public boolean ignites;

    public Loadout(EntityPlayerMPFake bot)
    {
        this.bot = bot;
    }

    /** Looks at the hotbar again when the last look is stale or the arrows have run out. */
    public void refresh()
    {
        if (untilScan > 0 && arrows > 0)
        {
            return;
        }
        untilScan = SCAN_TICKS;
        bow = -1;
        flameBow = -1;
        crossbow = -1;
        sword = -1;
        trident = -1;
        spear = -1;
        cart = -1;
        rails = -1;
        riptide = false;
        ignites = false;
        arrows = 0;
        Holder<Enchantment> flame = flame(bot.registryAccess());
        Inventory inventory = bot.getInventory();
        for (int slot = 0; slot < 9; slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            if (stack.is(Items.BOW))
            {
                if (bow < 0)
                {
                    bow = slot;
                }
                if (flame != null && EnchantmentHelper.getItemEnchantmentLevel(flame, stack) > 0)
                {
                    flameBow = slot;
                    ignites = true;
                }
            }
            else if (stack.is(Items.CROSSBOW))
            {
                crossbow = slot;
            }
            else if (stack.is(Items.TRIDENT))
            {
                trident = slot;
                riptide = EnchantmentHelper.getTridentSpinAttackStrength(stack, bot) > 0.0F;
            }
            else if (stack.is(Items.TNT_MINECART))
            {
                cart = slot;
            }
            else if (stack.is(Items.RAIL))
            {
                rails = slot;
            }
            else if (sword < 0 && stack.is(ItemTags.SWORDS))
            {
                sword = slot;
            }
            else if (spear < 0 && stack.is(ItemTags.SPEARS))
            {
                spear = slot;
            }
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.ARROW))
            {
                arrows += stack.getCount();
            }
        }
    }

    /** Forgets everything the last scan found, so the next one starts from nothing. */
    public void forget()
    {
        untilScan = 0;
        arrows = 0;
    }

    /** True while the bot has a bow it can shoot and an arrow to shoot. */
    public boolean canShoot()
    {
        return bow >= 0 && arrows > 0;
    }

    /** The stack in the given hotbar slot, or nothing. */
    public ItemStack stack(int slot)
    {
        return slot < 0 ? ItemStack.EMPTY : bot.getInventory().getItem(slot);
    }

    private static Holder<Enchantment> flame(RegistryAccess registries)
    {
        return registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse("minecraft:flame")).orElse(null);
    }
}
