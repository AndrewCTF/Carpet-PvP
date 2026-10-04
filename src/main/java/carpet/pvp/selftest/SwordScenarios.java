package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.MatchHistory;
import carpet.pvp.BotStats;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * How well the sword bot fights: the share of its clicks that are worth something, the ladder of its
 * difficulty presets in the real game, the chase after a runner and the shield it carries in its offhand.
 */
final class SwordScenarios
{
    /** Ticks the damage scenario gives the expert bot to beat the target down. */
    private static final int DUEL_TICKS = 320;
    /** Health the armoured target has to be brought down to within that window. */
    private static final float DUEL_HEALTH = 8.0F;
    /** Share of the clicks the expert bot has to land. */
    private static final int DUEL_HIT_RATE = 80;
    /** Duels each rung of the ladder meets at. */
    private static final int LADDER_DUELS = 3;
    /** Duels of one rung the rung above it has to win to pass. */
    private static final int LADDER_WINS = 2;
    /** Ticks one duel of the ladder may take. */
    private static final int LADDER_TICKS = 380;
    /** Blocks between two duels, so no two of them share a spot. */
    private static final double LADDER_SPACING = 60.0D;
    /** Ticks the bot has to catch a runner that walks away in a straight line. */
    private static final int CATCH_TICKS = 300;
    /** Ticks the runner keeps walking away before it stops, so the bot has to catch it and then hit it. */
    private static final int RUNNER_TICKS = 150;
    /** Distance the runner starts off at, so the bot has to close a real gap to get to it. */
    private static final double CATCH_GAP = 12.0D;
    /** Ticks each half of the shield scenario runs for. */
    private static final int SHIELD_TICKS = 200;
    /** Ticks each phase of the settings scenario runs for. */
    private static final int SETTINGS_TICKS = 120;
    /** Phases the settings scenario walks through. */
    private static final int SETTINGS_PHASES = 5;
    /** Share of one half's damage the shielded bot has to save by holding its shield up. */
    private static final int SHIELD_SAVING = 15;

    /** The five presets of the ladder, easiest first. */
    private static final String[] LADDER = {"beginner", "casual", "average", "skilled", "expert"};

    private SwordScenarios() {}

    /**
     * An expert bot against a target in full diamond armour that walks up to it and then stands there
     * without ever fighting back. Everything the bot does with its sword shows in two numbers: the share
     * of its clicks that are worth a hit, and how far down the target is by the time it has to be.
     */
    static Scenario damageRate(String a, String b, String c, Vec3 origin)
    {
        Vec3 walker = origin.add(0.0D, 0.0D, -4.5D);
        boolean[] armed = {false};
        int[] tick = {0};
        float[] lowest = {20.0F};
        StringBuilder[] trace = {new StringBuilder()};
        return new Scenario(DUEL_TICKS + 200, List.of(new Bot(a, origin), new Bot(b, walker)), List.of(), server ->
        {
            ServerPlayer target = SelfTest.player(server, b);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(a, "expert").forEach(command -> SelfTest.run(server, command));
                // A fresh fake player still has the saturation it spawned with, which heals it back several
                // hearts within a second of every hit, so a dummy that is to be measured has to stop healing.
                SelfTest.run(server, "gamerule natural_health_regeneration false");
                // Beating the dummy brings it back at its own spawn point, so the bot has to be allowed to
                // walk after it again, as /bot duel does.
                SelfTest.run(server, "bot option " + a + " targetrange 64");
                SelfTest.run(server, SelfTest.cmd(b + " move forward for 20"));
                armed[0] = true;
                return SelfTest.pending(b + " walks up to " + a + " and then stands there");
            }
            BotBody body = SelfTest.body(server, a);
            if (body == null)
            {
                return SelfTest.pending("waiting for " + a + " to start fighting");
            }
            BotStats stats = body.stats();
            tick[0]++;
            // The dummy comes back at full health when the bot beats it, so what the run measures is the lowest
            // it ever got to rather than where it happened to be at the end.
            if (target.isAlive() && target.getHealth() < lowest[0])
            {
                lowest[0] = target.getHealth();
            }
            if (tick[0] < DUEL_TICKS)
            {
                if (tick[0] % 20 == 0)
                {
                    trace[0].append(SelfTest.fmt("%d:%.1f/%.1f,", tick[0],
                            SelfTest.player(server, a).distanceTo(target), target.getHealth()));
                }
                return SelfTest.pending(SelfTest.fmt("%d clicks of which %d hit (%d%%), the target is at %.1f,"
                                + " its lowest so far %.1f", stats.clicks, stats.hits, stats.hitRate(),
                        target.getHealth(), lowest[0]));
            }
            return new Probe(stats.hitRate() >= DUEL_HIT_RATE && lowest[0] <= DUEL_HEALTH,
                    SelfTest.fmt("%d clicks of which %d hit (%d%%); %d out of reach, %d off aim and %d uncharged;"
                                    + " %.1f damage over %d ticks took the armoured target down to %.1f health;"
                                    + " planner %d calls over %d simulated ticks, %d starved ticks, %d crits,"
                                    + " %d sprint hits; last clicks [%s]; every 20th tick [%s]",
                            stats.clicks, stats.hits, stats.hitRate(), stats.missesOutOfReach, stats.missesOffAim,
                            stats.missesUncharged, stats.damageDealt, tick[0], lowest[0], stats.plannerCalls,
                            stats.simulatedTicks, stats.starvedTicks, stats.crits, stats.sprintHits,
                            stats.clickLogLine(), trace[0]));
        });
    }


    /**
     * A target walking away in a straight line, which is the case the navigation used to lose: a bot that
     * matches the target's pace never catches it. It has to close the gap with its own legs and then swing.
     */
    static Scenario catchesRunner(String a, String b, String c, Vec3 origin)
    {
        Vec3 runner = origin.add(0.0D, 0.0D, -CATCH_GAP);
        boolean[] armed = {false};
        int[] caught = {-1};
        int[] sprintTicks = {0};
        int[] tick = {0};
        double[] closest = {CATCH_GAP};
        return new Scenario(CATCH_TICKS + 200, List.of(new Bot(a, origin), new Bot(b, runner)), List.of(), server ->
        {
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(a, "expert").forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "bot option " + a + " targetrange 96");
                SelfTest.run(server, SelfTest.cmd(b + " move forward for " + RUNNER_TICKS));
                armed[0] = true;
                return SelfTest.pending(b + " walks away from " + a + " in a straight line");
            }
            ServerPlayer hunter = SelfTest.player(server, a);
            ServerPlayer prey = SelfTest.player(server, b);
            double gap = hunter.distanceTo(prey);
            closest[0] = Math.min(closest[0], gap);
            if (hunter.isSprinting())
            {
                sprintTicks[0]++;
            }
            BotBody body = SelfTest.body(server, a);
            if (body == null)
            {
                return SelfTest.pending("waiting for " + a + " to start fighting");
            }
            if (caught[0] < 0 && body.stats().hits >= 2 && gap <= 3.5D)
            {
                caught[0] = tick[0];
            }
            tick[0]++;
            if (caught[0] < 0 && tick[0] < CATCH_TICKS)
            {
                return SelfTest.pending(SelfTest.fmt("%.1f blocks behind after %d ticks, sprinting %d of them,"
                        + " %d clicks of which %d hit", gap, tick[0], sprintTicks[0],
                        body.stats().clicks, body.stats().hits));
            }
            return new Probe(caught[0] >= 0,
                    SelfTest.fmt("caught a target walking away %d ticks after it started, having closed to %.1f"
                                    + " blocks, sprinting %d of %d ticks and landing %d of its %d clicks",
                            caught[0], closest[0], sprintTicks[0], tick[0], body.stats().hits,
                            body.stats().clicks));
        });
    }

    /**
     * A bot with a shield in its off hand against an attacker of the same difficulty, run twice: once with
     * shield play on and once with it off. Both halves have to land hits, so the only thing that differs is
     * whether the shield comes up in front of an incoming one.
     */
    static Scenario shieldPlay(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        int[] tick = {0};
        double[] taken = {0.0D, 0.0D};
        int[] upTicks = {0, 0};
        int[] guarded = {0, 0};
        int[] hits = {0, 0};
        /** Ticks the shield has been up since the last hit came in, which is the wind-up it went up for. */
        int[] held = {0, 0};
        boolean[] armed = {false};
        return new Scenario(2 * SHIELD_TICKS + 500, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 3.0D))),
                List.of(), server ->
        {
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                // autoTotem would put a totem in the off hand instead of the shield that has to go up.
                SelfTest.shieldKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "bot option " + a + " autototem false");
                SelfTest.swordCombat(a, "skilled").forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(b, "skilled").forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "gamerule natural_health_regeneration false");
                SelfTest.run(server, "bot duel " + a + " " + b);
                armed[0] = true;
                return SelfTest.pending(a + " is holding its shield up against " + b);
            }
            ServerPlayer shield = SelfTest.player(server, a);
            BotBody body = SelfTest.body(server, a);
            if (body == null)
            {
                return SelfTest.pending("waiting for " + a + " to start fighting");
            }
            BotStats stats = body.stats();
            boolean up = shield.isBlocking();
            if (up)
            {
                upTicks[phase[0]]++;
            }
            if (stats.damageTaken > taken[phase[0]])
            {
                if (held[phase[0]] > 0)
                {
                    // The shield was up somewhere between the last hit and this one, so it was up in front of
                    // this one: that is the whole point of putting it up before the swing lands.
                    guarded[phase[0]]++;
                }
                held[phase[0]] = 0;
            }
            if (up)
            {
                held[phase[0]]++;
            }
            taken[phase[0]] = stats.damageTaken;
            hits[phase[0]] = stats.hits;
            tick[0]++;
            if (tick[0] < SHIELD_TICKS)
            {
                return SelfTest.pending(SelfTest.fmt("half %d at tick %d: shield up on %d ticks, %.1f damage taken,"
                                + " %d hits landed", phase[0] + 1, tick[0], upTicks[phase[0]],
                        taken[phase[0]], hits[phase[0]]));
            }
            if (phase[0] == 0)
            {
                double first = taken[0];
                phase[0] = 1;
                tick[0] = 0;
                taken[0] = first;
                // The same fight with shield play switched off: the shield is still in the off hand, the bot
                // simply never brings it up.
                SelfTest.run(server, "bot option " + a + " shieldplay false");
                SelfTest.player(server, a).setHealth(20.0F);
                SelfTest.player(server, b).setHealth(20.0F);
                SelfTest.run(server, "bot duel " + a + " " + b);
                return SelfTest.pending("shield play off, the same duel restarts");
            }
            double first = taken[0];
            double second = taken[1];
            double saved = 1.0D - first / Math.max(1.0E-6D, second);
            return new Probe(upTicks[0] >= 10 && guarded[0] > 0 && upTicks[1] == 0 && second > 0.0D
                            && first > 0.0D && first <= second * 0.8D,
                    SelfTest.fmt("with shield play the bot took %.1f damage over %d ticks with its shield up on %d"
                                    + " of them and %d of its hits met the shield; the same duel against the same"
                                    + " bot with shield play off, which never brought the shield up on a single"
                                    + " tick, took %.1f, so the shield saved %d%%; holding it up is what cost"
                                    + " the bot its own hits, %d against %d",
                            first, SHIELD_TICKS, upTicks[0], guarded[0], second,
                            (int) Math.round(saved * 100.0D), hits[0], hits[1]));
        });
    }


    /**
     * The settings only the sword style reads that a scenario can see: which weapon it picks out of the hotbar
     * and whether it bunny-hops while it closes. One bot walks through both of them in turn.
     */
    static Scenario swordSettings(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        int[] tick = {0};
        int[] held = {-1, -1, -1};
        int[] airborne = {0, 0};
        boolean[] armed = {false};
        return new Scenario(SETTINGS_PHASES * SETTINGS_TICKS + 600,
                List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 6.0D))), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (!armed[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending("waiting for " + a + " and " + b + " to finish loading");
                }
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                // A diamond sword in the slot it starts in and a netherite axe two along: an axe hits for
                // nine and a diamond sword for seven, so the two settings disagree about which is the weapon.
                SelfTest.run(server, "give " + a + " minecraft:netherite_axe");
                SelfTest.swordCombat(a, "skilled").forEach(command -> SelfTest.run(server, command));
                // A planner range this small and a target that keeps walking away leave the bot closing the
                // distance for the whole run, which is what the weapon choice and the bunny hop are about.
                SelfTest.run(server, "bot option " + a + " plannerrange 2");
                SelfTest.run(server, "bot option " + a + " targetrange 96");
                SelfTest.run(server, SelfTest.cmd(b + " move forward for 4000"));
                armed[0] = true;
                return SelfTest.pending(a + " has a diamond sword and a netherite axe in its hotbar");
            }
            BotBody body = SelfTest.body(server, a);
            if (body == null)
            {
                return SelfTest.pending("waiting for " + a + " to start fighting");
            }
            tick[0]++;
            if (!bot.onGround())
            {
                airborne[0]++;
            }
            if (tick[0] < SETTINGS_TICKS)
            {
                return SelfTest.pending(SelfTest.fmt("phase %d at tick %d: holding slot %d, off the ground %d ticks",
                        phase[0], tick[0], bot.getInventory().getSelectedSlot(), airborne[0]));
            }
            int slot = bot.getInventory().getSelectedSlot();
            switch (phase[0])
            {
                // 0: autoWeapon off, so the sword it was given is the weapon.
                case 0 -> {
                    held[0] = slot;
                    SelfTest.run(server, "bot option " + a + " autoweapon true");
                    SelfTest.run(server, "bot option " + a + " prefersword true");
                    next(phase, tick, 1);
                    return SelfTest.pending("autoWeapon on with preferSword on, held slot " + slot);
                }
                // 1: autoWeapon on but preferSword on as well, which is still the sword.
                case 1 -> {
                    held[1] = slot;
                    SelfTest.run(server, "bot option " + a + " prefersword false");
                    next(phase, tick, 2);
                    return SelfTest.pending("preferSword off as well, held slot " + slot);
                }
                // 2: autoWeapon on and no sword preference, so the axe that hits harder wins.
                case 2 -> {
                    held[2] = slot;
                    SelfTest.run(server, "bot option " + a + " prefersword true");
                    SelfTest.run(server, "bot option " + a + " bhop true");
                    next(phase, tick, 3);
                    return SelfTest.pending("preferSword back on and bunny-hopping on, held slot " + slot);
                }
                // 3: bunny-hopping on, so the bot leaves the ground while it closes.
                case 3 -> {
                    next(phase, tick, 4);
                    SelfTest.run(server, "bot option " + a + " bhop false");
                    return SelfTest.pending("bunny-hopping left it off the ground on " + airborne[0]
                            + " ticks, now off");
                }
                // 4: no bunny-hopping, so it stays on the ground.
                default -> {
                    int axe = BotBody.findSlot(bot, Items.NETHERITE_AXE);
                    BotStats stats = body.stats();
                    return new Probe(held[0] == held[1] && held[2] == axe && axe >= 0
                                    && airborne[0] > 0 && airborne[1] < airborne[0],
                            SelfTest.fmt("with autoWeapon off it held slot %d, with it on and preferSword on slot"
                                            + " %d, and with preferSword off slot %d, where the netherite axe is"
                                            + " at %d; bunny-hopping left it off the ground on %d ticks and walking"
                                            + " without it on %d, over %d clicks of which %d missed",
                                    held[0], held[1], held[2], axe, airborne[0], airborne[1], stats.clicks,
                                    stats.misses));
                }
            }
        });
    }

    /** Moves a scenario on to its next phase, with its tick count from the start again. */
    private static void next(int[] phase, int[] tick, int to)
    {
        phase[0] = to;
        tick[0] = 0;
    }

    /** One duel of the ladder: {@link #rung} above beats {@code LADDER[rung - 1]}, a majority of the time. */
    private static final class LadderDuel
    {
        private final int rung;
        private final String weaker;
        private final String stronger;
        private final int[] ticks = new int[1];
        private boolean armed;
        private boolean finished;
        private int wins;

        LadderDuel(int rung, int index, double z)
        {
            this.rung = rung;
            this.weaker = "SelfL" + rung + index + "w";
            this.stronger = "SelfL" + rung + index + "s";
        }

        void arm(MinecraftServer server)
        {
            SelfTest.swordKit(weaker).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordKit(stronger).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordCombat(weaker, LADDER[rung - 1]).forEach(command -> SelfTest.run(server, command));
            SelfTest.swordCombat(stronger, LADDER[rung]).forEach(command -> SelfTest.run(server, command));
            SelfTest.run(server, "bot duel " + weaker + " " + stronger);
            armed = true;
        }

        /**
         * Counts a tick of the duel. Returns true once it is over, which it is when one of the two is
         * down or when it has run out of time; a duel the stronger one survives is a duel it won.
         */
        boolean tick(MinecraftServer server)
        {
            ServerPlayer weak = SelfTest.player(server, weaker);
            ServerPlayer strong = SelfTest.player(server, stronger);
            ticks[0]++;
            boolean over = weak == null || strong == null || weak.isDeadOrDying() || strong.isDeadOrDying()
                    || ticks[0] > LADDER_TICKS;
            if (over && weak != null && strong != null && !weak.isDeadOrDying())
            {
                wins++;
            }
            return over;
        }
    }

    /**
     * Every preset against the one below it in the game itself: the same kit on both sides and a majority
     * of the duels for the one above. All the duels run at once on their own patch of ground, so the ladder
     * costs the time of its longest duel rather than the sum of all of them.
     */
    static Scenario ladder(String a, String b, String c, Vec3 origin)
    {
        List<LadderDuel> duels = new ArrayList<>();
        List<Bot> bots = new ArrayList<>();
        for (int rung = 1; rung < LADDER.length; rung++)
        {
            for (int index = 0; index < LADDER_DUELS; index++)
            {
                double z = (rung * LADDER_DUELS + index) * LADDER_SPACING;
                duels.add(new LadderDuel(rung, index, z));
                bots.add(new Bot("SelfL" + rung + index + "w", new Vec3(origin.x, SelfTest.SURFACE_Y, z)));
                bots.add(new Bot("SelfL" + rung + index + "s", new Vec3(origin.x, SelfTest.SURFACE_Y, z + 3.0D)));
            }
        }
        int[] done = {0};
        return new Scenario(LADDER_TICKS + 900, bots, List.of(), server ->
        {
            for (LadderDuel duel : duels)
            {
                if (SelfTest.warmingUp(server, duel.weaker, duel.stronger))
                {
                    return SelfTest.pending("waiting for the bots of " + LADDER[duel.rung] + " to load");
                }
            }
            LadderDuel running = null;
            for (LadderDuel duel : duels)
            {
                if (!duel.armed)
                {
                    duel.arm(server);
                }
                if (duel.finished)
                {
                    continue;
                }
                if (duel.tick(server))
                {
                    duel.finished = true;
                    done[0]++;
                }
                else if (running == null)
                {
                    running = duel;
                }
            }
            if (done[0] < duels.size())
            {
                return SelfTest.pending(SelfTest.fmt("%d of %d duels over; %s %.1f health against %s %.1f",
                        done[0], duels.size(), LADDER[running.rung], health(server, running.stronger),
                        LADDER[running.rung - 1], health(server, running.weaker)));
            }
            StringBuilder detail = new StringBuilder();
            int unheld = 0;
            for (int rung = 1; rung < LADDER.length; rung++)
            {
                int wins = 0;
                for (int index = 0; index < LADDER_DUELS; index++)
                {
                    wins += duels.get((rung - 1) * LADDER_DUELS + index).wins;
                }
                detail.append(LADDER[rung]).append(" beat ").append(LADDER[rung - 1]).append(' ').append(wins)
                        .append('/').append(LADDER_DUELS).append(rung < LADDER.length - 1 ? ", " : "");
                if (wins < LADDER_WINS)
                {
                    unheld++;
                }
            }
            // Every duel that was decided left a result behind, which is what the web panel shows.
            int recorded = MatchHistory.matches().size();
            return new Probe(unheld == 0 && recorded > 0,
                    detail + "; " + recorded + " fights have been recorded so far this run");
        });
    }

    private static float health(net.minecraft.server.MinecraftServer server, String name)
    {
        ServerPlayer bot = SelfTest.player(server, name);
        return bot == null ? -1.0F : bot.getHealth();
    }
}