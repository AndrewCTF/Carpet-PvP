package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.kit.Kit;
import carpet.pvp.kit.KitInventory;
import carpet.pvp.kit.KitStore;
import carpet.pvp.style.StyleIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A match between bots: everybody against everybody, or two teams through the faction registry.
 *
 * <p>A match uses the spot the caller stands in: the bots are placed in a ring around it, given the
 * kit of the mode, turned into fighters and put into factions so that they fight the right people.
 * A free-for-all gives every bot a faction of its own and a team match gives each team one, so the
 * bots of a team refuse to hit their own side. A player who hits one is a legitimate target; a player
 * who joins a side with {@code /bot match join} is put into that faction instead.</p>
 *
 * <p>A match ends when one side is left standing or when it has run out of time, and either way the
 * result goes to {@link MatchHistory}, is announced and every bot of the match is taken off.</p>
 */
public final class MatchManager
{
    public enum Mode
    {
        /** Every bot fights every other bot, and any player nearby that hits one. */
        FFA,
        /** Two teams of bots, which never target each other. */
        TEAMS
    }

    /** Ticks a match may take before it is decided on the damage that was dealt. */
    public static final int TIME_LIMIT_TICKS = 1200;
    /** How far from the caller the bots are placed. */
    private static final double RING = 6.0D;
    /** How far a bot of a match looks for somebody to fight. */
    private static final double FIGHT_RANGE = 48.0D;
    /** Bots per side a match may be given. */
    private static final int MIN_BOTS = 2;
    private static final int MAX_BOTS = 16;

    /** One fighter of a match, a bot of the ring or a player who joined a team. */
    private static final class Fighter
    {
        private final String name;
        private final UUID id;
        private final String side;
        private final String faction;
        private final boolean bot;
        private float health = Float.NaN;
        /** The damage this fighter dealt inside this match. */
        private double counted;
        private double lastDealt;
        private boolean out;

        private Fighter(String name, UUID id, String side, String faction, boolean bot)
        {
            this.name = name;
            this.id = id;
            this.side = side;
            this.faction = faction;
            this.bot = bot;
        }
    }

    private static final class Match
    {
        private final MinecraftServer server;
        private final Mode mode;
        private final BotPvpConfig.CombatStyle style;
        private final String difficulty;
        private final long startedAt;
        private final List<Fighter> fighters = new ArrayList<>();
        private final Map<String, List<String>> teams = new LinkedHashMap<>();
        private final List<UUID> joined = new ArrayList<>();

        private Match(MinecraftServer server, Mode mode, BotPvpConfig.CombatStyle style, String difficulty)
        {
            this.server = server;
            this.mode = mode;
            this.style = style;
            this.difficulty = difficulty;
            this.startedAt = server.overworld().getGameTime();
        }

        private long ticks()
        {
            return Math.max(0L, server.overworld().getGameTime() - startedAt);
        }
    }

    private static Match match;

    private MatchManager()
    {
    }

    public static boolean active()
    {
        return match != null;
    }

    /** What the running match is, in one line. */
    public static String status()
    {
        if (match == null)
        {
            return "no match is running";
        }
        int standing = 0;
        for (Fighter fighter : match.fighters)
        {
            if (!fighter.out) standing++;
        }
        return String.format(Locale.ROOT,
                "%s match of %d bots fighting with %s at %s, %d still standing, %d of %d ticks left",
                match.mode.name().toLowerCase(Locale.ROOT), match.fighters.size(), match.style,
                match.difficulty, standing, Math.max(0, TIME_LIMIT_TICKS - match.ticks()), TIME_LIMIT_TICKS);
    }

    /**
     * Starts a match around the caller.
     *
     * @param count how many bots, and for a team match how many per team
     * @return null on success, otherwise the reason the match could not start
     */
    public static String start(MinecraftServer server, ServerPlayer caller, Mode mode, int count,
            BotPvpConfig.CombatStyle style, String difficulty)
    {
        if (match != null)
        {
            return "a match is already running: " + status();
        }
        if (count < MIN_BOTS || count > MAX_BOTS)
        {
            return "a match needs between " + MIN_BOTS + " and " + MAX_BOTS + " bots per side, not " + count;
        }
        String kitName = StyleIndex.kit(style);
        if (kitName == null || KitStore.of(server).get(kitName).isEmpty())
        {
            return "there is no kit for the " + style + " style";
        }
        int total = mode == Mode.FFA ? count : count * 2;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < total; i++)
        {
            String name = freeName(server, wanted(mode, i, count));
            if (name == null)
            {
                return "there is no free name left for the bots of this match";
            }
            names.add(name);
        }

        Match started = new Match(server, mode, style, difficulty);
        Vec3 centre = caller.position();
        ResourceKey<Level> dimension = caller.level().dimension();
        Kit kit = KitStore.of(server).get(kitName).orElse(null);
        for (int i = 0; i < total; i++)
        {
            String faction = mode == Mode.FFA ? "ffa_" + names.get(i) : teamOf(i / count);
            EntityPlayerMPFake bot = spawn(server, names.get(i), ringSpot(centre, total, i), caller, dimension);
            if (bot == null)
            {
                clear(started);
                return "could not spawn " + names.get(i);
            }
            if (kit != null)
            {
                KitInventory.apply(bot, kit, server.registryAccess());
            }
            Fighter fighter = new Fighter(names.get(i), bot.getUUID(),
                    mode == Mode.FFA ? names.get(i) : faction, faction, true);
            started.fighters.add(fighter);
            started.teams.computeIfAbsent(fighter.side, side -> new ArrayList<>()).add(fighter.name);
            configure(bot, style, difficulty, faction);
        }
        match = started;
        announce(server, Component.literal("A " + mode.name().toLowerCase(Locale.ROOT) + " match of " + total
                + " bots fighting with " + style + " at " + difficulty + " has started"));
        return null;
    }

    /**
     * Puts a player on a side of a team match: the bots of that side leave them alone and the bots of
     * the other side fight them.
     *
     * @return null on success, otherwise the reason it could not be done
     */
    public static String join(ServerPlayer player, String team)
    {
        if (match == null || match.mode != Mode.TEAMS)
        {
            return "there is no team match to join";
        }
        String side = team.toLowerCase(Locale.ROOT);
        if (!match.teams.containsKey(side))
        {
            return "there is no team called " + team + " in this match; the teams are " + match.teams.keySet();
        }
        if (player instanceof EntityPlayerMPFake)
        {
            return "a bot cannot join a team";
        }
        FactionManager.join(side, player.getUUID());
        match.joined.add(player.getUUID());
        return null;
    }

    /** Takes the bots of the running match off the server without deciding a winner. */
    public static String stop(MinecraftServer server)
    {
        if (match == null)
        {
            return "there is no match to stop";
        }
        String before = status();
        clear(match);
        match = null;
        announce(server, Component.literal("The match was stopped: " + before));
        return null;
    }

    /** One tick of a match: what every bot is doing, and whether the match is over. */
    public static void tick(MinecraftServer server)
    {
        if (match == null) return;
        for (Fighter fighter : match.fighters)
        {
            ServerPlayer player = server.getPlayerList().getPlayer(fighter.id);
            if (player == null)
            {
                fighter.out = true;
                continue;
            }
            float health = player.getHealth();
            // A bot that dies is put back with full health on the next tick, but for the tick it died
            // in it sits at the single health point a fake player's death leaves it, with no hurt time
            // on it, which is what tells a death from a hit that happened to leave one heart.
            if (!fighter.out && health <= 1.0F && health < fighter.health && player.hurtTime == 0)
            {
                fighter.out = true;
            }
            fighter.health = health;
            if (fighter.bot && !fighter.out)
            {
                double dealt = dealtOf(server, fighter);
                if (dealt > fighter.lastDealt)
                {
                    fighter.counted += dealt - fighter.lastDealt;
                }
                fighter.lastDealt = dealt;
            }
        }
        Set<String> sides = standingSides();
        if (sides.size() > 1 && match.ticks() < TIME_LIMIT_TICKS) return;
        conclude(server, sides.size() == 1 ? sides.iterator().next() : null);
    }

    /** The sides that still have a fighter on their feet. */
    private static Set<String> standingSides()
    {
        Set<String> sides = new LinkedHashSet<>();
        for (Fighter fighter : match.fighters)
        {
            if (!fighter.out) sides.add(fighter.side);
        }
        return sides;
    }

    /** Everyone still standing, most damage first. */
    private static List<Fighter> byDamage()
    {
        List<Fighter> standing = new ArrayList<>();
        for (Fighter fighter : match.fighters)
        {
            if (!fighter.out) standing.add(fighter);
        }
        standing.sort((a, b) -> Double.compare(b.counted, a.counted));
        return standing;
    }

    /** The health a side dealt, which is what its own members' counters add up to. */
    private static double sideDamage(String side)
    {
        double total = 0.0;
        for (Fighter fighter : match.fighters)
        {
            if (fighter.side.equals(side)) total += fighter.counted;
        }
        return total;
    }

    private static double dealtOf(MinecraftServer server, Fighter fighter)
    {
        if (server.getPlayerList().getPlayer(fighter.id) instanceof EntityPlayerMPFake bot)
        {
            BotBody body = bot.getBotBrain().body();
            if (body != null) return body.stats().damageDealt;
        }
        return 0.0;
    }

    /**
     * Records the result, announces it and takes every bot of the match off. A match that ran out of
     * time with more than one side left is won by whoever dealt the most damage.
     */
    private static void conclude(MinecraftServer server, String winner)
    {
        List<Fighter> order = byDamage();
        String taken = winner;
        if (taken == null && !order.isEmpty())
        {
            taken = order.get(0).side;
        }
        String loser = "nobody";
        for (Fighter fighter : order)
        {
            if (!fighter.side.equals(taken))
            {
                loser = fighter.side;
                break;
            }
        }
        long ticks = match.ticks();
        double winnerDamage = taken == null ? 0.0 : sideDamage(taken);
        double loserDamage = loser.equals("nobody") ? 0.0 : sideDamage(loser);
        clear(match);
        match = null;
        if (taken == null)
        {
            announce(server, Component.literal("The match ended with nobody left standing"));
            return;
        }
        MatchHistory.record(new MatchHistory.Match(taken, loser, taken, ticks, winnerDamage, loserDamage));
        announce(server, Component.literal("The match is over: " + taken + " won with "
                + oneDecimal(winnerDamage) + " damage to " + oneDecimal(loserDamage)
                + " after " + ticks + " ticks"));
    }

    /** Takes the bots off the server and the joined players out of their factions. */
    private static void clear(Match finished)
    {
        for (Fighter fighter : finished.fighters)
        {
            if (!fighter.bot) continue;
            if (finished.server.getPlayerList().getPlayer(fighter.id) instanceof EntityPlayerMPFake bot)
            {
                bot.getPvpConfig().combat = false;
                bot.fakePlayerDisconnect(Component.literal("The match is over"));
            }
            FactionManager.leave(fighter.id);
            if (fighter.faction != null) FactionManager.delete(fighter.faction);
        }
        for (UUID player : finished.joined)
        {
            FactionManager.leave(player);
        }
    }

    private static void configure(EntityPlayerMPFake bot, BotPvpConfig.CombatStyle style, String difficulty,
            String faction)
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        cfg.combatStyle = style;
        cfg.combat = true;
        cfg.autoTarget = true;
        cfg.targetBots = true;
        cfg.targetPlayers = true;
        cfg.targetRange = FIGHT_RANGE;
        cfg.meleeRange = 3.0;
        // A bot that keeps its own totem up never goes down, so the match would never end.
        cfg.autoTotem = false;
        cfg.autoShield = false;
        cfg.applyDifficulty(difficulty);
        cfg.faction = faction;
        FactionManager.create(faction);
        FactionManager.join(faction, bot.getUUID());
    }

    private static EntityPlayerMPFake spawn(MinecraftServer server, String name, Vec3 spot, ServerPlayer caller,
            ResourceKey<Level> dimension)
    {
        float yaw = (float) (Math.toDegrees(Math.atan2(caller.getZ() - spot.z, caller.getX() - spot.x)) - 90.0D);
        if (!EntityPlayerMPFake.createFake(name, server, server.createCommandSourceStack(), spot, yaw, 0.0D,
                dimension, GameType.SURVIVAL, false))
        {
            return null;
        }
        return server.getPlayerList().getPlayerByName(name) instanceof EntityPlayerMPFake bot ? bot : null;
    }

    private static Vec3 ringSpot(Vec3 centre, int count, int index)
    {
        double angle = 2.0 * Math.PI * index / count;
        return new Vec3(centre.x + RING * Math.cos(angle), centre.y, centre.z + RING * Math.sin(angle));
    }

    private static String teamOf(int side)
    {
        return side == 0 ? "red" : "blue";
    }

    private static String wanted(Mode mode, int index, int perSide)
    {
        if (mode == Mode.FFA)
        {
            return "Free" + (index + 1);
        }
        String team = teamOf(index / perSide);
        return Character.toUpperCase(team.charAt(0)) + team.substring(1) + (index % perSide + 1);
    }

    /** The first name of that form that nobody is using, with a number on the end if need be. */
    private static String freeName(MinecraftServer server, String wanted)
    {
        for (int suffix = 0; suffix < 100; suffix++)
        {
            String name = suffix == 0 ? wanted : wanted + suffix;
            if (server.getPlayerList().getPlayerByName(name) == null
                    && !EntityPlayerMPFake.isSpawningPlayer(name))
            {
                return name;
            }
        }
        return null;
    }

    private static void announce(MinecraftServer server, Component message)
    {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }

    private static String oneDecimal(double value)
    {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}