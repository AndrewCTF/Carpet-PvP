package carpet.pvp.gui;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.FactionManager;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.style.StyleIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The way into the menus, and everything they do to the world: {@code /bot gui} opens the main one.
 *
 * <p>The kit a bot wears, when the menu gave it one of its own, and the kit layout a player is
 * editing are kept here for the session, as the registry of a bot's settings is not a place for
 * either.</p>
 */
public final class BotGui
{
    /** The name the kit editor starts under when the player did not pick one. */
    public static final String NEW_KIT = "new_kit";

    /** A bot the menu asked for, waiting for its login to finish. */
    private record PendingSpawn(ServerPlayer viewer, BotPvpConfig.CombatStyle style, String difficulty, String opponent) {}

    private static final Map<String, PendingSpawn> PENDING_SPAWNS = new HashMap<>();
    private static final Map<String, String> PENDING_DUELS = new HashMap<>();
    private static final Map<UUID, String> KITS = new HashMap<>();
    private static final Map<UUID, Kit> EDITING = new HashMap<>();

    private BotGui() {}

    /** Ticks with the server: finishes what the menus asked for and repaints the ones that are open. */
    public static void tick(MinecraftServer server)
    {
        finishSpawns(server);
        forget(server);
        BotMenu.tick(server);
    }

    /**
     * Drops what the menus remembered about players and bots that are no longer on the server, so that
     * a session does not keep the kit of everybody who ever opened the editor.
     */
    private static void forget(MinecraftServer server)
    {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) online.add(player.getUUID());
        KITS.keySet().removeIf(id -> !online.contains(id));
        EDITING.keySet().removeIf(id -> !online.contains(id));
    }

    // ===== opening =====

    /** Opens the main menu, the one a player lands in with {@code /bot gui}. */
    public static void openMain(ServerPlayer viewer)
    {
        MainMenu.open(viewer);
    }

    // ===== changing a bot =====

    /**
     * The one way the menus change a bot: the same {@link BotPvpConfig#apply} the commands use, so the
     * menu and {@code /bot option} cannot end up disagreeing about what a bot is set to.
     *
     * @return the error string, or null when the change went through
     */
    public static String apply(EntityPlayerMPFake bot, String key, String value)
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        String error = cfg.apply(key, value);
        if (error == null) FactionManager.sync(bot.getUUID(), cfg.faction);
        return error;
    }

    /** Says what went wrong, so the player is not left guessing why nothing happened. */
    public static void report(ServerPlayer viewer, String error)
    {
        if (error != null) viewer.sendSystemMessage(MenuItems.text(error, ChatFormatting.RED));
    }

    /** The kit a bot is wearing, the one its style comes with unless the menu changed it. */
    public static String kitOf(EntityPlayerMPFake bot)
    {
        return KITS.getOrDefault(bot.getUUID(), StyleIndex.kit(bot.getPvpConfig().combatStyle));
    }

    /** The next style after the given one, in the order the enum declares them. */
    public static BotPvpConfig.CombatStyle nextStyle(BotPvpConfig.CombatStyle style)
    {
        BotPvpConfig.CombatStyle[] values = BotPvpConfig.CombatStyle.values();
        return values[(style.ordinal() + 1) % values.length];
    }

    /** The name a style is given in commands and in the config: the melee style is called sword. */
    public static String styleName(BotPvpConfig.CombatStyle style)
    {
        return style == BotPvpConfig.CombatStyle.MELEE ? "sword" : style.name().toLowerCase(Locale.ROOT);
    }

    /** Puts a kit on a bot and remembers it as the kit of that bot. */
    public static boolean giveKit(EntityPlayerMPFake bot, String kit)
    {
        MinecraftServer server = serverOf(bot);
        Kit found = kit == null ? null : KitStore.of(server).get(kit).orElse(null);
        if (found == null) return false;
        KitInventory.apply(bot, found, server.registryAccess());
        KITS.put(bot.getUUID(), kit);
        return true;
    }

    /** Makes two bots target each other by putting them in a faction of their own and turning them on. */
    public static void duel(EntityPlayerMPFake one, EntityPlayerMPFake other)
    {
        if (one.getUUID().equals(other.getUUID())) return;
        for (EntityPlayerMPFake bot : List.of(one, other))
        {
            BotPvpConfig cfg = bot.getPvpConfig();
            apply(bot, "combat", "true");
            apply(bot, "autotarget", "true");
            apply(bot, "targetbots", "true");
            apply(bot, "targetplayers", "true");
            apply(bot, "targetrange", BotOptionLayout.format(Math.max(cfg.targetRange, 32.0D)));
            apply(bot, "faction", "bot_" + bot.getName().getString());
        }
    }

    /** Turns a bot onto whoever is looking at its page: another bot gets a duel, a player gets a fight. */
    public static void duelMe(EntityPlayerMPFake bot, ServerPlayer viewer)
    {
        if (viewer instanceof EntityPlayerMPFake other && other != bot)
        {
            duel(bot, other);
            return;
        }
        // A real player fights on their own; all the bot has to do is go looking for them.
        apply(bot, "combat", "true");
        apply(bot, "autotarget", "true");
        apply(bot, "targetplayers", "true");
    }

    /** Stops every bot of the server from fighting. */
    public static int stopAll(ServerPlayer viewer)
    {
        int stopped = 0;
        for (EntityPlayerMPFake bot : bots(viewer))
        {
            if (bot.getPvpConfig().combat)
            {
                apply(bot, "combat", "false");
                stopped++;
            }
        }
        viewer.sendSystemMessage(MenuItems.text(stopped == 0
                ? "No bot of this server is fighting."
                : "Stopped " + stopped + " bot(s).", ChatFormatting.YELLOW));
        return stopped;
    }

    /** The bots of the server, in the order the player list holds them. */
    public static List<EntityPlayerMPFake> bots(ServerPlayer viewer)
    {
        List<EntityPlayerMPFake> bots = new ArrayList<>();
        for (ServerPlayer player : viewer.level().getServer().getPlayerList().getPlayers())
        {
            if (player instanceof EntityPlayerMPFake bot) bots.add(bot);
        }
        return bots;
    }

    /** The bot of that name, or null when there is none. */
    public static EntityPlayerMPFake bot(ServerPlayer viewer, String name)
    {
        ServerPlayer player = viewer.level().getServer().getPlayerList().getPlayerByName(name);
        return player instanceof EntityPlayerMPFake bot ? bot : null;
    }

    // ===== spawning =====

    /** Starts a spawn of a bot next to the player. The bot is set up once it has finished logging in. */
    public static boolean spawnBot(ServerPlayer viewer, BotPvpConfig.CombatStyle style, String difficulty)
    {
        return spawnBot(viewer, style, difficulty, null) != null;
    }

    private static String spawnBot(ServerPlayer viewer, BotPvpConfig.CombatStyle style, String difficulty, String opponent)
    {
        MinecraftServer server = serverOf(viewer);
        String name = freeName(server, "Bot");
        if (name == null) return null;
        ResourceKey<Level> dimension = viewer.level().dimension();
        if (!EntityPlayerMPFake.createFake(name, server, viewer.position(), viewer.getYRot(), viewer.getXRot(),
                dimension, GameType.SURVIVAL, false)) return null;
        PendingSpawn spawn = new PendingSpawn(viewer, style, difficulty, opponent);
        PENDING_SPAWNS.put(name, spawn);
        // A server without authentication places the bot right away, the way /bot spawn finds it too.
        if (server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake bot)
        {
            finishSpawn(bot, PENDING_SPAWNS.remove(name));
        }
        return name;
    }

    /** Two bots of the viewer's own style, one for each of them, that start fighting each other. */
    public static boolean quickFight(ServerPlayer viewer)
    {
        BotPvpConfig defaults = new BotPvpConfig();
        BotPvpConfig.CombatStyle style = defaults.combatStyle;
        String difficulty = defaults.difficulty.name().toLowerCase(Locale.ROOT);
        String first = spawnBot(viewer, style, difficulty, null);
        if (first == null) return false;
        if (spawnBot(viewer, style, difficulty, first) == null) return false;
        viewer.sendSystemMessage(MenuItems.text(first + " and its opponent are on their way. Watch with "
                + "/bot gui", ChatFormatting.YELLOW));
        return true;
    }

    private static void finishSpawns(MinecraftServer server)
    {
        for (String name : new ArrayList<>(PENDING_SPAWNS.keySet()))
        {
            if (!(server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake bot)) continue;
            PendingSpawn spawn = PENDING_SPAWNS.remove(name);
            finishSpawn(bot, spawn);
            if (spawn.opponent() != null) PENDING_DUELS.put(name, spawn.opponent());
        }
        for (String name : new ArrayList<>(PENDING_DUELS.keySet()))
        {
            String opponent = PENDING_DUELS.get(name);
            if (!(server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake one)) continue;
            if (!(server.getPlayerList().getPlayerByName(opponent) instanceof EntityPlayerMPFake other)) continue;
            PENDING_DUELS.remove(name);
            duel(one, other);
        }
    }

    /** Turns a bot that has just joined into the fighter the menu asked for. */
    private static void finishSpawn(EntityPlayerMPFake bot, PendingSpawn spawn)
    {
        MinecraftServer server = serverOf(bot);
        String kit = StyleIndex.kit(spawn.style());
        if (kit != null) KitStore.of(server).get(kit).ifPresent(found -> KitInventory.apply(bot, found, server.registryAccess()));
        apply(bot, "combatstyle", styleName(spawn.style()));
        apply(bot, "combat", "true");
        apply(bot, "autotarget", "true");
        apply(bot, "targetbots", "true");
        report(spawn.viewer(), apply(bot, "difficulty", spawn.difficulty()));
        spawn.viewer().sendSystemMessage(MenuItems.text("Spawned " + bot.getName().getString()
                + " fighting with " + styleName(spawn.style()) + " at difficulty " + spawn.difficulty(),
                ChatFormatting.YELLOW));
    }

    private static String freeName(MinecraftServer server, String prefix)
    {
        for (int i = 1; i < 1000; i++)
        {
            String name = prefix + i;
            if (PENDING_SPAWNS.containsKey(name)) continue;
            if (server.getPlayerList().getPlayerByName(name) != null) continue;
            if (EntityPlayerMPFake.isSpawningPlayer(name)) continue;
            return name;
        }
        return null;
    }

    private static MinecraftServer serverOf(net.minecraft.world.entity.Entity entity)
    {
        return entity.level() instanceof ServerLevel level ? level.getServer() : null;
    }

    // ===== the kit editor =====

    /** Keeps the layout a player is editing, so a chat command can still reach it once the screen is gone. */
    static void remember(ServerPlayer viewer, Kit kit)
    {
        EDITING.put(viewer.getUUID(), kit);
    }

    /** Names the viewer could save their layout under, the first of which the prompt offers. */
    public static List<String> suggestions(ServerPlayer viewer)
    {
        List<String> names = new ArrayList<>();
        String base = viewer.getName().getString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_") + "_kit";
        for (int i = 1; i < 100 && names.size() < 8; i++)
        {
            String name = i == 1 ? base : base + i;
            if (Kit.isValidName(name) && KitStore.of(serverOf(viewer)).get(name).isEmpty()) names.add(name);
        }
        return names;
    }

    /** Saves the layout the viewer was editing under a name of their own. */
    public static boolean saveAs(ServerPlayer viewer, String name)
    {
        Kit layout = EDITING.get(viewer.getUUID());
        if (layout == null)
        {
            viewer.sendSystemMessage(MenuItems.text("You have no kit open in the editor.", ChatFormatting.RED));
            return false;
        }
        if (!Kit.isValidName(name))
        {
            viewer.sendSystemMessage(MenuItems.text("A kit name may only hold letters, digits, _ and -.", ChatFormatting.RED));
            return false;
        }
        Kit kit = new Kit(name, layout.entries());
        try
        {
            KitStore.of(serverOf(viewer)).save(kit);
        }
        catch (IOException e)
        {
            viewer.sendSystemMessage(MenuItems.text("Could not write kit " + name + ": " + e.getMessage(), ChatFormatting.RED));
            return false;
        }
        EDITING.remove(viewer.getUUID());
        viewer.sendSystemMessage(MenuItems.text("Saved kit " + name + " with " + kit.entries().size() + " entries.", ChatFormatting.GREEN));
        return true;
    }

    /** Throws the layout a player was editing away. */
    public static boolean discard(ServerPlayer viewer)
    {
        return EDITING.remove(viewer.getUUID()) != null;
    }

    /**
     * Asks for a name to save the layout under. A server cannot run the anvil screen without a client
     * behind it, so the prompt is a line of chat with the command in it and a button that runs it.
     */
    static void promptName(ServerPlayer viewer)
    {
        if (EDITING.get(viewer.getUUID()) == null)
        {
            viewer.sendSystemMessage(MenuItems.text("There is nothing to save.", ChatFormatting.RED));
            return;
        }
        List<String> names = suggestions(viewer);
        viewer.sendSystemMessage(MenuItems.text("Your layout is held. Save it with ", ChatFormatting.GRAY)
                .append(MenuItems.text("/bot gui saveas <name>", ChatFormatting.YELLOW))
                .append(MenuItems.text(", or click:", ChatFormatting.GRAY)));
        if (names.isEmpty())
        {
            viewer.sendSystemMessage(MenuItems.text("Every suggested name is taken, so type one of your own.", ChatFormatting.GRAY));
        }
        else
        {
            viewer.sendSystemMessage(MenuItems.text("[ save it as ", ChatFormatting.GRAY)
                    .append(click(names.get(0), "/bot gui saveas " + names.get(0)))
                    .append(MenuItems.text(" ]", ChatFormatting.GRAY)));
        }
        viewer.sendSystemMessage(MenuItems.text("[ ", ChatFormatting.GRAY)
                .append(click("throw it away", "/bot gui discard"))
                .append(MenuItems.text(" ]", ChatFormatting.GRAY)));
    }

    private static Component click(String text, String command)
    {
        return Component.literal(text).withStyle(Style.EMPTY
                .withColor(ChatFormatting.GREEN)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }
}