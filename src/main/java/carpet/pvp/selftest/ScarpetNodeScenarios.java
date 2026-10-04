package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The Scarpet node of a CarpetLogic program on a real server: who it runs for, what it can do to the world, and
 * how a program's variables go into a snippet and come back.
 */
final class ScarpetNodeScenarios
{
    private ScarpetNodeScenarios() {}

    /**
     * A program whose snippet sets a block three east of the bot and two up, changes a variable and gives a
     * number the program branches on. It is refused for a program nobody started, refused for a player who may
     * not use /script run, and runs for one who may, with the bot as the snippet's player and position.
     */
    static Scenario scarpetNode(String a, String b, String c, Vec3 origin)
    {
        BlockPos spot = BlockPos.containing(origin.add(3.0D, 2.0D, 0.0D));
        String place = spot.getX() + ", " + spot.getY() + ", " + spot.getZ();
        String program = """
                [{type: SET, params: {name: n, value: '6'}},
                 {type: SCARPET, params: {code: "set(x + 3, y + 2, z, 'gold_block'); n = n + 1; n * 6", result: answer}},
                 {type: SCARPET, params: {code: "query(p, 'name')", result: who}},
                 {type: IF, params: {condition: '$answer == 42'},
                  children: [{type: SET, params: {name: verdict, value: "$who + ' got ' + $answer + ' from n ' + $n"}}],
                  elseChildren: [{type: SET, params: {name: verdict, value: "'took the wrong branch'"}}]},
                 {type: IF_THEN_ELSE, condition: {type: CONDITION_SCARPET, params: {code: "block(%s) == 'gold_block'"}},
                  children: [{type: SET, params: {name: seen, value: "'gold'"}}]}]""".formatted(place);
        int[] phase = {0};
        String[] refusals = new String[2];
        return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 3.0D))), List.of(), server ->
        {
            switch (phase[0])
            {
                case 0:
                    // Nobody started this one: it has no player to run a snippet as.
                    SelfTest.startProgram(server, a, program);
                    phase[0] = 1;
                    return SelfTest.pending("a program without an owner meets its Scarpet node");
                case 1:
                    if (SelfTest.status(a).equals("RUNNING")) return SelfTest.pending("waiting for the program without an owner");
                    refusals[0] = SelfTest.status(a) + ": " + SelfTest.programError.apply(a);
                    // A player who is no operator, which is what commandScriptACE asks for.
                    SelfTest.run(server, "deop " + b);
                    SelfTest.pendingProgram(program);
                    SelfTest.programStarterAs.accept(a, b);
                    phase[0] = 2;
                    return SelfTest.pending(b + ", who is no operator, starts the program");
                case 2:
                    if (SelfTest.status(a).equals("RUNNING")) return SelfTest.pending("waiting for the program of " + b);
                    refusals[1] = SelfTest.status(a) + ": " + SelfTest.programError.apply(a);
                    if (!server.overworld().getBlockState(spot).isAir())
                        return done(server, b, spot, false, "a snippet that was refused changed the world all the same");
                    SelfTest.run(server, "op " + b);
                    SelfTest.pendingProgram(program);
                    SelfTest.programStarterAs.accept(a, b);
                    phase[0] = 3;
                    return SelfTest.pending(b + ", now an operator, starts the program");
                default:
                    if (SelfTest.status(a).equals("RUNNING")) return SelfTest.pending("waiting for the program of the operator " + b);
                    boolean refusedBoth = refusals[0].equals("ERROR: SCARPET only runs in programs a player started from the web editor")
                            && refusals[1].startsWith("ERROR: SCARPET needs " + b + " to be allowed /script run");
                    boolean placed = server.overworld().getBlockState(spot).is(Blocks.GOLD_BLOCK);
                    String verdict = SelfTest.programVariable.apply(a, "verdict");
                    String seen = SelfTest.programVariable.apply(a, "seen");
                    boolean branched = verdict.equals(a + " got 42 from n 7") && seen.equals("gold");
                    return done(server, b, spot, refusedBoth && placed && branched && SelfTest.status(a).equals("COMPLETED"), SelfTest.fmt(
                            "without an owner: '%s'; for %s before it was opped: '%s'; for the operator the program is %s%s, the block at %s is %s, "
                                    + "and it left '%s' and '%s'",
                            refusals[0], b, refusals[1], SelfTest.status(a),
                            SelfTest.status(a).equals("ERROR") ? " (" + SelfTest.programError.apply(a) + ")" : "",
                            place, placed ? "gold" : "not gold", verdict, seen));
            }
        });
    }

    // The operator and the block go back, whatever the outcome.
    private static Probe done(net.minecraft.server.MinecraftServer server, String operator, BlockPos spot, boolean ok, String detail)
    {
        SelfTest.run(server, "deop " + operator);
        SelfTest.run(server, SelfTest.setBlock(spot, "minecraft:air"));
        return new Probe(ok, detail);
    }
}
