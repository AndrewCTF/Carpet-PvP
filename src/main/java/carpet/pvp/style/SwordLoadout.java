package carpet.pvp.style;

import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.Perception;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What the sword style holds in its main hand: the best weapon in the hotbar, a sword in preference to anything
 * else, and an axe in preference to both while the target is blocking behind a shield.
 *
 * <p>A hotbar change takes a tick to come into effect, as for a real client, so the slot is asked for through
 * the body. The choice is only revisited when the target starts or stops blocking, since swapping back and
 * forth every tick would leave the bot holding nothing at all.</p>
 */
final class SwordLoadout
{
    /** Axes, best first, which is the order a shield is broken with. */
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};
    /** Swords, best first, which is what a melee bot holds when it has nothing better to do. */
    private static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
    /** The attack damage a bare hand has, which is what the item modifiers are added to. */
    private static final double BARE_HAND_DAMAGE = 1.0D;

    private final BotBody body;
    private boolean slotsKnown;
    private int axeSlot = -1;
    private int swordSlot = -1;
    private boolean axeInHand;

    SwordLoadout(BotBody body)
    {
        this.body = body;
    }

    /** Picks the weapon for this tick and asks the body to switch to it. */
    void choose(BotPvpConfig cfg, Perception.Snapshot seen, LivingEntity target)
    {
        if (!slotsKnown)
        {
            axeSlot = firstSlot(AXES);
            swordSlot = firstSlot(SWORDS);
            slotsKnown = true;
        }
        axeInHand = cfg.shieldBreak && axeSlot >= 0 && (seen.blocking || target.isBlocking());
        int wanted = pick(cfg);
        if (wanted >= 0 && wanted != body.currentSlot() && wanted != body.pendingSlot())
        {
            body.requestSlot(wanted);
        }
    }

    /** True while the bot is holding an axe because the target is blocking. */
    boolean axeInHand()
    {
        return axeInHand;
    }

    /**
     * The slot the bot should hold: the axe while a shield has to come down, otherwise the best weapon in the
     * hotbar, which with preferSword on is the best sword unless something in the hotbar hits harder.
     */
    private int pick(BotPvpConfig cfg)
    {
        if (axeInHand)
        {
            return axeSlot;
        }
        int weapon = bestWeaponSlot(cfg);
        if (cfg.preferSword)
        {
            int sword = swordSlot >= 0 ? swordSlot : bestSlot(ItemTags.SWORDS);
            if (sword >= 0)
            {
                return sword;
            }
        }
        return weapon;
    }

    /** The hotbar slot of the weapon that hits hardest, which is what autoWeapon turns on. */
    private int bestWeaponSlot(BotPvpConfig cfg)
    {
        if (!cfg.autoWeapon)
        {
            return swordSlot >= 0 ? swordSlot : bestSlot(ItemTags.SWORDS);
        }
        int best = -1;
        double bestDamage = 0.0D;
        for (int slot = 0; slot < 9; slot++)
        {
            ItemStack stack = body.bot().getInventory().getItem(slot);
            if (!isWeapon(stack))
            {
                continue;
            }
            double damage = damageOf(stack);
            if (best < 0 || damage > bestDamage)
            {
                best = slot;
                bestDamage = damage;
            }
        }
        return best;
    }

    /** The attack damage the bot would swing with if this stack were in its main hand. */
    private static double damageOf(ItemStack stack)
    {
        var modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        return modifiers == null ? BARE_HAND_DAMAGE
                : modifiers.compute(Attributes.ATTACK_DAMAGE, BARE_HAND_DAMAGE, EquipmentSlot.MAINHAND);
    }

    private static boolean isWeapon(ItemStack stack)
    {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE);
    }

    /** The slot of the best item of a kind in the hotbar, which is what a sword preference falls back on. */
    private int bestSlot(TagKey<Item> kind)
    {
        int best = -1;
        double bestDamage = 0.0D;
        for (int slot = 0; slot < 9; slot++)
        {
            ItemStack stack = body.bot().getInventory().getItem(slot);
            if (!stack.is(kind))
            {
                continue;
            }
            double damage = damageOf(stack);
            if (best < 0 || damage > bestDamage)
            {
                best = slot;
                bestDamage = damage;
            }
        }
        return best;
    }

    private int firstSlot(Item[] items)
    {
        for (Item item : items)
        {
            int slot = BotBody.findSlot(body.bot(), item);
            if (slot >= 0)
            {
                return slot;
            }
        }
        return -1;
    }
}