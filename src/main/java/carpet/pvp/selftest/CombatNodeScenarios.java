package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotPvpConfig.Difficulty;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The combat nodes of CarpetLogic, run on a real bot: turning the combat AI on and off with a program, a fight
 * node that ends, an option that cannot be applied, an event the game reports, and a program that is stopped
 * in the middle of a fight.
 */
final class CombatNodeScenarios
{
    /** How close the target stands, so that the bot's style finds it in reach at once. */
    private static final double IN_REACH = 2.5D;

    private CombatNodeScenarios() {}

    /**
     * A program that turns the AI on waits until the target is hurt and turns it off again. What the bot does
     * while the node is active is the brain's business; afterwards it must stand still and hit nothing.
     */
    static Scenario combatStartStop(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, IN_REACH);
        int[] phase = {0};
        double[] lastX = {origin.x};
        int[] quiet = {0};
        int[] hitsWhenStopped = {-1};
        String program = """
                [{type: COMBAT_START, params: {style: sword, difficulty: expert, targets: bots}},
                 {type: WAIT_UNTIL, params: {timeout: 900},
                  condition: {type: CONDITION_TARGET_HEALTH, params: {operator: "<", value: 20}}},
                 {type: COMBAT_STOP},
                 {type: STOP_MOVEMENT}]""";
        return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, ahead)), List.of(), server ->
        {
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending("waiting for both bots to finish loading");
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is fighting " + b + " until it is hurt");
            }
            ServerPlayer bot = SelfTest.player(server, a);
            BotBody body = SelfTest.body(server, a);
            if (phase[0] == 1)
            {
                EntityPlayerMPFake fake = (EntityPlayerMPFake) bot;
                LivingEntity target = fake.getBotBrain().target();
                if (body == null || body.stats().hits < 1)
                {
                    return SelfTest.pending(SelfTest.fmt(
                            "waiting for %s to land a hit: combat %s, its target is %s at %.1f blocks, "
                                    + "%s has %.1f health and %d clicks were made",
                            a, fake.getPvpConfig().combat,
                            target == null ? "none" : target.getName().getString(),
                            target == null ? 0.0D : bot.distanceTo(target),
                            b, SelfTest.player(server, b).getHealth(), body == null ? 0 : body.stats().clicks));
                }
                if (!SelfTest.status(a).equals("COMPLETED"))
                {
                    return SelfTest.pending("waiting for the program to reach its end");
                }
                hitsWhenStopped[0] = body.stats().hits;
                lastX[0] = bot.getX();
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("the program completed after %d hits", hitsWhenStopped[0]));
            }
            double moved = Math.abs(bot.getX() - lastX[0]);
            lastX[0] = bot.getX();
            if (moved <= 0.01D) quiet[0]++;
            if (quiet[0] < 6) return SelfTest.pending(SelfTest.fmt("%s moved %.3f blocks after the fight", a, moved));
            EntityPlayerMPFake fake = (EntityPlayerMPFake) bot;
            boolean stillFighting = fake.getPvpConfig().combat || fake.getBotBrain().target() != null
                    || SelfTest.pack(server, a).isNavEnabled();
            int hits = body.stats().hits;
            boolean ok = !stillFighting && hits == hitsWhenStopped[0] && SelfTest.status(a).equals("COMPLETED");
            return new Probe(ok, SelfTest.fmt(
                    "%s landed %d hits, and %d ticks after COMBAT_STOP it holds no input, is not navigating, "
                            + "has no target and has not hit since (%d hits, combat %s)",
                    a, hitsWhenStopped[0], quiet[0], hits, fake.getPvpConfig().combat));
        });
    }

    /** A fight node ends when the target dies, and the node after it runs. */
    static Scenario fightNode(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, IN_REACH);
        int[] phase = {0};
        int[] ticks = {0};
        double[] lastX = {origin.x};
        int[] quiet = {0};
        String program = """
                [{type: FIGHT, params: {style: sword, difficulty: expert, targets: bots, timeout: 900, range: 24, rangeTicks: 100}},
                 {type: MOVE, params: {direction: forward, ticks: 20}}]""";
        return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, ahead)), List.of(), server ->
        {
            ticks[0]++;
            ServerPlayer bot = SelfTest.player(server, a);
            BotBody body = SelfTest.body(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending("waiting for both bots to finish loading");
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is waiting out a fight with " + b);
            }
            if (phase[0] == 1)
            {
                if (body == null || body.stats().hits < 1)
                {
                    return SelfTest.pending("waiting for " + a + " to land a hit");
                }
                // the target dies, which is what the fight node is waiting for
                if (SelfTest.result(server, "damage " + b + " 1000") < 1) return SelfTest.pending(b + " could not be killed yet");
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("killed %s in the middle of the fight", b));
            }
            if (phase[0] == 2)
            {
                if (!SelfTest.status(a).equals("COMPLETED"))
                {
                    return SelfTest.pending("the fight node is still going");
                }
                lastX[0] = bot.getX();
                phase[0] = 3;
                return SelfTest.pending(SelfTest.fmt("the fight ended and the program completed after %d ticks", ticks[0]));
            }
            double moved = Math.abs(bot.getX() - lastX[0]);
            lastX[0] = bot.getX();
            if (moved <= 0.01D) quiet[0]++;
            if (quiet[0] < 6) return SelfTest.pending(SelfTest.fmt("%s moved %.3f blocks after the program", a, moved));
            EntityPlayerMPFake fake = (EntityPlayerMPFake) bot;
            boolean ok = quiet[0] >= 6 && !fake.getPvpConfig().combat && body.stats().hits >= 1;
            return new Probe(ok, SelfTest.fmt(
                    "the fight node ended %d ticks into a 900 tick timeout after %d hits, the program carried on "
                            + "and came to rest with combat %s",
                    ticks[0], body.stats().hits, fake.getPvpConfig().combat));
        });
    }

    /** An option the bot takes is applied, and one it does not stops the program with the command's reason. */
    static Scenario combatOption(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, IN_REACH);
        int[] phase = {0};
        String program = """
                [{type: SET_COMBAT_OPTION, params: {key: difficulty, value: expert}},
                 {type: COMBAT_START, params: {targets: bots, difficulty: expert}},
                 {type: WAIT_UNTIL, params: {timeout: 600},
                  condition: {type: CONDITION_TARGET_HEALTH, params: {operator: "<", value: 20}}},
                 {type: SET_COMBAT_OPTION, params: {key: nosuchoption, value: "1"}},
                 {type: STOP_MOVEMENT}]""";
        return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, ahead)), List.of(), server ->
        {
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending("waiting for both bots to finish loading");
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is setting its combat options");
            }
            BotBody body = SelfTest.body(server, a);
            if (body == null || body.stats().hits < 1)
            {
                return SelfTest.pending("waiting for " + a + " to land a hit before it sets a bad option");
            }
            EntityPlayerMPFake bot = (EntityPlayerMPFake) SelfTest.player(server, a);
            BotPvpConfig cfg = bot.getPvpConfig();
            String error = error(a);
            boolean expert = cfg.difficulty == Difficulty.EXPERT;
            boolean refused = "ERROR".equals(SelfTest.status(a)) && error != null && error.contains("Unknown setting: nosuchoption");
            return new Probe(expert && refused, SelfTest.fmt(
                    "%s is at difficulty %s and its program stopped with %s", a, cfg.difficulty,
                    refused ? "\"" + error + "\"" : reason(a)));
        });
    }

    /** The game reports a kill, and the reaction the program registered for it runs. */
    static Scenario onKillEvent(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, IN_REACH);
        int[] phase = {0};
        int[] waited = {0};
        String program = """
                [{type: COMBAT_START, params: {style: sword, difficulty: beginner, targets: bots}},
                 {type: ON_EVENT, params: {event: when_kill},
                  children: [{type: SET_COMBAT_OPTION, params: {key: difficulty, value: expert}}]},
                 {type: LOOP, params: {count: 400}, children: [{type: DELAY, params: {ticks: 5}}]}]""";
        return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, ahead)), List.of(), server ->
        {
            EntityPlayerMPFake bot = (EntityPlayerMPFake) SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending("waiting for both bots to finish loading");
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is waiting with a when_kill handler");
            }
            if (phase[0] == 1)
            {
                if (bot.getBotBrain().target() == null)
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s to pick %s as its target", a, b));
                }
                if (SelfTest.result(server, "damage " + b + " 1000") < 1) return SelfTest.pending(b + " could not be killed yet");
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("killed the target of %s", a));
            }
            if (bot.getPvpConfig().difficulty != Difficulty.EXPERT && waited[0]++ < 10)
            {
                return SelfTest.pending("waiting for the when_kill handler to run");
            }
            return new Probe(bot.getPvpConfig().difficulty == Difficulty.EXPERT, SelfTest.fmt(
                    "after its target died %s is at difficulty %s, its program is %s",
                    a, bot.getPvpConfig().difficulty, SelfTest.status(a)));
        });
    }

    /** A totem of the bot's own pops, which is something only the game knows, and the handler runs. */
    static Scenario totemPopEvent(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        int[] waited = {0};
        String program = """
                [{type: ON_EVENT, params: {event: when_totem_pop},
                  children: [{type: SET_COMBAT_OPTION, params: {key: difficulty, value: expert}}]},
                 {type: LOOP, params: {count: 400}, children: [{type: DELAY, params: {ticks: 5}}]}]""";
        return new Scenario(1000, List.of(new Bot(a, origin)), List.of(), server ->
        {
            EntityPlayerMPFake bot = (EntityPlayerMPFake) SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending("waiting for the bot to finish loading");
                SelfTest.run(server, "give " + a + " minecraft:totem_of_undying");
                SelfTest.run(server, SelfTest.cmd(a + " equip offhand minecraft:totem_of_undying"));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is waiting with a when_totem_pop handler");
            }
            if (phase[0] == 1)
            {
                if (bot.getPvpConfig().difficulty == Difficulty.EXPERT)
                {
                    return SelfTest.pending("the handler ran before the totem was used");
                }
                if (SelfTest.result(server, "damage " + a + " 100") < 1) return SelfTest.pending(a + " could not be hurt yet");
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("hurt %s for more than it has health for", a));
            }
            if (bot.getPvpConfig().difficulty != Difficulty.EXPERT && waited[0]++ < 10)
            {
                return SelfTest.pending("waiting for the when_totem_pop handler to run");
            }
            return new Probe(bot.getPvpConfig().difficulty == Difficulty.EXPERT, SelfTest.fmt(
                    "its totem saved %s, which has %.1f health again, and its program is %s at difficulty %s",
                    a, bot.getHealth(), SelfTest.status(a), bot.getPvpConfig().difficulty));
        });
    }

    /** A program that is stopped while its bot is fighting leaves the bot standing, not fighting. */
    static Scenario stopProgramStopsFight(String a, String b, String c, Vec3 origin)
    {
        Vec3 ahead = origin.add(0.0D, 0.0D, IN_REACH);
        int[] phase = {0};
        int[] hitsWhenStopped = {-1};
        double[] lastX = {origin.x};
        int[] quiet = {0};
        String program = """
                [{type: COMBAT_START, params: {style: sword, difficulty: expert, targets: bots}},
                 {type: LOOP, params: {count: 400}, children: [{type: DELAY, params: {ticks: 5}}]}]""";
        return new Scenario(1000, List.of(new Bot(a, origin), new Bot(b, ahead)), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            BotBody body = SelfTest.body(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b)) return SelfTest.pending("waiting for both bots to finish loading");
                SelfTest.swordKit(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.startProgram(server, a, program);
                phase[0] = 1;
                return SelfTest.pending(a + " is fighting " + b + " inside a program that never ends");
            }
            if (phase[0] == 1)
            {
                if (body == null || body.stats().hits < 1)
                {
                    return SelfTest.pending("waiting for " + a + " to land a hit");
                }
                SelfTest.programStopper.accept(a);
                hitsWhenStopped[0] = body.stats().hits;
                lastX[0] = bot.getX();
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("stopped the program of %s after %d hits", a, hitsWhenStopped[0]));
            }
            double moved = Math.abs(bot.getX() - lastX[0]);
            lastX[0] = bot.getX();
            if (moved <= 0.01D) quiet[0]++;
            if (quiet[0] < 8) return SelfTest.pending(SelfTest.fmt("%s moved %.3f blocks after the stop", a, moved));
            EntityPlayerMPFake fake = (EntityPlayerMPFake) bot;
            LivingEntity target = fake.getBotBrain().target();
            boolean stillFighting = fake.getPvpConfig().combat || target != null
                    || SelfTest.pack(server, a).isNavEnabled() || body.stats().hits != hitsWhenStopped[0];
            return new Probe(!stillFighting && "gone".equals(SelfTest.status(a)), SelfTest.fmt(
                    "%d ticks after its program was stopped mid-fight %s holds no input, has no target (%s), "
                            + "is not navigating and is still at %d hits",
                    quiet[0], a, target == null ? "none" : target.getName().getString(), body.stats().hits));
        });
    }

    private static String error(String botName)
    {
        return SelfTest.programError.apply(botName);
    }

    private static String reason(String botName)
    {
        String error = error(botName);
        return error == null ? "no reason at all, it is " + SelfTest.status(botName) : "\"" + error + "\"";
    }
}