package carpet.pvp.selftest;

import carpet.pvp.BotBody;
import carpet.pvp.BotStats;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The fight a bot is pointed at before anything else: a target that stands still and never fights back. Every
 * level of the ladder has to close the distance and land a hit on its own, all five of them at once on their
 * own patch of ground, because a level that stands a few blocks off and looks at the target is a level nobody
 * can practise against.
 */
final class PassiveTargetScenarios
{
    /** The five presets of the ladder, easiest first. */
    private static final String[] LADDER = {"beginner", "casual", "average", "skilled", "expert"};
    /**
     * Ticks one bot of the ladder has to need before its first hit on a target that stands still. The five
     * presets land it in fourteen to forty ticks and a bot that needs hundreds of them to walk the last two
     * blocks is slow rather than stuck, which is what this is there to tell apart from a bot that never swings.
     */
    private static final int FIRST_HIT_TICKS = 500;
    /** Blocks between two pairs, so no two of them share a spot. */
    private static final double SPACING = 60.0D;
    /** How far off the target a pair starts, which is the distance the planner takes over at. */
    private static final double START = 4.6D;

    private PassiveTargetScenarios() {}

    /** One bot of the ladder and the target it has to reach on its own. */
    private static final class Pair
    {
        private final String level;
        private final String bot;
        private final String target;
        private final int[] firstHit = {-1};
        private final double[] closest = {START};
        private boolean armed;

        Pair(int index)
        {
            level = LADDER[index];
            bot = "SelfP" + index + "b";
            target = "SelfP" + index + "t";
        }

        /** Turns the bot into a sword fighter of its level and gives both sides the sword kit. */
        void arm(MinecraftServer server)
        {
            SelfTest.swordKit(bot).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordKit(target).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordCombat(bot, level).forEach(command -> SelfTest.run(server, command));
            armed = true;
        }

        /** Counts a tick of the fight, remembering the first hit and how close the bot ever got. */
        void tick(MinecraftServer server, int elapsed)
        {
            ServerPlayer fighter = SelfTest.player(server, bot);
            ServerPlayer dummy = SelfTest.player(server, target);
            if (fighter == null || dummy == null)
            {
                return;
            }
            closest[0] = Math.min(closest[0], fighter.distanceTo(dummy));
            BotBody body = SelfTest.body(server, bot);
            if (firstHit[0] < 0 && body != null && body.stats().hits >= 1)
            {
                firstHit[0] = elapsed;
            }
        }
    }

    /**
     * Every preset against a target in the same kit that stands still and gives nothing back: the bot has to
     * walk the last blocks itself and swing, at every level, and inside the same number of ticks.
     */
    static Scenario hitsPassiveTarget(String a, String b, String c, Vec3 origin)
    {
        List<Pair> pairs = new ArrayList<>();
        List<Bot> bots = new ArrayList<>();
        for (int index = 0; index < LADDER.length; index++)
        {
            Pair pair = new Pair(index);
            pairs.add(pair);
            double z = origin.z + index * SPACING;
            bots.add(new Bot(pair.bot, new Vec3(origin.x, SelfTest.SURFACE_Y, z)));
            bots.add(new Bot(pair.target, new Vec3(origin.x, SelfTest.SURFACE_Y, z + START)));
        }
        int[] elapsed = {0};
        List<String> warming = new ArrayList<>();
        for (Pair pair : pairs)
        {
            warming.add(pair.bot);
            warming.add(pair.target);
        }
        return new Scenario(FIRST_HIT_TICKS + 900, bots, List.of(), server ->
        {
            if (SelfTest.warmingUp(server, warming.toArray(new String[0])))
            {
                return SelfTest.pending("waiting for the five pairs to finish loading");
            }
            elapsed[0]++;
            for (Pair pair : pairs)
            {
                if (!pair.armed)
                {
                    pair.arm(server);
                }
                pair.tick(server, elapsed[0]);
            }
            Pair slowest = null;
            for (Pair pair : pairs)
            {
                if (pair.firstHit[0] < 0)
                {
                    slowest = pair;
                }
            }
            if (slowest != null)
            {
                return SelfTest.pending("waiting for " + LADDER.length + " first hits, "
                        + progress(server, pairs)
                        + SelfTest.fmt(", tick %d of %d", elapsed[0], FIRST_HIT_TICKS));
            }
            StringBuilder detail = new StringBuilder();
            int late = 0;
            for (int index = 0; index < pairs.size(); index++)
            {
                Pair pair = pairs.get(index);
                BotBody body = SelfTest.body(server, pair.bot);
                BotStats stats = body == null ? null : body.stats();
                detail.append(pair.level).append(" landed its first hit on tick ").append(pair.firstHit[0])
                        .append(SelfTest.fmt(" after running %.2f blocks", START - pair.closest[0]));
                if (stats != null)
                {
                    detail.append(", and had ").append(stats.clicks).append(" clicks of which ")
                            .append(stats.hits).append(" hit for ")
                            .append(SelfTest.fmt("%.1f", stats.damageDealt)).append(" damage");
                }
                if (pair.firstHit[0] > FIRST_HIT_TICKS)
                {
                    late++;
                }
                detail.append(index < pairs.size() - 1 ? "; " : "");
            }
            return new Probe(late == 0, detail.toString());
        });
    }

    /** Where the pairs that have not hit yet stand, so a timeout says which of them never got there. */
    private static String progress(MinecraftServer server, List<Pair> pairs)
    {
        StringBuilder out = new StringBuilder();
        for (Pair pair : pairs)
        {
            if (pair.firstHit[0] >= 0)
            {
                continue;
            }
            BotBody body = SelfTest.body(server, pair.bot);
            BotStats stats = body == null ? null : body.stats();
            out.append(out.length() > 0 ? ", " : "").append(pair.level)
                    .append(SelfTest.fmt(" %.2f blocks at its closest", pair.closest[0]));
            if (stats != null)
            {
                out.append(" with ").append(stats.clicks).append(" clicks of which ").append(stats.hits)
                        .append(" hit");
            }
        }
        return out.length() > 0 ? out.toString() : "every pair has hit";
    }
}