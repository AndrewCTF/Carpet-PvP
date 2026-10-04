package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotStats;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.style.SwordStyle;
import carpet.pvp.sim.DuelSim;
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
 *
 * <p>The bound is tight, and a bot that takes longer than it says what it was doing while it did not click: the
 * distance it was standing at, the action its planner had decided on, the charge and the swing count it
 * believed it had, whether the navigation controller or the planner had the movement, and what the opponent
 * model thought the target was about to do. A stall is a planner that keeps choosing not to close, and the only
 * way to tell that from a bot that is walking in at the speed a player walks is to say what it chose.</p>
 */
final class PassiveTargetScenarios
{
    /** The five presets of the ladder, easiest first. */
    private static final String[] LADDER = {"beginner", "casual", "average", "skilled", "expert"};
    /**
     * Ticks one bot of the ladder has to need before its first hit on a target that stands still.
     *
     * <p>Thirty-two runs of this scenario on the two versions, sixteen each, put the four easier presets on
     * tick 14 to 18 and the hardest on tick 24 to 30, so 60 is about twice the slowest of them. That leaves room
     * for what a preset spends before its first click can be charged at all: the four ticks of reaction delay of
     * the easiest level, and the eleven ticks its swing timer needs to reach the charge gate. A stall still
     * fails, and loudly: a bot stood off four blocks for six hundred ticks without a click once passed a bound
     * of 500, and now fails this at tick 120 with a note on what it was doing instead.</p>
     */
    private static final int FIRST_HIT_TICKS = 60;
    /** A stretch of this many ticks without a click is what the scenario calls a stall, and records it. */
    private static final int STALL_TICKS = 60;
    /** Blocks between two pairs, so no two of them share a spot. */
    private static final double SPACING = 60.0D;
    /** How far off the target a pair starts, which is the distance the planner takes over at. */
    private static final double START = 4.6D;

    private PassiveTargetScenarios() {}

    /**
     * What a preset was doing through a stretch of ticks it did not click: enough of it to say whether it was
     * walking in, standing off, or unable to bring its view onto the target.
     */
    private record Stall(int from, int to, double distance, double closest, int action, boolean navigating,
            float forward, float charge, int swing, int predicted)
    {
    }

    /** One bot of the ladder and the target it has to reach on its own. */
    private static final class Pair
    {
        private final String level;
        private final String bot;
        private final String target;
        private final int[] firstHit = {-1};
        private final double[] closest = {START};
        private final List<Stall> stalls = new ArrayList<>();
        /** The stretch of ticks without a click being watched, kept while it goes on and closed by a click. */
        private Stall open;
        private int lastClick;
        private int lastStall = -STALL_TICKS;
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

        /** Counts a tick of the fight: the first hit, how close the bot ever got, and any stretch without a click. */
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
            if (body == null)
            {
                return;
            }
            BotStats stats = body.stats();
            if (stats.hits >= 1 && firstHit[0] < 0)
            {
                firstHit[0] = elapsed;
            }
            if (stats.clicks > lastClick)
            {
                lastClick = stats.clicks;
                close();
                return;
            }
            if (elapsed - lastClick < STALL_TICKS || elapsed - lastStall < STALL_TICKS)
            {
                return;
            }
            lastStall = elapsed;
            EntityPlayerMPFake fake = (EntityPlayerMPFake) fighter;
            int predicted = fake.getBotBrain().style() instanceof SwordStyle sword
                    ? sword.predictedOpponent() : DuelSim.NOOP;
            open = new Stall(lastClick, elapsed, fighter.distanceTo(dummy), closest[0], body.lastAction(),
                    SelfTest.pack(server, bot).isNavEnabled(), fighter.zza,
                    fake.getBotBrain().perception().self().attackCharge,
                    fake.getBotBrain().perception().self().ticksSinceSwing, predicted);
        }

        /** Ends the stretch of ticks without a click, keeping what it looked like at the end of it. */
        void close()
        {
            if (open != null)
            {
                stalls.add(open);
                open = null;
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
        return new Scenario(FIRST_HIT_TICKS + 3 * STALL_TICKS + 120, bots, List.of(), server ->
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
            List<Pair> late = new ArrayList<>();
            for (Pair pair : pairs)
            {
                if (pair.firstHit[0] < 0 || pair.firstHit[0] > FIRST_HIT_TICKS)
                {
                    late.add(pair);
                }
            }
            if (!late.isEmpty())
            {
                for (Pair pair : pairs)
                {
                    pair.close();
                }
                return SelfTest.pending((late.size() == pairs.size() ? "no preset has hit yet"
                        : late.size() + " of " + pairs.size() + " presets are late") + ", at tick "
                        + elapsed[0] + " of " + FIRST_HIT_TICKS + ": " + progress(server, pairs) + stalls(pairs));
            }
            StringBuilder detail = new StringBuilder();
            for (int index = 0; index < pairs.size(); index++)
            {
                Pair pair = pairs.get(index);
                pair.close();
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
                detail.append(index < pairs.size() - 1 ? "; " : "");
            }
            return new Probe(true, detail + stalls(pairs));
        });
    }

    /** Where the presets that have not hit yet stand, so a timeout says which of them never got there. */
    private static String progress(MinecraftServer server, List<Pair> pairs)
    {
        StringBuilder out = new StringBuilder();
        for (Pair pair : pairs)
        {
            BotBody body = SelfTest.body(server, pair.bot);
            BotStats stats = body == null ? null : body.stats();
            out.append(out.length() > 0 ? ", " : "").append(pair.level).append(" ")
                    .append(pair.firstHit[0] < 0 ? "has not hit" : "hit on tick " + pair.firstHit[0]);
            if (stats != null)
            {
                out.append(" with ").append(stats.clicks).append(" clicks of which ").append(stats.hits)
                        .append(" hit");
            }
        }
        return out.toString();
    }

    /** What each preset was doing through every stretch of ticks it did not click. */
    private static String stalls(List<Pair> pairs)
    {
        StringBuilder out = new StringBuilder();
        for (Pair pair : pairs)
        {
            for (Stall stall : pair.stalls)
            {
                if (out.length() > 0)
                {
                    out.append("; ");
                }
                out.append(pair.level)
                        .append(SelfTest.fmt(" stood off from tick %d to %d", stall.from(), stall.to()))
                        .append(SelfTest.fmt(": %.2f blocks away at its closest of %.2f", stall.distance(),
                                stall.closest()))
                        .append(", the planner had ")
                        .append(SelfTest.fmt("forward %d, jump %s, sprint %s, click %s",
                                DuelSim.forward(stall.action()), onOff(DuelSim.jump(stall.action())),
                                onOff(DuelSim.sprint(stall.action())), onOff(DuelSim.attack(stall.action()))))
                        .append(stall.navigating() ? ", the navigation controller had the movement"
                                : ", the planner had the movement")
                        .append(SelfTest.fmt(", forward %.1f", stall.forward()))
                        .append(SelfTest.fmt(", charge %.2f at swing %d",
                                stall.charge(), stall.swing()))
                        .append(", the opponent model expected ")
                        .append(SelfTest.fmt("forward %d, sprint %s, click %s",
                                DuelSim.forward(stall.predicted()), onOff(DuelSim.sprint(stall.predicted())),
                                onOff(DuelSim.attack(stall.predicted()))));
            }
        }
        return out.length() > 0 ? ". Through every stretch of " + STALL_TICKS
                + " ticks or more without a click: " + out : ". No preset went " + STALL_TICKS
                + " ticks without a click";
    }

    private static String onOff(boolean value)
    {
        return value ? "on" : "off";
    }
}