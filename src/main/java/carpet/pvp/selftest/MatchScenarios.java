package carpet.pvp.selftest;

import carpet.pvp.BotBody;
import carpet.pvp.CombatTrace;
import carpet.pvp.CombatTraces;
import carpet.pvp.FactionManager;
import carpet.pvp.FactionStore;
import carpet.pvp.MatchHistory;
import carpet.pvp.MatchManager;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** The practice features: matches, the factions behind them, looking through a bot and the fight trace. */
final class MatchScenarios
{
    /** The bots a free-for-all puts in the ring. */
    private static final List<String> FFA = List.of("Free1", "Free2", "Free3", "Free4");
    /** The bots a team match puts in the ring. */
    private static final List<String> RED = List.of("Red1", "Red2");
    private static final List<String> BLUE = List.of("Blue1", "Blue2");
    private static final List<String> TEAMS = List.of("Red1", "Red2", "Blue1", "Blue2");

    private MatchScenarios()
    {
    }

    /**
     * A free-for-all: four bots, each on a faction of its own, all fighting. Every bot has to hit and
     * be hit, and the match has to end with a result the history holds.
     */
    static Scenario ffa(String a, String b, String c, Vec3 origin)
    {
        double[] dealt = new double[FFA.size()];
        double[] counted = new double[FFA.size()];
        Set<String>[] hurtBy = sets(FFA.size());
        Probe[] verdict = {null};
        String[] step = {"setup"};
        return new Scenario(1500, List.of(new Bot(a, origin)), List.of(), SelfTest.NOTHING, server ->
        {
            if ("setup".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                step[0] = asPlayer(server, a, "bot match ffa 4 sword skilled") == 4 ? "run" : "refused";
                return SelfTest.pending("the match has been asked for");
            }
            if ("refused".equals(step[0]))
            {
                return new Probe(false, "/bot match ffa refused");
            }
            if (verdict[0] != null)
            {
                return verdict[0];
            }
            gather(FFA, dealt, counted, hurtBy);
            if (MatchManager.active())
            {
                return SelfTest.pending("the match is running: " + MatchManager.status());
            }
            sweep(server);
            gather(FFA, dealt, counted, hurtBy);
            int hitting = 0;
            int hit = 0;
            for (int i = 0; i < FFA.size(); i++)
            {
                if (dealt[i] > 0.0) hitting++;
                if (!hurtBy[i].isEmpty()) hit++;
            }
            MatchHistory.Match newest = MatchHistory.matches().isEmpty() ? null : MatchHistory.matches().get(0);
            boolean recorded = newest != null && newest.ticks() > 0 && !"nobody".equals(newest.winner());
            boolean ok = recorded && hitting == FFA.size() && hit == FFA.size();
            verdict[0] = new Probe(ok, SelfTest.fmt(
                    "%d of %d bots dealt damage (%s) and %d of %d were hit by another bot (%s); the history holds %s winning after %d ticks",
                    hitting, FFA.size(), join(dealt), hit, FFA.size(), names(hurtBy),
                    newest == null ? "nobody" : newest.winner(), newest == null ? 0 : newest.ticks()));
            return verdict[0];
        });
    }

    /**
     * A team match: two teams of two. Nobody may ever hit their own side, which the traces of the bots
     * are the evidence for, and the result is recorded.
     */
    static Scenario teams(String a, String b, String c, Vec3 origin)
    {
        double[] dealt = new double[TEAMS.size()];
        double[] counted = new double[TEAMS.size()];
        Set<String>[] from = sets(TEAMS.size());
        List<String> friendlyHits = new ArrayList<>();
        Probe[] verdict = {null};
        String[] step = {"setup"};
        return new Scenario(1500, List.of(new Bot(a, origin)), List.of(), SelfTest.NOTHING, server ->
        {
            if ("setup".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending("waiting for " + a + " to finish loading");
                }
                step[0] = asPlayer(server, a, "bot match teams 2 sword skilled") == 2 ? "run" : "refused";
                return SelfTest.pending("the match has been asked for");
            }
            if ("refused".equals(step[0]))
            {
                return new Probe(false, "/bot match teams refused");
            }
            if (verdict[0] != null)
            {
                return verdict[0];
            }
            gather(TEAMS, dealt, counted, from);
            for (String name : TEAMS)
            {
                CombatTrace fight = CombatTraces.fight(name);
                if (fight == null) continue;
                for (CombatTrace.Event event : fight.events())
                {
                    if (event.kind() == CombatTrace.Kind.HIT && sameTeam(name, event.other()))
                    {
                        friendlyHits.add(name + " hit " + event.other() + " on tick " + event.tick());
                    }
                }
            }
            if (MatchManager.active())
            {
                return SelfTest.pending("the match is running: " + MatchManager.status());
            }
            sweep(server);
            gather(TEAMS, dealt, counted, from);
            MatchHistory.Match newest = MatchHistory.matches().isEmpty() ? null : MatchHistory.matches().get(0);
            boolean recorded = newest != null && newest.ticks() > 0 && isTeam(newest.winner());
            int hitting = 0;
            for (double value : dealt)
            {
                if (value > 0.0) hitting++;
            }
            boolean ok = recorded && friendlyHits.isEmpty() && hitting == TEAMS.size();
            verdict[0] = new Probe(ok, SelfTest.fmt(
                    "%d of %d bots dealt damage (%s); the history holds %s winning after %d ticks with %.1f against %.1f; %s",
                    hitting, TEAMS.size(), join(dealt), newest == null ? "nobody" : newest.winner(),
                    newest == null ? 0 : newest.ticks(), newest == null ? 0.0 : newest.attackerDamage(),
                    newest == null ? 0.0 : newest.defenderDamage(),
                    friendlyHits.isEmpty() ? "no bot ever hit its own side" : friendlyHits.get(0)));
            return verdict[0];
        });
    }

    /**
     * The factions of a server live in the world folder: what is in the registry is written there, and
     * a registry that has been cleared comes back exactly as it was saved.
     */
    static Scenario factionPersistence(String a, String b, String c, Vec3 origin)
    {
        String[] step = {"save"};
        List<UUID> members = List.of(UUID.nameUUIDFromBytes("self-red".getBytes()),
                UUID.nameUUIDFromBytes("self-blue".getBytes()));
        return new Scenario(200, List.of(), List.of(), server ->
        {
            FactionManager.reset();
            FactionManager.create("self_red");
            FactionManager.create("self_blue");
            FactionManager.join("self_red", members.get(0));
            FactionManager.join("self_blue", members.get(1));
            FactionManager.ally("self_red", "self_blue");
            try
            {
                FactionStore.save(server, FactionManager.snapshot());
            }
            catch (java.io.IOException e)
            {
                throw new IllegalStateException("the factions could not be written to " + FactionStore.file(server), e);
            }
            FactionManager.reset();
        }, server ->
        {
            if ("save".equals(step[0]))
            {
                step[0] = "load";
                boolean cleared = FactionManager.allFactions().isEmpty();
                FactionManager.restore(FactionStore.load(server));
                return new Probe(cleared, SelfTest.fmt("the registry was cleared and the file in %s read back as %s",
                        FactionStore.file(server).getFileName(), FactionManager.allFactions()));
            }
            Set<String> factions = FactionManager.allFactions();
            boolean names = factions.containsAll(List.of("self_red", "self_blue"));
            boolean where = FactionManager.factionOf(members.get(0)).equals("self_red")
                    && FactionManager.factionOf(members.get(1)).equals("self_blue");
            boolean allied = FactionManager.areFriendly(members.get(0), members.get(1));
            FactionManager.reset();
            return new Probe(names && where && allied, SelfTest.fmt(
                    "%s came back with %s, its members in their own factions (%s) and the two still allied (%s)",
                    FactionStore.FILE, factions, where, allied));
        });
    }

    /**
     * Looking through a bot puts the caller in spectator mode on the bot's view, and stopping gives them
     * their game mode, their spot and their rotation back exactly.
     */
    static Scenario spectateRoundtrip(String a, String b, String c, Vec3 origin)
    {
        GameType[] before = {null};
        Vec3[] spot = {null};
        float[] yaw = {0.0F};
        String[] step = {"start"};
        return new Scenario(300, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 3.0D))),
                List.of(), server ->
                {
                    ServerPlayer player = SelfTest.player(server, a);
                    before[0] = player.gameMode.getGameModeForPlayer();
                    spot[0] = player.position();
                    yaw[0] = player.getYRot();
                    int started = asPlayer(server, a, "bot spectate " + b);
                    if (started != 1)
                    {
                        step[0] = "no spectate";
                    }
                }, server ->
        {
            ServerPlayer player = SelfTest.player(server, a);
            if (step[0].startsWith("no"))
            {
                return new Probe(false, "/bot spectate refused: " + step[0]);
            }
            if ("start".equals(step[0]))
            {
                boolean attached = player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR
                        && player.getCamera() == SelfTest.player(server, b);
                if (!attached)
                {
                    return SelfTest.pending("waiting for the camera to be attached");
                }
                step[0] = asPlayer(server, a, "bot spectate stop") == 1 ? "stop" : "no stop";
                if ("no stop".equals(step[0]))
                {
                    return new Probe(false, "/bot spectate stop refused");
                }
                return SelfTest.pending("the camera is back on the player");
            }
            double off = player.position().distanceTo(spot[0]);
            boolean ok = player.gameMode.getGameModeForPlayer() == before[0] && off < 1.0E-6D
                    && player.getYRot() == yaw[0] && player.getCamera() == player;
            return new Probe(ok, SelfTest.fmt(
                    "%s was %s at (%.3f, %.3f, %.3f) facing %.2f and is now %s at (%.3f, %.3f, %.3f) facing %.2f, %.6f blocks away, camera %s",
                    a, before[0], spot[0].x, spot[0].y, spot[0].z, yaw[0],
                    player.gameMode.getGameModeForPlayer(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), off, player.getCamera() == player ? "on itself" : "elsewhere"));
        });
    }

    /**
     * A fight leaves a trace: once the bots have stopped, the file the trace is written to holds the
     * same number of hits the bot's own counters do.
     */
    static Scenario traceRecordsFight(String a, String b, String c, Vec3 origin)
    {
        Vec3 walker = origin.add(0.0D, 0.0D, -4.5D);
        BotBody[] body = new BotBody[1];
        String[] step = {"fight"};
        int[] hits = {0};
        return new Scenario(1400, List.of(new Bot(a, origin), new Bot(b, walker)), List.of(), server ->
        {
            if (SelfTest.warmingUp(server, a, b))
            {
                SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                return;
            }
            SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordCombat(a, "expert").forEach(command -> SelfTest.run(server, command));
            SelfTest.run(server, "player " + b + " move forward for 20");
        }, server ->
        {
            if ("armed".equals(step[0]))
            {
                body[0] = SelfTest.body(server, a);
                if (body[0] == null)
                {
                    return SelfTest.pending("waiting for " + a + " to start fighting");
                }
                if (body[0].stats().hits < 1)
                {
                    return SelfTest.pending("waiting for " + a + " to land a hit on " + b);
                }
                hits[0] = body[0].stats().hits;
                SelfTest.run(server, "bot option " + a + " combat false");
                step[0] = "wait";
                return SelfTest.pending(a + " landed " + hits[0] + " hits and stopped fighting");
            }
            if ("fight".equals(step[0]))
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(a, "expert").forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "player " + b + " move forward for 20");
                step[0] = "armed";
                return SelfTest.pending(b + " walks up to " + a + " and then stands there");
            }
            if ("wait".equals(step[0]))
            {
                if (CombatTraces.last(a) == null)
                {
                    return SelfTest.pending("waiting for the fight trace of " + a + " to be written");
                }
                step[0] = "check";
                return SelfTest.pending("the trace of the last fight has been written");
            }
            Path file = CombatTraces.folder(server).resolve(a + ".json");
            if (!Files.isRegularFile(file))
            {
                return new Probe(false, "no trace file at " + file);
            }
            CombatTrace trace;
            try
            {
                trace = CombatTrace.read(file);
            }
            catch (java.io.IOException e)
            {
                return new Probe(false, "the trace at " + file + " could not be read: " + e);
            }
            int fromFile = trace.count(CombatTrace.Kind.HIT);
            int fromStats = body[0] == null ? hits[0] : body[0].stats().hits;
            boolean ok = fromFile == hits[0] && fromFile == fromStats && trace.size() > 0;
            return new Probe(ok, SelfTest.fmt(
                    "%s holds %d hits out of %d events, %.1f damage dealt and %.1f taken; the bot's own counters say %d hits",
                    file.getFileName(), fromFile, trace.size(), trace.damageDealt(), trace.damageTaken(),
                    fromStats));
        });
    }

    /** A command as if this player had typed it, which the player-scoped /bot subcommands need. */
    static int asPlayer(MinecraftServer server, String name, String command)
    {
        ServerPlayer player = SelfTest.player(server, name);
        if (player == null) return 0;
        CommandSourceStack source = server.createCommandSourceStack().withEntity(player)
                .withPosition(player.position());
        try
        {
            return server.getCommands().getDispatcher().execute(command, source);
        }
        catch (Exception e)
        {
            SelfTest.log(server, command + " threw " + e);
            return 0;
        }
    }

    /**
     * Reads the damage every bot's trace holds so far: what it dealt, and which of the others it was hit
     * by. A trace outlives the bot, so this still works once a match has taken the bots off.
     *
     * <p>This is asked for on every tick of a match, and a trace's own total only ever goes up, so what is
     * added each time is the difference against the last time it was asked: adding the running total on every
     * tick would count the same damage again and again and end up with tens of thousands of health points
     * for a match nobody ever dealt.</p>
     */
    private static void gather(List<String> bots, double[] dealt, double[] counted, Set<String>[] hurtBy)
    {
        for (int i = 0; i < bots.size(); i++)
        {
            CombatTrace fight = CombatTraces.fight(bots.get(i));
            if (fight == null) continue;
            double total = fight.damageDealt();
            // A trace that ended and a new one that has not been hit yet starts over, so a smaller total is
            // a fresh fight rather than damage that was taken back.
            dealt[i] += Math.max(0.0D, total - counted[i]);
            counted[i] = total;
            for (CombatTrace.Event event : fight.events())
            {
                if (event.kind() == CombatTrace.Kind.TAKEN && !event.other().isEmpty())
                {
                    hurtBy[i].add(event.other());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] sets(int count)
    {
        Set<String>[] out = new Set[count];
        for (int i = 0; i < count; i++)
        {
            out[i] = new LinkedHashSet<>();
        }
        return out;
    }

    private static boolean sameTeam(String bot, String other)
    {
        return RED.contains(bot) && RED.contains(other) || BLUE.contains(bot) && BLUE.contains(other);
    }

    /** The history names a side by its faction, so a team match records "red" or "blue". */
    private static boolean isTeam(String name)
    {
        return "red".equals(name) || "blue".equals(name);
    }

    /** Takes away any bot a match left behind, so the next scenario starts with a clean server. */
    private static void sweep(MinecraftServer server)
    {
        SelfTest.run(server, "bot match stop");
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player.getName().getString().matches("(Free|Red|Blue)\\d+"))
            {
                SelfTest.run(server, "player " + player.getName().getString() + " disconnect");
            }
        }
    }

    private static String join(double[] values)
    {
        StringBuilder out = new StringBuilder();
        for (double value : values)
        {
            if (out.length() > 0) out.append(' ');
            out.append(String.format(Locale.ROOT, "%.1f", value));
        }
        return out.toString();
    }

    private static String names(Set<String>[] sets)
    {
        StringBuilder out = new StringBuilder();
        for (Set<String> set : sets)
        {
            if (out.length() > 0) out.append(' ');
            out.append(set.size());
        }
        return out.toString();
    }
}