package carpet.pvp.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/** The items the buttons of a menu are made of: a name and the lore lines under it. */
final class MenuItems
{
    /** Marks the choice that is made on a menu that offers several of them. */
    static final String CHOSEN = BotOptionLayout.CHONEN;

    /** What the kit editor marks its empty slots with. Never an item of the kit being edited. */
    private static final Item MARKER = Blocks.STAINED_GLASS_PANE.gray().asItem();

    private MenuItems() {}

    static MutableComponent text(String text, ChatFormatting colour)
    {
        return Component.literal(text).withStyle(colour);
    }

    static ItemStack item(Item item, MutableComponent name, Component... lore)
    {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name);
        if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
        return stack;
    }

    /** A button: one item, a name in the given colour and up to three lore lines. */
    static ItemStack button(Item item, ChatFormatting colour, String name, String... lore)
    {
        Component[] lines = new Component[lore.length];
        for (int i = 0; i < lore.length; i++) lines[i] = text(lore[i], ChatFormatting.GRAY);
        return item(item, text(name, colour), lines);
    }

    /** A button that stands for a switch being on. */
    static ItemStack on(String name, String... lore)
    {
        return button(Blocks.STAINED_GLASS_PANE.lime().asItem(), ChatFormatting.GREEN, name, lore);
    }

    /** A button that stands for a switch being off. */
    static ItemStack off(String name, String... lore)
    {
        return button(Blocks.STAINED_GLASS_PANE.red().asItem(), ChatFormatting.RED, name, lore);
    }

    /** The empty pane the kit editor marks its empty slots with. */
    static ItemStack marker(String name)
    {
        return button(MARKER, ChatFormatting.DARK_GRAY, name);
    }

    /** True while a stack is one of those markers rather than anything of a kit. */
    static boolean isMarker(ItemStack stack)
    {
        return !stack.isEmpty() && stack.getItem() == MARKER;
    }

    /** A bot's own head, so a row of bots reads as a row of players. */
    static ItemStack head(ServerPlayer bot, MutableComponent name, Component... lore)
    {
        ItemStack stack = item(Items.PLAYER_HEAD, name, lore);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(bot.getGameProfile()));
        return stack;
    }
}