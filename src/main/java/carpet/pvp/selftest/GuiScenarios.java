package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.gui.BotGui;
import carpet.pvp.gui.BotMenu;
import carpet.pvp.gui.BotOptionLayout;
import carpet.pvp.gui.BotOptionLayout.Entry;
import carpet.pvp.kit.KitStore;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
//~ if <26.1 'ContainerInput' -> 'ClickType'
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The menus of {@code /bot gui}, driven the way the server drives them: a click is a call of the
 * menu's own click handler with the slot, the button and the kind of click a client would have sent.
 *
 * <p>A check that says "waiting" is polled until it says yes or the scenario runs out of ticks, so a
 * step that cannot be finished on the tick it started leaves the rest for the next one.</p>
 */
final class GuiScenarios
{
    /** What a check says when it has not got its answer yet. */
    private static final String WAITING = "waiting";

    /** The pages of the menu, and the button of the main one that gets to each of the others. */
    private enum Page
    {
        MAIN(-1, 6), SPAWN(45, 4), BOT(-2, 6);

        final int button;
        final int rows;

        Page(int button, int rows)
        {
            this.button = button;
            this.rows = rows;
        }

        /** How many slots the chest of this page has. */
        int grid()
        {
            return rows * 9;
        }
    }

    /** The two buttons a client may send; the second is the right click, or the hotbar key. */
    private static final int[] CLICKS = {0, 1};

    /** What the kit editor marks an empty slot of a layout with. */
    //~ if <26.1 'STAINED_GLASS_PANE.gray()' -> 'GRAY_STAINED_GLASS_PANE'
    private static final Item MARKER = Blocks.STAINED_GLASS_PANE.gray().asItem();

    private GuiScenarios() {}

    /**
     * A click on a toggle flips the setting it belongs to and the button says so afterwards. Any other
     * kind of click on the same button is not a click on it, and a setting a command changed shows up
     * in the open menu on the next tick without anybody touching it.
     */
    static Scenario toggleOption(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        Probe[] settled = {null};
        return new Scenario(200, List.of(new Bot(a, origin)), List.of(), server ->
        {
            if (settled[0] != null) return settled[0];
            EntityPlayerMPFake bot = fake(server, a);
            BotPvpConfig cfg = bot.getPvpConfig();
            int slot = slotOf("critical");
            if (phase[0] == 0)
            {
                phase[0] = 1;
                Probe step = script("gui_toggle_option", () ->
                {
                    if (openPage(server, bot, Page.BOT, bot) == null) return new Probe(false, a + " could not open its page");
                    ItemStack before = menu(bot).getSlot(slot).getItem();
                    if (!name(before).endsWith(": on")) return new Probe(false, SelfTest.fmt("the toggle reads %s", name(before)));
//~ if <26.1 'ContainerInput' -> 'ClickType' {
                    click(bot, slot, ContainerInput.PICKUP);
                    if (cfg.critical) return new Probe(false, "the click did not turn criticals off");
                    ItemStack after = menu(bot).getSlot(slot).getItem();
                    if (!name(after).endsWith(": off")) return new Probe(false, SelfTest.fmt("the toggle still reads %s", name(after)));
                    // A shift click and a hotbar key are not a click on a button.
                    click(bot, slot, ContainerInput.QUICK_MOVE);
                    click(bot, slot, ContainerInput.SWAP);
                    if (cfg.critical) return new Probe(false, "a shift click or a hotbar key on a button turned it back on");
                    SelfTest.run(server, "bot option " + a + " critical true");
                    if (!cfg.critical) return new Probe(false, "the command did not turn criticals back on");
                    return new Probe(false, WAITING + " for the open menu to notice the command");
                });
                if (!step.ok() && !waiting(step)) settled[0] = step;
                return step;
            }
            // A tick later the open menu has painted itself from the state the command left behind.
            ItemStack painted = menu(bot).getSlot(slot).getItem();
            return script("gui_toggle_option", () -> new Probe(cfg.critical && name(painted).endsWith(": on"),
                    SelfTest.fmt("%s: the click made the button read off, the command made the bot %s critical and the open button read %s",
                            a, cfg.critical, name(painted))));
        });
    }

    /** Clicking the style of a bot goes to the next one, and a command sets the one the menu shows. */
    static Scenario cycleStyle(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        Probe[] settled = {null};
        BotPvpConfig.CombatStyle[] styles = BotPvpConfig.CombatStyle.values();
        return new Scenario(200, List.of(new Bot(a, origin)), List.of(), server ->
        {
            if (settled[0] != null) return settled[0];
            EntityPlayerMPFake bot = fake(server, a);
            BotPvpConfig cfg = bot.getPvpConfig();
            // The next style is the one the menu itself would go to, so the test does not depend on the
            // order the enum happens to be written in.
            BotPvpConfig.CombatStyle first = styles[(cfg.combatStyle.ordinal() + 1) % styles.length];
            BotPvpConfig.CombatStyle second = styles[(first.ordinal() + 1) % styles.length];
            if (phase[0] == 0)
            {
                phase[0] = 1;
                Probe step = script("gui_cycle_style", () ->
                {
                    if (openPage(server, bot, Page.BOT, bot) == null) return new Probe(false, a + " could not open its page");
                    click(bot, 1, ContainerInput.PICKUP);
                    if (cfg.combatStyle != first)
                    {
                        return new Probe(false, SelfTest.fmt("one click on the style left it at %s", cfg.combatStyle));
                    }
                    String shown = name(menu(bot).getSlot(1).getItem());
                    if (!shown.endsWith(BotGui.styleName(first)))
                    {
                        return new Probe(false, SelfTest.fmt("the button reads %s while the bot fights %s", shown, first));
                    }
                    click(bot, 1, ContainerInput.PICKUP);
                    if (cfg.combatStyle != second) return new Probe(false, "the second click did not go on to the next style");
                    SelfTest.run(server, "bot option " + a + " combatstyle mace");
                    if (cfg.combatStyle != BotPvpConfig.CombatStyle.MACE) return new Probe(false, "the command did not take");
                    return new Probe(false, WAITING + " for the open menu to notice the command");
                });
                if (!step.ok() && !waiting(step)) settled[0] = step;
                return step;
            }
            String shown = name(menu(bot).getSlot(1).getItem());
            return script("gui_cycle_style", () -> new Probe(shown.endsWith(BotGui.styleName(cfg.combatStyle)),
                    SelfTest.fmt("%s went sword, then %s by clicking and %s by command, and the button reads %s",
                            a, second, cfg.combatStyle, shown)));
        });
    }

    /** The spawn page: a style, a difficulty and a button, and a bot comes out of it set to both. */
    static Scenario spawn(String a, String b, String c, Vec3 origin)
    {
        boolean[] started = {false};
        boolean[] clicked = {false};
        Set<String> before = new HashSet<>();
        Probe[] settled = {null};
        String[] spawned = {null};
        // The slots the spawn page puts a style and a difficulty on, read off the enums themselves.
        int mode = BotPvpConfig.CombatStyle.SMP.ordinal();
        int difficulty = BotPvpConfig.Difficulty.SKILLED.ordinal();
        return new Scenario(200, List.of(new Bot(a, origin)), List.of(), server ->
        {
            if (settled[0] != null) return settled[0];
            if (!started[0])
            {
                before.addAll(botNames(server));
                started[0] = true;
            }
            ServerPlayer viewer = SelfTest.player(server, a);
            if (!clicked[0])
            {
                clicked[0] = true;
                Probe page = script("gui_spawn", () ->
                {
                    if (openPage(server, viewer, Page.SPAWN, viewer) == null) return new Probe(false, a + " could not open the spawn page");
                    click(viewer, mode, ContainerInput.PICKUP);
                    click(viewer, 9 + difficulty, ContainerInput.PICKUP);
                    if (!name(menu(viewer).getSlot(mode).getItem()).startsWith(BotOptionLayout.CHONEN))
                    {
                        return new Probe(false, "the style that was picked is not marked");
                    }
                    click(viewer, 31, ContainerInput.PICKUP);
                    return new Probe(true, "the spawn page was driven with three clicks");
                });
                if (!page.ok())
                {
                    settled[0] = page;
                    return page;
                }
            }
            for (String name : botNames(server))
            {
                if (!before.contains(name)) spawned[0] = name;
            }
            if (spawned[0] == null) return SelfTest.pending(WAITING + " for the bot the menu spawned");
            settled[0] = script("gui_spawn", () ->
            {
                try
                {
                    EntityPlayerMPFake fresh = (EntityPlayerMPFake) SelfTest.player(server, spawned[0]);
                    BotPvpConfig cfg = fresh.getPvpConfig();
                    boolean asAsked = cfg.combatStyle == BotPvpConfig.CombatStyle.SMP
                            && cfg.difficulty == BotPvpConfig.Difficulty.SKILLED && cfg.combat;
                    if (!asAsked)
                    {
                        return new Probe(false, SelfTest.fmt("%s joined with style %s, difficulty %s, combat %s",
                                spawned[0], cfg.combatStyle, cfg.difficulty, cfg.combat));
                    }
                    return new Probe(true, SelfTest.fmt("%s joined where %s stood, fighting with %s at %s",
                            spawned[0], a, cfg.combatStyle, cfg.difficulty));
                }
                finally
                {
                    for (String name : botNames(server))
                    {
                        if (!before.contains(name)) SelfTest.run(server, "player " + name + " disconnect");
                    }
                }
            });
            return settled[0];
        });
    }

    /**
     * Every click a client can make, on every slot of every page, leaves the player's own inventory
     * alone and the items of the menu where they were.
     */
    static Scenario noItemTheft(String a, String b, String c, Vec3 origin)
    {
        Probe[] settled = {null};
        return new Scenario(400, List.of(new Bot(a, origin), new Bot(b, origin.add(3.0D, 0.0D, 0.0D))),
                List.of("give " + a + " minecraft:diamond_sword", "give " + a + " minecraft:golden_apple 5",
                        "give " + a + " minecraft:netherite_chestplate"), server ->
        {
            if (settled[0] != null) return settled[0];
            Set<String> before = botNames(server);
            settled[0] = script("gui_no_item_theft", () ->
            {
                try
                {
                    ServerPlayer viewer = fake(server, a);
                    ServerPlayer other = fake(server, b);
                    List<ItemStack> untouched = SelfTest.slots(viewer);
                    int lying = dropped(server, viewer);
                    EnumMap<Page, AbstractContainerMenu> open = new EnumMap<>(Page.class);
                    int clicks = 0;
                    for (Page page : Page.values())
                    {
                        for (ContainerInput input : ContainerInput.values())
                        {
                            for (int button : CLICKS)
                            {
                                // From the bottom of a page up, so that the one button which takes a
                                // bot off the server is asked about last.
                                for (int slot = page.grid() + Inventory.INVENTORY_SIZE + 9 - 1; slot >= 0; slot--)
                                {
                                    if (viewer.containerMenu != open.get(page))
                                    {
                                        AbstractContainerMenu opened = openPage(server, viewer, page, other);
                                        // The page needs a bot and there is none left to open it for.
                                        if (opened == null) break;
                                        open.put(page, opened);
                                    }
                                    AbstractContainerMenu menu = viewer.containerMenu;
                                    if (slot >= menu.slots.size()) continue;
                                    int count = itemCount(menu);
                                    List<Integer> occupied = occupied(menu);
                                    int paints = paints(menu);
                                    click(viewer, slot, input, button);
                                    // A button that changed what the menu shows paints it again, so only a
                                    // click that painted nothing can be held to keeping every item in place.
                                    String problem = stolen(viewer, menu, untouched, count, occupied, paints);
                                    if (problem != null)
                                    {
                                        return new Probe(false, SelfTest.fmt("a %s click on slot %d of the %s page %s",
                                                input, slot, page, problem));
                                    }
                                    clicks++;
                                }
                            }
                        }
                    }
                    if (dropped(server, viewer) != lying) return new Probe(false, "a click dropped an item on the ground");
                    return new Probe(true, SelfTest.fmt("%d clicks of %d kinds over the slots of three pages left %s with exactly the %d slots it started with, and dropped nothing",
                            clicks, ContainerInput.values().length, a, untouched.size()));
                }
                finally
                {
                    for (String name : botNames(server))
                    {
                        if (!before.contains(name)) SelfTest.run(server, "player " + name + " disconnect");
                    }
                }
            });
            return settled[0];
        });
    }

    /** A kit laid out and saved through the editor is handed back identically by {@code /bot kit give}. */
    static Scenario kitEditorRoundtrip(String a, String b, String c, Vec3 origin)
    {
        String saved = "gui_roundtrip";
        Probe[] settled = {null};
        return new Scenario(300, List.of(new Bot(a, origin), new Bot(b, origin.add(3.0D, 0.0D, 0.0D))),
                List.of("give " + a + " minecraft:golden_apple 3"), server ->
        {
            if (settled[0] != null) return settled[0];
            settled[0] = script("gui_kit_editor_roundtrip", () ->
            {
                KitStore store = KitStore.of(server);
                store.delete(saved);
                store.delete(BotGui.NEW_KIT);
                ServerPlayer viewer = fake(server, a);
                List<ItemStack> untouched = SelfTest.slots(viewer);
                try
                {
                    // The editor of the sword kit, reached the way a player reaches it.
                    if (openPage(server, viewer, Page.MAIN, viewer) == null) return new Probe(false, a + " could not open the menu");
                    click(viewer, 47, ContainerInput.PICKUP);
                    int swords = kitSlot(server, "sword");
                    if (swords < 0) return new Probe(false, "the sword kit is not on the first page of the kit list");
                    click(viewer, swords, ContainerInput.PICKUP);

                    String problem = SelfTest.sameInventory(untouched, SelfTest.slots(viewer));
                    if (problem != null) return new Probe(false, "opening the editor changed the inventory: " + problem);
                    if (!menu(viewer).getSlot(0).getItem().is(Items.DIAMOND_SWORD))
                    {
                        return new Probe(false, SelfTest.fmt("the editor opened with %s in its first slot",
                                SelfTest.describe(menu(viewer).getSlot(0).getItem())));
                    }

                    // Take the weapon out of the kit with a shift click, as a player would.
                    click(viewer, 0, ContainerInput.QUICK_MOVE);
                    if (!menu(viewer).getSlot(0).getItem().isEmpty()) return new Probe(false, "the shift click left the weapon in the kit");
                    int weapon = holding(viewer, Items.DIAMOND_SWORD);
                    if (weapon < 0) return new Probe(false, "the weapon did not reach the inventory of the player");

                    // Put it back where it came from.
                    click(viewer, weapon, ContainerInput.PICKUP);
                    click(viewer, 0, ContainerInput.PICKUP);
                    problem = SelfTest.sameInventory(untouched, SelfTest.slots(viewer));
                    if (problem != null) return new Probe(false, "putting the weapon back left the inventory changed: " + problem);
                    if (!menu(viewer).getSlot(0).getItem().is(Items.DIAMOND_SWORD))
                    {
                        return new Probe(false, SelfTest.fmt("the weapon is not back in the kit, but %s is",
                                SelfTest.describe(menu(viewer).getSlot(0).getItem())));
                    }

                    // A second weapon goes into a slot that is still empty.
                    SelfTest.run(server, "give " + a + " minecraft:diamond_axe");
                    int axe = holding(viewer, Items.DIAMOND_AXE);
                    if (axe < 0) return new Probe(false, "the axe never reached the player");
                    click(viewer, axe, ContainerInput.PICKUP);
                    click(viewer, 4, ContainerInput.PICKUP);
                    if (!menu(viewer).getSlot(4).getItem().is(Items.DIAMOND_AXE))
                    {
                        return new Probe(false, SelfTest.fmt("the axe did not go into the layout, but %s is there",
                                SelfTest.describe(menu(viewer).getSlot(4).getItem())));
                    }
                    List<ItemStack> layout = editorLayout(viewer);

                    // Save it under a name of its own, which is what the prompt of save as hands out.
                    click(viewer, 47, ContainerInput.PICKUP);
                    if (SelfTest.result(server, "execute as " + a + " run bot gui saveas " + saved) != 1)
                    {
                        return new Probe(false, "/bot gui saveas " + saved + " saved nothing");
                    }
                    if (!store.customNames().contains(saved)) return new Probe(false, saved + " is not a kit of the server");

                    // And the kit command hands the layout back the way it was laid out.
                    if (SelfTest.result(server, "bot kit give " + b + " " + saved) != 1)
                    {
                        return new Probe(false, "bot kit give " + b + " " + saved + " failed");
                    }
                    problem = SelfTest.sameInventory(layout, worn(SelfTest.player(server, b)));
                    if (problem != null) return new Probe(false, "the saved kit came back different: " + problem);
                    problem = SelfTest.sameInventory(untouched, SelfTest.slots(viewer));
                    if (problem != null) return new Probe(false, "the editor left the inventory changed: " + problem);

                    // The save button of an empty editor writes a kit of its own.
                    if (openPage(server, viewer, Page.MAIN, viewer) == null) return new Probe(false, a + " could not open the menu again");
                    click(viewer, 47, ContainerInput.PICKUP);
                    click(viewer, 37, ContainerInput.PICKUP);
                    SelfTest.run(server, "give " + a + " minecraft:netherite_helmet");
                    int helmet = holding(viewer, Items.NETHERITE_HELMET);
                    if (helmet < 0) return new Probe(false, "the helmet never reached the player");
                    click(viewer, helmet, ContainerInput.PICKUP);
                    click(viewer, 0, ContainerInput.PICKUP);
                    click(viewer, 45, ContainerInput.PICKUP);
                    if (!store.customNames().contains(BotGui.NEW_KIT)) return new Probe(false, "the save button wrote no kit");
                    return new Probe(true, SelfTest.fmt("a %d slot layout came back from %s identical, and the save button wrote a kit of %d entries",
                            layout.size(), saved, store.get(BotGui.NEW_KIT).map(found -> found.entries().size()).orElse(0)));
                }
                finally
                {
                    store.delete(saved);
                    store.delete(BotGui.NEW_KIT);
                    BotGui.discard(viewer);
                }
            });
            return settled[0];
        });
    }

    // ===== driving the menus =====

    /** A click, the way the server handles the packet a client sends for it. */
    private static void click(ServerPlayer viewer, int slot, ContainerInput input)
    {
        click(viewer, slot, input, 0);
    }

    private static void click(ServerPlayer viewer, int slot, ContainerInput input, int button)
    {
        AbstractContainerMenu menu = viewer.containerMenu;
        menu.suppressRemoteUpdates();
        menu.clicked(slot, button, input, viewer);
        menu.resumeRemoteUpdates();
        menu.broadcastChanges();
    }

    /**
     * Opens one of the pages the way {@code /bot gui} and the buttons of the menus get there, and
     * returns the menu that is now on the player's screen, or null when it could not be opened.
     */
    private static AbstractContainerMenu openPage(MinecraftServer server, ServerPlayer viewer, Page page, ServerPlayer target)
    {
        if (SelfTest.result(server, "execute as " + viewer.getName().getString() + " run bot gui") == 0) return null;
        if (page != Page.MAIN) click(viewer, page.button, ContainerInput.PICKUP);
        if (page == Page.BOT)
        {
            int slot = BotGui.bots(viewer).indexOf(target);
            if (slot < 0) return null;
            click(viewer, slot, ContainerInput.PICKUP);
        }
        return viewer.containerMenu instanceof BotMenu ? viewer.containerMenu : null;
    }
//~}

    private static AbstractContainerMenu menu(ServerPlayer viewer)
    {
        return viewer.containerMenu;
    }

    /** The chest slot a setting's button sits on. */
    private static int slotOf(String key)
    {
        for (Entry entry : BotOptionLayout.page(0))
        {
            if (entry.option().key().equals(key)) return entry.slot();
        }
        return -1;
    }

    /** The slot a kit is listed in on the page of the kit list. */
    private static int kitSlot(MinecraftServer server, String kit)
    {
        return KitStore.of(server).names().indexOf(kit);
    }

    /** The slot of the player's own inventory that holds an item, or -1. */
    private static int holding(ServerPlayer viewer, Item item)
    {
        AbstractContainerMenu menu = viewer.containerMenu;
        for (int slot = menu.slots.size() - Inventory.INVENTORY_SIZE - 9; slot < menu.slots.size(); slot++)
        {
            if (menu.getSlot(slot).getItem().is(item)) return slot;
        }
        return -1;
    }

    /** The layout as the editor holds it: the inventory, then the armour and the offhand. */
    private static List<ItemStack> editorLayout(ServerPlayer viewer)
    {
        List<ItemStack> layout = new ArrayList<>();
        for (int i = 0; i < Layout.SLOTS; i++)
        {
            ItemStack stack = viewer.containerMenu.getSlot(i).getItem();
            layout.add(stack.is(MARKER) ? ItemStack.EMPTY : stack.copy());
        }
        return layout;
    }

    /** The same slots as a player wearing that layout would have them. */
    private static List<ItemStack> worn(ServerPlayer player)
    {
        List<ItemStack> worn = new ArrayList<>();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) worn.add(player.getInventory().getItem(i).copy());
        for (EquipmentSlot slot : Layout.EQUIPMENT) worn.add(player.getItemBySlot(slot).copy());
        return worn;
    }

    /** The layout of a kit, written out here rather than read from the code that lays it out. */
    private static final class Layout
    {
        static final EquipmentSlot[] EQUIPMENT = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND };
        static final int SLOTS = Inventory.INVENTORY_SIZE + EQUIPMENT.length;
    }

    private static String name(ItemStack stack)
    {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? SelfTest.describe(stack) : name.getString();
    }

    private static EntityPlayerMPFake fake(MinecraftServer server, String name)
    {
        return (EntityPlayerMPFake) SelfTest.player(server, name);
    }

    private static Set<String> botNames(MinecraftServer server)
    {
        Set<String> names = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake) names.add(player.getName().getString());
        }
        return names;
    }

    // ===== what a click must not do =====

    /** How many items the chest of a menu holds, the buttons as well as any layout. */
    private static int itemCount(AbstractContainerMenu menu)
    {
        int count = 0;
        for (int i = 0; i < gridOf(menu); i++) count += menu.getSlot(i).getItem().getCount();
        return count;
    }

    private static List<Integer> occupied(AbstractContainerMenu menu)
    {
        List<Integer> occupied = new ArrayList<>();
        for (int i = 0; i < gridOf(menu); i++)
        {
            if (!menu.getSlot(i).getItem().isEmpty()) occupied.add(i);
        }
        return occupied;
    }

    private static int gridOf(AbstractContainerMenu menu)
    {
        return menu.slots.size() - Inventory.INVENTORY_SIZE - 9;
    }

    private static String stolen(ServerPlayer viewer, AbstractContainerMenu menu, List<ItemStack> before,
            int count, List<Integer> occupied, int paints)
    {
        String problem = SelfTest.sameInventory(before, SelfTest.slots(viewer));
        if (problem != null) return "changed the inventory, " + problem;
        if (!viewer.containerMenu.getCarried().isEmpty())
        {
            return "left " + SelfTest.describe(viewer.containerMenu.getCarried()) + " on the cursor";
        }
        if (viewer.containerMenu == menu && paints(menu) == paints)
        {
            if (count != itemCount(menu)) return "left the menu with " + itemCount(menu) + " items instead of " + count;
            if (!occupied.equals(occupied(menu))) return "changed which slots hold something";
        }
        return null;
    }

    private static int paints(AbstractContainerMenu menu)
    {
        return menu instanceof BotMenu bot ? bot.paints() : -1;
    }

    /** How many items are lying on the ground within four blocks of the player. */
    private static int dropped(MinecraftServer server, ServerPlayer player)
    {
        return server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(player.position(), player.position()).inflate(4.0D)).size();
    }

    private static boolean waiting(Probe probe)
    {
        return probe.detail().startsWith(WAITING);
    }

    private static Probe script(String name, Body body)
    {
        try
        {
            return body.run();
        }
        catch (RuntimeException e)
        {
            return new Probe(false, name + " threw " + e);
        }
    }

    @FunctionalInterface
    private interface Body
    {
        Probe run();
    }
}