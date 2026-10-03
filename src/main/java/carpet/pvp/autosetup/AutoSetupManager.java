package carpet.pvp.autosetup;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The sessions of a server: which players are fighting, what they are fighting, and the file each
 * of them leaves behind for as long as it runs.
 *
 * <p>Everything that has to be undone goes through here, so that the three ways a session ends all
 * undo the same things: {@code /auto-setup stop}, the player logging out and the server stopping
 * clean up in full, while a crash is undone by reading the files again and doing the same work for
 * whoever logs in next.</p>
 */
public final class AutoSetupManager
{
    /** The folder of the world's session files, one per player. */
    private static final String FOLDER = "carpet-autosetup";
    /** How many names to try before giving up on a free bot name. */
    private static final int NAME_TRIES = 32;

    private static final Map<String, AutoSetupSession> SESSIONS = new LinkedHashMap<>();
    private static final Map<String, SavedState> RECOVERED = new LinkedHashMap<>();
    private static Path folder;

    private AutoSetupManager() {}

    // ===== the sessions =====

    /** The session a player is in, or null while they are not fighting. */
    public static AutoSetupSession session(ServerPlayer player)
    {
        return SESSIONS.get(player.getName().getString());
    }

    /**
     * Starts a session, or the next round of the one the player is already in: asking for the mode
     * they are fighting in again is a rematch, which keeps the score, and asking for another one
     * ends what they had before, since a player only ever fights one bot at a time.
     *
     * @param difficulty what to fight next, or null to go on with the one the session has
     * @throws AutoSetupSession.Failed when no session can be started, with the reason to tell the player
     */
    public static AutoSetupSession start(MinecraftServer server, ServerPlayer player, AutoMode mode,
            BotPvpConfig.Difficulty difficulty) throws AutoSetupSession.Failed
    {
        String name = player.getName().getString();
        AutoSetupSession existing = SESSIONS.get(name);
        if (existing != null && existing.mode() == mode)
        {
            if (difficulty != null) existing.setDifficulty(difficulty);
            existing.rematch();
            return existing;
        }
        stop(server, name);
        AutoSetupSession session = AutoSetupSession.open(server, player, mode,
                difficulty != null ? difficulty : defaultDifficulty());
        SESSIONS.put(name, session);
        return session;
    }

    /** The difficulty the {@code botDifficulty} rule names, or the middle one when it names none. */
    static BotPvpConfig.Difficulty defaultDifficulty()
    {
        try
        {
            return BotPvpConfig.Difficulty.valueOf(AutoSetupSettings.defaultDifficulty().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return BotPvpConfig.Difficulty.AVERAGE;
        }
    }

    /**
     * Ends a player's session and gives everything back.
     *
     * @return false when there was no session, or when the player could not be given their things
     *         back, in which case the file stays and a login will finish the job
     */
    public static boolean stop(MinecraftServer server, String player)
    {
        AutoSetupSession session = SESSIONS.remove(player);
        if (session == null) return false;
        return session.close(false);
    }

    /** Ticks every session: the countdown, the fight and the menu in front of it. */
    public static void tick(MinecraftServer server)
    {
        for (AutoSetupSession session : new ArrayList<>(SESSIONS.values()))
        {
            if (server.getPlayerList().getPlayerByName(session.playerName()) == null)
            {
                stop(server, session.playerName());
                continue;
            }
            try
            {
                session.tick();
            }
            catch (RuntimeException e)
            {
                // A session that cannot go on is better stopped than left half running, and stopping
                // it hands the player their things back.
                AutoSetupSettings.LOG.error("/auto-setup stopped the session of " + session.playerName(), e);
                stop(server, session.playerName());
            }
        }
    }

    // ===== the files =====

    /**
     * Reads the files the last shutdown left behind, so that whoever logs in is given their things
     * back. Called once the world is loaded.
     */
    public static void load(MinecraftServer server)
    {
        folder = server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
        RECOVERED.clear();
        if (!Files.isDirectory(folder)) return;
        List<Path> files;
        try (var listed = Files.list(folder))
        {
            files = listed.filter(Files::isRegularFile).sorted().toList();
        }
        catch (IOException e)
        {
            AutoSetupSettings.LOG.error("/auto-setup cannot list " + folder + ": " + e.getMessage());
            return;
        }
        for (Path file : files)
        {
            String fileName = file.getFileName().toString();
            if (!fileName.endsWith(".json")) continue;
            try
            {
                SessionFile session = SessionFile.parse(Files.readString(file, StandardCharsets.UTF_8));
                RECOVERED.put(session.player(), session.saved());
                AutoSetupSettings.LOG.info("/auto-setup is holding the things of " + session.player()
                        + " back from a session the server did not survive");
            }
            catch (IOException | IllegalArgumentException e)
            {
                // A file nobody can read is left where it is: it is not this mod's to throw away.
                AutoSetupSettings.LOG.error("/auto-setup cannot read " + fileName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Throws away every session as a crash would, without touching the files, and reads them again.
     * What is left is what a restart of this server would find.
     */
    public static void reload(MinecraftServer server)
    {
        SESSIONS.clear();
        load(server);
    }

    /** The file a player's session is kept in. */
    public static Path fileFor(MinecraftServer server, String player)
    {
        return folder(server).resolve(safeName(player) + ".json");
    }

    /** True while a session file is on disk, which is what a recovery waits for. */
    public static boolean isUnrecovered(String player)
    {
        return RECOVERED.containsKey(player);
    }

    // ===== the hooks the server calls =====

    /**
     * A player has logged in: if the server did not survive their last session, this is where they
     * get their things, their place and their game mode back, and the arena comes down.
     */
    public static void onPlayerJoined(MinecraftServer server, ServerPlayer player)
    {
        String name = player.getName().getString();
        SavedState saved = RECOVERED.remove(name);
        if (saved == null) return;
        if (!giveBack(server, player, saved))
        {
            // Nothing is deleted until the player is whole again, and the next login tries once more.
            RECOVERED.put(name, saved);
            AutoSetupSettings.LOG.error("/auto-setup could not give " + name + " their things back; their "
                    + "file is kept for the next time they log in");
            return;
        }
        delete(server, name);
        Menus.recovered(player);
    }

    /** A player has logged out: their session cannot outlive them, so it is cleaned up. */
    public static void onPlayerLeft(MinecraftServer server, ServerPlayer player)
    {
        stop(server, player.getName().getString());
    }

    /**
     * The server is stopping: every session is cleaned up, so that nothing is left holding a file.
     * A file that is waiting for its player to log in is left alone: until then it is all that is
     * left of what they owned.
     */
    public static void onServerStopping(MinecraftServer server)
    {
        for (String player : new ArrayList<>(SESSIONS.keySet()))
        {
            stop(server, player);
        }
    }

    // ===== what a session needs from the manager =====

    /**
     * Writes what a player would lose if the server died now. Nothing has been touched yet the
     * first time this is called, so a failure there leaves the player exactly as they were.
     */
    static void write(AutoSetupSession session)
    {
        Path target = fileFor(session.server(), session.playerName());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try
        {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, session.file().toJson(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            try
            {
                Files.deleteIfExists(temporary);
            }
            catch (IOException ignored)
            {
                // The half written file is named apart from the session, so it cannot be read as one.
            }
            throw new AutoSetupSession.Failed("your things could not be saved, so nothing was changed: "
                    + e.getMessage());
        }
    }

    /**
     * Undoes everything a session did, from the record of it: the arena comes down, the rules go
     * back to what they were, and the player gets their own things, their game mode and their place.
     * Stopping a session and recovering one that a crash left behind are the same call.
     *
     * @return whether the player is whole again; false when they were not there to be given them
     *         back, which is what keeps their file for the next login
     */
    static boolean giveBack(MinecraftServer server, ServerPlayer player, SavedState saved)
    {
        if (saved == null) return true;
        Arena.restore(server, saved.spot().dimension(), saved.blocks());
        for (String rule : saved.rules())
        {
            int split = rule.indexOf('=');
            if (split > 0) AutoSetupSettings.set(rule.substring(0, split), rule.substring(split + 1));
        }
        if (player == null) return false;
        try
        {
            Inventory inventory = player.getInventory();
            inventory.clearContent();
            for (int i = 0; i < Inventory.INVENTORY_SIZE && i < saved.slots().size(); i++)
            {
                inventory.setItem(i, AutoSetupSession.decode(server.registryAccess(), saved.slots().get(i)));
            }
            for (int i = 0; i < AutoSetupSession.EQUIPMENT.length; i++)
            {
                int slot = Inventory.INVENTORY_SIZE + i;
                player.setItemSlot(AutoSetupSession.EQUIPMENT[i], slot < saved.slots().size()
                        ? AutoSetupSession.decode(server.registryAccess(), saved.slots().get(slot)) : ItemStack.EMPTY);
            }
            inventory.setSelectedSlot(Mth.clamp(saved.selected(), 0, 8));
            inventory.setChanged();
            GameType mode = GameType.byName(saved.gamemode());
            if (mode != null) player.setGameMode(mode);
            ServerLevel level = Arena.level(server, saved.spot().dimension());
            if (level != null)
            {
                player.teleportTo(level, saved.spot().x(), saved.spot().y(), saved.spot().z(), Set.of(),
                        saved.spot().yaw(), saved.spot().pitch(), true);
            }
        }
        catch (RuntimeException e)
        {
            AutoSetupSettings.LOG.error("/auto-setup could not give " + player.getName().getString()
                    + " their things back", e);
            return false;
        }
        return true;
    }

    /** Takes a session out of the way and, when the player is whole again, deletes its file. */
    static boolean forget(AutoSetupSession session, boolean givenBack)
    {
        SESSIONS.remove(session.playerName());
        if (givenBack) delete(session.server(), session.playerName());
        return givenBack;
    }

    private static void delete(MinecraftServer server, String player)
    {
        try
        {
            Files.deleteIfExists(fileFor(server, player));
        }
        catch (IOException e)
        {
            AutoSetupSettings.LOG.error("/auto-setup could not delete the session file of " + player
                    + "; it will be read again on the next login: " + e.getMessage());
        }
    }

    /** A bot name of the player's own, free on this server and no longer than a name may be. */
    static String botNameFor(MinecraftServer server, String player)
    {
        String base = "AS_" + safeName(player);
        if (base.length() > 16) base = base.substring(0, 16);
        for (int i = 1; i <= NAME_TRIES; i++)
        {
            String name = i == 1 ? base
                    : base.substring(0, Math.min(base.length(), 16 - String.valueOf(i).length())) + i;
            if (server.getPlayerList().getPlayerByName(name) == null
                    && !EntityPlayerMPFake.isSpawningPlayer(name))
            {
                return name;
            }
        }
        throw new AutoSetupSession.Failed("there is no free name for a bot right now");
    }

    private static Path folder(MinecraftServer server)
    {
        return folder != null ? folder : server.getWorldPath(LevelResource.ROOT).resolve(FOLDER);
    }

    /** Player names are already safe, but a file name is worth being careful about. */
    private static String safeName(String player)
    {
        String safe = player.replaceAll("[^A-Za-z0-9_]", "_");
        return safe.isEmpty() ? "player" : safe;
    }
}
