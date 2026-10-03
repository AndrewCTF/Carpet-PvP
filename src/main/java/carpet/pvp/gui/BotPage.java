package carpet.pvp.gui;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.FactionManager;
import carpet.pvp.gui.BotOptionLayout.Entry;
import carpet.pvp.gui.BotOptionLayout.Option;
import carpet.pvp.gui.BotOptionLayout.Role;
import carpet.pvp.kit.KitStore;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The page of one bot: what it fights with and every setting it has, and the four things a player
 * wants to do to it.
 *
 * <p>Nothing is listed here by hand: the settings come from {@link BotOptionLayout}, which reads them
 * off {@link BotPvpConfig}, and the options of the styles come from
 * {@link carpet.pvp.style.StyleIndex#options()}. Every change goes through
 * {@link BotGui#apply(EntityPlayerMPFake, String, String)}, the same call {@code /bot option} makes.</p>
 */
final class BotPage extends BotMenu
{
    private static final int ROWS = 6;

    /** The head, the style, the five difficulties and the kit. */
    private static final int HEAD = 0;
    private static final int STYLE = 1;
    private static final int FIRST_DIFFICULTY = 2;
    private static final int KIT = 7;
    private static final int REMOVE = 8;
    /** Duel, spectate, faction, and the two arrows that walk through the pages of settings. */
    private static final int DUEL = 9;
    private static final int SPECTATE = 10;
    private static final int FACTION = 11;
    private static final int PREVIOUS = 16;
    private static final int NEXT = 17;

    private static final Item[] DIFFICULTY_ICONS = {
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD };

    private final ServerPlayer viewer;
    private final String name;
    private int page;

    private BotPage(int containerId, Inventory inventory, ServerPlayer viewer, String name)
    {
        super(containerId, inventory, viewer, ROWS);
        this.viewer = viewer;
        this.name = name;
        button(HEAD, () -> openMain());
        button(STYLE, () -> cycleStyle());
        for (int i = 0; i < BotPvpConfig.Difficulty.values().length; i++)
        {
            int index = i;
            button(FIRST_DIFFICULTY + i, () -> setDifficulty(index));
        }
        button(KIT, () -> cycleKit());
        button(REMOVE, () -> remove());
        button(DUEL, () -> duel());
        button(SPECTATE, () -> spectate());
        button(FACTION, () -> cycleFaction());
        button(PREVIOUS, () -> turn(-1));
        button(NEXT, () -> turn(1));
        for (int slot = BotOptionLayout.FIRST_SLOT; slot < grid(); slot++)
        {
            int index = slot;
            button(slot, () -> press(index));
        }
        repaint();
    }

    static void open(ServerPlayer viewer, String name)
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null)
        {
            BotGui.report(viewer, name + " is not a bot of this server.");
            return;
        }
        viewer.openMenu(new SimpleMenuProvider((containerId, inventory, player) ->
                new BotPage(containerId, inventory, (ServerPlayer) player, name),
                Component.literal("Bot " + name)));
    }

    // ===== the four things a player wants to do =====

    private void openMain()
    {
        MainMenu.open(viewer);
    }

    private void cycleStyle()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        BotPvpConfig.CombatStyle next = BotGui.nextStyle(bot.getPvpConfig().combatStyle);
        BotGui.report(viewer, BotGui.apply(bot, "combatstyle", BotGui.styleName(next)));
    }

    private void setDifficulty(int index)
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        BotPvpConfig.Difficulty[] difficulties = BotPvpConfig.Difficulty.values();
        if (index < difficulties.length)
        {
            BotGui.report(viewer, BotGui.apply(bot, "difficulty", difficulties[index].name().toLowerCase(Locale.ROOT)));
        }
    }

    private void cycleKit()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        List<String> kits = KitStore.of(server()).names();
        if (kits.isEmpty()) return;
        String current = BotGui.kitOf(bot);
        int next = kits.indexOf(current) + 1;
        String kit = kits.get(next >= kits.size() ? 0 : next);
        if (BotGui.giveKit(bot, kit)) viewer.sendSystemMessage(
                MenuItems.text(name + " now wears the " + kit + " kit.", ChatFormatting.GREEN));
        else BotGui.report(viewer, "There is no kit called " + kit);
    }

    private void remove()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        String removed = bot.getName().getString();
        bot.kill(Component.literal("Removed from the bot menu"));
        viewer.sendSystemMessage(MenuItems.text("Removed " + removed + ".", ChatFormatting.YELLOW));
        openMain();
    }

    private void duel()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        BotGui.duelMe(bot, viewer);
        viewer.sendSystemMessage(MenuItems.text(name + " is fighting you.", ChatFormatting.YELLOW));
        MainMenu.open(viewer);
    }

    private void spectate()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        viewer.setCamera(bot);
        viewer.sendSystemMessage(MenuItems.text("Now watching " + name + ". Press F5 to stop.", ChatFormatting.YELLOW));
    }

    private void cycleFaction()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        List<String> factions = new ArrayList<>(new TreeSet<>(FactionManager.allFactions()));
        String current = bot.getPvpConfig().faction;
        int next = current == null ? 0 : factions.indexOf(current) + 1;
        String wanted = next < factions.size() ? factions.get(next) : null;
        BotGui.report(viewer, BotGui.apply(bot, "faction", wanted == null ? "none" : wanted));
    }

    private void turn(int by)
    {
        page = Math.max(0, Math.min(BotOptionLayout.pageCount() - 1, page + by));
    }

    /** What a click on one of the settings does: nothing but read it. */
    private void press(int slot)
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return;
        for (Entry entry : BotOptionLayout.page(page))
        {
            if (entry.slot() != slot) continue;
            change(bot, entry);
            return;
        }
    }

    private void change(EntityPlayerMPFake bot, Entry entry)
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        Option option = entry.option();
        switch (entry.role())
        {
            case MINUS -> step(bot, option, -option.step());
            case PLUS -> step(bot, option, option.step());
            case VALUE -> {
                if (option.kind() == BotOptionLayout.Kind.TOGGLE)
                {
                    BotGui.report(viewer, BotGui.apply(bot, option.key(),
                            String.valueOf(!BotOptionLayout.flag(cfg, option.key()))));
                }
            }
        }
    }

    private void step(EntityPlayerMPFake bot, Option option, double by)
    {
        double wanted = BotOptionLayout.number(bot.getPvpConfig(), option.key()) + by;
        // A setting that has reached the end of what it allows simply does not move any further.
        BotGui.report(viewer, BotGui.apply(bot, option.key(), BotOptionLayout.format(wanted)));
    }

    // ===== what is on the screen =====

    @Override
    protected void paint()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null)
        {
            // The bot has gone; the main menu says so rather than leaving an empty page behind.
            openMain();
            return;
        }
        BotPvpConfig cfg = bot.getPvpConfig();
        for (int slot = 0; slot < BotOptionLayout.FIRST_SLOT; slot++) clear(slot);

        put(HEAD, head(bot, cfg));
        put(STYLE, style(cfg));
        for (int i = 0; i < BotPvpConfig.Difficulty.values().length; i++)
        {
            put(FIRST_DIFFICULTY + i, difficulty(cfg, i));
        }
        put(KIT, kit(bot));
        put(REMOVE, MenuItems.button(Items.BARRIER, ChatFormatting.RED, "Remove this bot",
                "The bot leaves the server.", "Its kit and settings are gone with it."));
        put(DUEL, MenuItems.button(Items.IRON_SWORD, ChatFormatting.RED, "Duel me",
                bot.isAlive() ? "Makes " + name + " fight you." : name + " is dead."));
        put(SPECTATE, MenuItems.button(Items.ENDER_EYE, ChatFormatting.AQUA, "Spectate",
                "Watch through its eyes.", "Press F5 again to stop."));
        put(FACTION, faction(cfg));
        if (page > 0) put(PREVIOUS, MenuItems.button(Items.ARROW, ChatFormatting.YELLOW, "Previous page"));
        if (page + 1 < BotOptionLayout.pageCount()) put(NEXT, MenuItems.button(Items.ARROW, ChatFormatting.YELLOW, "Next page"));

        for (int slot = BotOptionLayout.FIRST_SLOT; slot < grid(); slot++) clear(slot);
        for (Entry entry : BotOptionLayout.page(page))
        {
            put(entry.slot(), item(cfg, entry));
        }
    }

    private ItemStack head(EntityPlayerMPFake bot, BotPvpConfig cfg)
    {
        return MenuItems.head(bot,
                MenuItems.text(bot.getName().getString(), ChatFormatting.WHITE),
                MenuItems.text(String.format(Locale.ROOT, "health %.1f / %.1f", bot.getHealth(), bot.getMaxHealth()),
                        ChatFormatting.GRAY),
                MenuItems.text(cfg.combat ? "fighting" : "idle", cfg.combat ? ChatFormatting.GREEN : ChatFormatting.GRAY),
                MenuItems.text("click a setting below to change it", ChatFormatting.DARK_GRAY));
    }

    private ItemStack style(BotPvpConfig cfg)
    {
        return MenuItems.button(Items.DIAMOND_SWORD, ChatFormatting.YELLOW, "Style: " + BotGui.styleName(cfg.combatStyle),
                cfg.combatStyle == BotPvpConfig.CombatStyle.MELEE
                        ? "Clicks and sword swings."
                        : "How " + name + " fights. Click to go to the next style.");
    }

    private ItemStack difficulty(BotPvpConfig cfg, int index)
    {
        BotPvpConfig.Difficulty[] difficulties = BotPvpConfig.Difficulty.values();
        BotPvpConfig.Difficulty difficulty = difficulties[index];
        boolean chosen = difficulty == cfg.difficulty;
        String label = difficulty.name().toLowerCase(Locale.ROOT);
        return MenuItems.button(DIFFICULTY_ICONS[Math.min(index, DIFFICULTY_ICONS.length - 1)],
                chosen ? ChatFormatting.GREEN : ChatFormatting.GRAY,
                (chosen ? MenuItems.CHOSEN : "") + "Difficulty: " + label,
                chosen ? "What it plays at." : "Click to play at " + label + ".");
    }

    private ItemStack kit(EntityPlayerMPFake bot)
    {
        String kit = BotGui.kitOf(bot);
        return MenuItems.button(Items.CHEST, ChatFormatting.YELLOW, "Kit: " + (kit == null ? "none" : kit),
                "Gives " + name + " this loadout and remembers it.",
                "Click to go to the next kit of the server.");
    }

    private ItemStack faction(BotPvpConfig cfg)
    {
        String faction = cfg.faction;
        return MenuItems.button(Items.LEAD, ChatFormatting.GOLD, "Faction: " + (faction == null ? "none" : faction),
                "Bots of one faction leave each other alone.",
                "Click to go to the next faction of the server.");
    }

    private ItemStack item(BotPvpConfig cfg, Entry entry)
    {
        Option option = entry.option();
        String title = BotOptionLayout.title(option.key());
        String note = BotOptionLayout.text(option.key());
        switch (option.kind())
        {
            case NUMBER -> {
                double value = BotOptionLayout.number(cfg, option.key());
                if (entry.role() == Role.MINUS)
                {
                    return MenuItems.button(Items.REDSTONE, ChatFormatting.RED, "-" + title,
                            "Lowers it by " + BotOptionLayout.format(option.step()) + ".", "Now " + BotOptionLayout.format(value) + ".");
                }
                if (entry.role() == Role.PLUS)
                {
                    return MenuItems.button(Items.EMERALD, ChatFormatting.GREEN, "+" + title,
                            "Raises it by " + BotOptionLayout.format(option.step()) + ".", "Now " + BotOptionLayout.format(value) + ".");
                }
                return MenuItems.item(Items.PAPER,
                        MenuItems.text(title + ": " + BotOptionLayout.format(value), ChatFormatting.WHITE),
                        MenuItems.text(note, ChatFormatting.GRAY));
            }
            case TOGGLE -> {
                boolean on = BotOptionLayout.flag(cfg, option.key());
                return on ? MenuItems.on(title + ": on", note, "Click to turn it off.")
                        : MenuItems.off(title + ": off", note, "Click to turn it on.");
            }
            default -> {
                return MenuItems.item(Items.PAPER,
                        MenuItems.text(title + ": " + BotOptionLayout.value(cfg, option.key()), ChatFormatting.WHITE),
                        MenuItems.text(note, ChatFormatting.GRAY),
                        MenuItems.text("Set with /bot option " + name + " " + option.key() + " <value>", ChatFormatting.DARK_GRAY));
            }
        }
    }

    @Override
    protected String signature()
    {
        EntityPlayerMPFake bot = BotGui.bot(viewer, name);
        if (bot == null) return "gone";
        BotPvpConfig cfg = bot.getPvpConfig();
        // cfg.describe() holds every setting of the bot, the style options included.
        return "bot " + name + " page " + page + " " + bot.getHealth() + " " + BotGui.kitOf(bot) + " " + cfg.describe();
    }

    private MinecraftServer server()
    {
        return viewer.level().getServer();
    }
}