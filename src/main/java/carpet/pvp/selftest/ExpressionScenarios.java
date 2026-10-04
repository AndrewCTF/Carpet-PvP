package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Expressions of a CarpetLogic program, run on a real bot: a while-loop whose condition measures the world, an
 * if inside it, and variables the program works out and leaves behind.
 */
final class ExpressionScenarios
{
    /** How far ahead of the bot the place it walks to is. */
    private static final double AHEAD = 6.0D;
    private static final int MOST_STEPS = 40;

    private ExpressionScenarios() {}

    /**
     * The bot walks five ticks at a time for as long as it is further than a block and a half from a place
     * ahead of it, counting its steps and the even ones among them, and then says what it did. What its
     * variables hold at the end has to be what the world shows.
     */
    static Scenario expressionIfWhile(String a, String b, String c, Vec3 origin)
    {
        Vec3 goal = origin.add(0.0D, 0.0D, AHEAD);
        String there = SelfTest.fmt("distance(%.1f, %.1f, %.1f)", goal.x, goal.y, goal.z);
        String program = """
                [{type: SET, params: {name: steps, value: '0'}},
                 {type: WHILE, params: {condition: '%s > 1.5 and $steps < %d'}, children: [
                   {type: IF, params: {condition: '$steps %% 2 == 0'}, children: [{type: SET, params: {name: even, value: '$even + 1'}}]},
                   {type: MOVE, params: {direction: forward, ticks: 5}},
                   {type: SET, params: {name: steps, value: '$steps + 1'}}]},
                 {type: STOP_MOVEMENT},
                 {type: SET, params: {name: left, value: 'round(%s * 10) / 10'}},
                 {type: SET, params: {name: verdict, value: "if($steps < %d, bot_name + ' arrived after ' + $steps + ' steps', 'gave up')"}}]"""
                .formatted(there, MOST_STEPS, there, MOST_STEPS);
        boolean[] started = {false};
        return new Scenario(600, List.of(new Bot(a, origin)), List.of(), server ->
        {
            if (!started[0])
            {
                SelfTest.startProgram(server, a, program);
                started[0] = true;
                return SelfTest.pending(a + " walks until it is close to the place ahead");
            }
            String status = SelfTest.status(a);
            if (status.equals("RUNNING")) return SelfTest.pending(a + " is at step " + SelfTest.programVariable.apply(a, "steps"));
            double steps = Double.parseDouble(SelfTest.programVariable.apply(a, "steps"));
            double even = Double.parseDouble(SelfTest.programVariable.apply(a, "even"));
            double left = Double.parseDouble(SelfTest.programVariable.apply(a, "left"));
            String verdict = SelfTest.programVariable.apply(a, "verdict");
            double really = SelfTest.player(server, a).position().distanceTo(goal);
            boolean counted = steps >= 3 && steps <= 12 && even == Math.ceil(steps / 2.0D);
            // The bot slides a little after its last step, so what it measured may be off by that much.
            boolean measured = left <= 1.5D && Math.abs(left - really) < 0.5D;
            boolean said = verdict.equals(SelfTest.fmt("%s arrived after %.0f steps", a, steps));
            return new Probe(status.equals("COMPLETED") && counted && measured && said, SelfTest.fmt(
                    "the program is %s: it took %.0f steps of which it counted %.0f as even, measured %.1f blocks left where the bot is "
                            + "%.2f from the place, and left the text '%s'%s",
                    status, steps, even, left, really, verdict,
                    status.equals("ERROR") ? ", stopped by: " + SelfTest.programError.apply(a) : ""));
        });
    }
}
