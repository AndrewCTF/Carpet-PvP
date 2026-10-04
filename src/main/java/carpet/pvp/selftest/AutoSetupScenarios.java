package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.autosetup.ArenaBlocks;
import carpet.pvp.autosetup.AutoMode;
import carpet.pvp.autosetup.AutoSetupManager;
import carpet.pvp.autosetup.AutoSetupSession;
import carpet.pvp.autosetup.AutoSetupSettings;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitStore;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What {@code /auto-setup} does for a player: sets a fight up, takes it down again, and never costs
 * them what they owned. The player is a fake player, run as the sender of the command, which is all
 * a real one would be as far as the command can tell.
 */
final class AutoSetupScenarios
{
    private AutoSetupScenarios() {}

    /** The setting the scenarios turn off and on again around a session. */
    private static final String NAVIGATION = "fakePlayerNavigation";

    /** Puts navigation where a scenario wants it, through the same door a session uses. */
    private static void navigation(boolean on)
    {
        AutoSetupSettings.set(NAVIGATION, String.valueOf(on));
    }

    /** What the host of this run has navigation at right now. */
    private static boolean navigation()
    {
        return Boolean.parseBoolean(AutoSetupSettings.value(NAVIGATION));
    }

    /**
     * A player with a distinctive inventory starts a session, fights a beginner sword bot in an
     * arena, sees a round end and its score kept across a rematch, and stops the session: every
     * slot, the place, the game mode and every block the arena wrote over have to be as they were.
     */
    static Scenario roundtrip(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        Held<List<ItemStack>> held = new Held<>(null);
        Held<List<Integer>> world = new Held<>(null);
        Held<Vec3> spot = new Held<>(null);
        Held<String> gamemode = new Held<>(null);
        Held<String> rounds = new Held<>("");
        return new Scenario(2400, List.of(new Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return pending(a + " is still loading");
                give(server, a);
                held.value = SelfTest.slots(player);
                spot.value = player.position();
                world.value = snapshot(server, origin);
                gamemode.value = player.gameMode.getGameModeForPlayer().getName();
                if (SelfTest.result(server, as(a, "auto-setup sword beginner")) < 1)
                {
                    return pending("/auto-setup sword beginner did not start a session");
                }
                phase[0] = 1;
                return pending(a + " set a sword arena up 20 blocks to the east");
            }
            AutoSetupSession session = AutoSetupManager.session(player);
            if (phase[0] == 1)
            {
                if (session == null) return pending("waiting for the session to start");
                if (session.stage() != AutoSetupSession.Stage.FIGHTING)
                {
                    return pending("the countdown has " + session.countdown() + " ticks to run");
                }
                if (session.arena().blocks().bounds() == null
                        || !inside(snapshotBox(origin), session.arena().blocks().bounds()))
                {
                    return new Probe(false, "the arena is not inside the region the scenario checks");
                }
                if (!standingOn(player, session)) return pending("waiting for " + a + " to be moved into the arena");
                if (!SelfTest.holds(player.getMainHandItem(), "diamond_sword"))
                {
                    return new Probe(false, a + " holds " + SelfTest.describe(player.getMainHandItem())
                            + " instead of the sword of the kit");
                }
                if (!(server.getPlayerList().getPlayerByName(session.botName()) instanceof EntityPlayerMPFake bot))
                {
                    return pending("waiting for " + session.botName() + " to join");
                }
                if (player.getHealth() >= 20.0F)
                {
                    // what the bot is doing goes into the message, so a timeout here says why
                    return pending(SelfTest.fmt("waiting for %s to land a hit on %s: %.1f blocks apart, combat %s, navigation %s, the bot %s",
                            session.botName(), a, bot.distanceTo(player), bot.getPvpConfig().combat, navigation(),
                            bot.getBotBrain() == null || bot.getBotBrain().body() == null ? "has no body yet" : bot.getBotBrain().body().stats().describe()));
                }
                // The round ends when the bot goes down, which puts the menu up and the score on it;
                // asking for the same mode again is the next round, with the score kept. A bot is only
                // hurtable between its hits, so wait for that rather than losing the round.
                if (!SelfTest.hittable(bot)) return pending("waiting for " + session.botName() + " to be hurtable");
                if (SelfTest.result(server, "damage " + session.botName() + " 1000") < 1)
                {
                    return new Probe(false, "/damage could not put " + session.botName() + " down");
                }
                phase[0] = 2;
                return pending("the bot has taken " + SelfTest.fmt("%.1f", 20.0F - player.getHealth()) + " health off "
                        + a + ", putting it down for the end of the round");
            }
            if (phase[0] == 2)
            {
                if (session == null) return pending("waiting for the session");
                if (session.stage() != AutoSetupSession.Stage.MENU)
                {
                    // what the bot is doing goes into the message, so a timeout here says why
                    ServerPlayer opponent = server.getPlayerList().getPlayerByName(session.botName());
                    return pending(SelfTest.fmt("waiting for the round to end, the bot is on %d and %s",
                            session.botWins(), opponent == null ? "is gone"
                                    : SelfTest.fmt("has %.1f of %.1f health", opponent.getHealth(),
                                            opponent.getMaxHealth())));
                }
                if (session.playerWins() != 1 || session.botWins() != 0)
                {
                    return new Probe(false, "after the bot died the score is " + session.playerWins()
                            + " to " + session.botWins());
                }
                if (SelfTest.result(server, as(a, "auto-setup sword")) < 1)
                {
                    return pending("/auto-setup sword did not start the next round");
                }
                phase[0] = 3;
                return pending("the round is over and the next one has been asked for");
            }
            if (phase[0] == 3)
            {
                if (session == null || session.stage() == AutoSetupSession.Stage.MENU)
                {
                    return pending("waiting for the next round to start");
                }
                if (session.playerWins() != 1 || session.botWins() != 0)
                {
                    return new Probe(false, "the score went back to " + session.playerWins() + " to "
                            + session.botWins() + " on a rematch");
                }
                if (player.getHealth() < 20.0F)
                {
                    return pending("waiting for " + a + " to be healed for the next round");
                }
                if (SelfTest.result(server, as(a, "auto-setup stop")) < 1)
                {
                    return pending("/auto-setup stop did not stop the session");
                }
                rounds.value = "fought one round against a beginner sword bot and kept the score of 1 to 0 "
                        + "across a rematch";
                phase[0] = 4;
                return pending("the second round is on and the session is stopping");
            }
            if (session != null) return pending("waiting for the session to end");
            String problem = SelfTest.sameInventory(held.value, SelfTest.slots(player));
            if (problem != null) return new Probe(false, problem);
            if (!player.gameMode.getGameModeForPlayer().getName().equals(gamemode.value))
            {
                return new Probe(false, a + " is in " + player.gameMode.getGameModeForPlayer()
                        + " mode, was in " + gamemode.value);
            }
            double moved = player.position().distanceTo(spot.value);
            if (moved > 0.01D)
            {
                return new Probe(false, SelfTest.fmt("%s is %.3f blocks from where it started", a, moved));
            }
            String changed = difference(origin, world.value, snapshot(server, origin));
            return new Probe(changed == null, changed != null ? changed
                    : SelfTest.fmt("%s %s in an arena, and is back in %s with all %d of its slots and %.2f "
                            + "blocks from where it started; the %d blocks of the arena are what they were", a,
                            rounds.value, player.gameMode.getGameModeForPlayer(), held.value.size(), moved,
                            world.value.size()));
        });
    }

    /** Every mode a player can pick starts a fight, with its own kit and its own arena. */
    static Scenario eachMode(String a, String b, String c, Vec3 origin)
    {
        AutoMode[] modes = {AutoMode.SWORD, AutoMode.SMP, AutoMode.MACE, AutoMode.CRYSTAL};
        int[] phase = {0};
        return new Scenario(2000, List.of(new Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return pending(a + " is still loading");
                // The menu on its own, which is what a player who has read nothing types first.
                if (SelfTest.result(server, as(a, "auto-setup")) < 1)
                {
                    return new Probe(false, "/auto-setup on its own did not print its menu");
                }
                give(server, a);
                phase[0] = 1;
                return pending(a + " has a kit of its own to fight with");
            }
            // Two steps per mode: one to ask for it, one to see that it is going.
            int step = phase[0] - 1;
            if (step >= modes.length * 2)
            {
                return new Probe(true, a + " fought a " + modes[0].label() + ", " + modes[1].label() + ", "
                        + modes[2].label() + " and " + modes[3].label() + " bot without an error");
            }
            AutoMode mode = modes[step / 2];
            if (step % 2 == 0)
            {
                if (SelfTest.result(server, as(a, "auto-setup " + mode.name().toLowerCase(Locale.ROOT))) < 1)
                {
                    return new Probe(false, "/auto-setup " + mode.name().toLowerCase(Locale.ROOT) + " failed");
                }
                phase[0]++;
                return pending("setting up a " + mode.label() + " fight");
            }
            AutoSetupSession session = AutoSetupManager.session(player);
            if (session == null) return pending("waiting for the " + mode.label() + " session to start");
            if (session.stage() == AutoSetupSession.Stage.COUNTDOWN)
            {
                return pending("the " + mode.label() + " countdown has " + session.countdown() + " ticks to run");
            }
            if (session.mode() != mode)
            {
                return new Probe(false, "asked for " + mode.label() + ", fighting " + session.mode().label());
            }
            String problem = weapon(server, player, mode);
            if (problem != null) return new Probe(false, mode.label() + ": " + problem);
            if (!(server.getPlayerList().getPlayerByName(session.botName()) instanceof EntityPlayerMPFake bot))
            {
                return pending("waiting for the " + mode.label() + " bot to join");
            }
            if (bot.getPvpConfig().combatStyle != mode.style())
            {
                return new Probe(false, mode.label() + ": the bot fights with " + bot.getPvpConfig().combatStyle);
            }
            if (!server.overworld().getBlockState(session.arena().centre()).is(floorOf(mode).getBlock()))
            {
                return new Probe(false, mode.label() + ": the floor at " + session.arena().centre() + " is "
                        + server.overworld().getBlockState(session.arena().centre()));
            }
            if (SelfTest.result(server, as(a, "auto-setup stop")) < 1)
            {
                return pending("stopping the " + mode.label() + " session");
            }
            phase[0]++;
            return pending(a + " finished a " + mode.label() + " round");
        });
    }

    /**
     * A session survives the server dying: what the player owned is on disk before anything of it
     * is touched, and a restart reads it back and gives it all over again.
     */
    static Scenario crashSafe(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        Held<List<ItemStack>> held = new Held<>(null);
        Held<List<Integer>> world = new Held<>(null);
        return new Scenario(1200, List.of(new Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return pending(a + " is still loading");
                navigation(false);
                give(server, a);
                held.value = SelfTest.slots(player);
                world.value = snapshot(server, origin);
                if (SelfTest.result(server, as(a, "auto-setup sword beginner")) < 1)
                {
                    return pending("/auto-setup sword beginner did not start a session");
                }
                phase[0] = 1;
                return pending(a + " is fighting, with its things on disk");
            }
            AutoSetupSession session = phase[0] == 1 ? AutoSetupManager.session(player) : null;
            if (phase[0] == 1)
            {
                if (session == null) return pending("waiting for the session to start");
                if (session.stage() != AutoSetupSession.Stage.FIGHTING)
                {
                    return pending("the countdown has " + session.countdown() + " ticks to run");
                }
                if (!Files.isRegularFile(AutoSetupManager.fileFor(server, a)))
                {
                    return new Probe(false, "no session file was written for " + a);
                }
                // A crash: every session is forgotten and the files are read again, as a server that
                // came back up would find them. The bot does not come back, so it goes.
                String bot = session.botName();
                AutoSetupManager.reload(server);
                if (AutoSetupManager.session(player) != null)
                {
                    return new Probe(false, "the session of " + a + " survived the restart of the manager");
                }
                if (!AutoSetupManager.isUnrecovered(a))
                {
                    return new Probe(false, "the file of " + a + " was not read back");
                }
                SelfTest.run(server, SelfTest.cmd(bot + " disconnect"));
                phase[0] = 2;
                return pending("the manager restarted and is holding the things of " + a + " back");
            }
            if (phase[0] == 2)
            {
                AutoSetupManager.onPlayerJoined(server, player);
                phase[0] = 3;
                return pending(a + " logged in again");
            }
            String problem = SelfTest.sameInventory(held.value, SelfTest.slots(player));
            if (problem != null) return new Probe(false, "after the restart " + problem);
            if (Files.exists(AutoSetupManager.fileFor(server, a)))
            {
                return new Probe(false, "the file of " + a + " was left behind");
            }
            if (navigation())
            {
                return new Probe(false, NAVIGATION + " was left on");
            }
            String changed = difference(origin, world.value, snapshot(server, origin));
            return new Probe(changed == null, changed != null ? "after the restart " + changed
                    : SelfTest.fmt("after a restart that threw the session away, %s had all %d of its slots back, "
                            + "%s is off again and the arena is gone", a, held.value.size(), NAVIGATION));
        });
    }

    /** The rules a session needs are turned on while it runs and put back when it is over. */
    static Scenario rulesRestored(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        return new Scenario(900, List.of(new Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return pending(a + " is still loading");
                navigation(false);
                if (navigation())
                {
                    return new Probe(false, NAVIGATION + " could not be turned off for this scenario");
                }
                if (SelfTest.result(server, as(a, "auto-setup sword beginner")) < 1)
                {
                    return pending("/auto-setup sword beginner did not start a session");
                }
                phase[0] = 1;
                return pending(a + " is setting a session up with " + NAVIGATION + " off");
            }
            if (phase[0] == 1)
            {
                if (AutoSetupManager.session(player) == null) return pending("waiting for the session to start");
                if (!navigation())
                {
                    return new Probe(false, NAVIGATION + " was not turned on for the session");
                }
                if (SelfTest.result(server, as(a, "auto-setup stop")) < 1)
                {
                    return pending("/auto-setup stop did not stop the session");
                }
                phase[0] = 2;
                return pending("the session is stopping with " + NAVIGATION + " on");
            }
            if (AutoSetupManager.session(player) != null) return pending("waiting for the session to end");
            boolean back = !navigation();
            navigation(true);
            return new Probe(back, back
                    ? SelfTest.fmt("%s was turned on for the session and put back to false when it ended",
                            NAVIGATION)
                    : SelfTest.fmt("%s was still on after the session ended", NAVIGATION));
        });
    }

    // ===== the pieces =====

    /** One value a scenario carries from one tick of its check to the next. */
    private static final class Held<T>
    {
        private T value;

        Held(T value)
        {
            this.value = value;
        }
    }

    private static Probe pending(String detail)
    {
        return new Probe(false, detail);
    }

    /** A run of the command as the given player, which is what a click of the menu does. */
    private static String as(String player, String command)
    {
        return "execute as " + player + " run " + command;
    }

    /** Something worth telling apart from an empty inventory, in two stacks. */
    private static void give(MinecraftServer server, String player)
    {
        SelfTest.run(server, "give " + player + " minecraft:golden_apple 3");
        SelfTest.run(server, "give " + player + " minecraft:emerald 5");
        SelfTest.run(server, "gamemode creative " + player);
    }

    /** The block the floor of a mode is made of. */
    private static BlockState floorOf(AutoMode mode)
    {
        return (mode == AutoMode.CRYSTAL ? Blocks.OBSIDIAN : Blocks.SMOOTH_STONE).defaultBlockState();
    }

    /** True when the player holds the weapon the kit of the mode starts with. */
    private static String weapon(MinecraftServer server, ServerPlayer player, AutoMode mode)
    {
        Kit kit = KitStore.of(server).get(mode.kitName()).orElse(null);
        if (kit == null || kit.entries().isEmpty()) return "there is no kit called " + mode.kitName();
        String wanted = Identifier.parse(kit.entries().get(0).item()).getPath();
        return SelfTest.holds(player.getMainHandItem(), wanted) ? null
                : player.getName().getString() + " holds " + SelfTest.describe(player.getMainHandItem())
                        + " instead of " + wanted;
    }

    /** True once the player stands where the session put them. */
    private static boolean standingOn(ServerPlayer player, AutoSetupSession session)
    {
        BlockPos spot = session.arena().playerSpawn();
        return Math.abs(player.getX() - (spot.getX() + 0.5D)) < 2.0D
                && Math.abs(player.getZ() - (spot.getZ() + 0.5D)) < 2.0D
                && Math.abs(player.getY() - spot.getY()) < 3.0D;
    }

    // ===== the world around the arena =====

    /** The region every scenario checks, generously around where its player spawns. */
    private static Box snapshotBox(Vec3 origin)
    {
        return new Box(floor(origin.x) - 2, floor(origin.y) - 4, floor(origin.z) - 14,
                floor(origin.x) + 36, floor(origin.y) + 14, floor(origin.z) + 14);
    }

    private static boolean inside(Box box, ArenaBlocks.Box bounds)
    {
        return box.minX <= bounds.minX() && bounds.maxX() <= box.maxX
                && box.minY <= bounds.minY() && bounds.maxY() <= box.maxY
                && box.minZ <= bounds.minZ() && bounds.maxZ() <= box.maxZ;
    }

    /** Every block of the region, as the block state ids they hold. */
    private static List<Integer> snapshot(MinecraftServer server, Vec3 origin)
    {
        Box box = snapshotBox(origin);
        List<Integer> states = new ArrayList<>(box.size());
        for (int x = box.minX; x <= box.maxX; x++)
        {
            for (int y = box.minY; y <= box.maxY; y++)
            {
                for (int z = box.minZ; z <= box.maxZ; z++)
                {
                    states.add(Block.getId(server.overworld().getBlockState(new BlockPos(x, y, z))));
                }
            }
        }
        return states;
    }

    /** The first block of the region that is not what it was, or null when there is none. */
    private static String difference(Vec3 origin, List<Integer> before, List<Integer> after)
    {
        if (before.size() != after.size())
        {
            return SelfTest.fmt("the checked region changed size, %d to %d", before.size(), after.size());
        }
        Box box = snapshotBox(origin);
        int depth = (box.maxY - box.minY + 1) * (box.maxZ - box.minZ + 1);
        for (int i = 0; i < before.size(); i++)
        {
            if (before.get(i).equals(after.get(i))) continue;
            int x = box.minX + i / depth;
            int rest = i % depth;
            int y = box.minY + rest / (box.maxZ - box.minZ + 1);
            int z = box.minZ + rest % (box.maxZ - box.minZ + 1);
            return SelfTest.fmt("the block at %d %d %d is %s, was %s", x, y, z,
                    Block.stateById(after.get(i)).getBlock(), Block.stateById(before.get(i)).getBlock());
        }
        return null;
    }

    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        int size()
        {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }
}
