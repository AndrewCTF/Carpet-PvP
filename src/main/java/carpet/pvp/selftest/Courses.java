package carpet.pvp.selftest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The courses a navigation scenario walks over, and waiting for one to be there.
 *
 * <p>A {@code fill} far from spawn on a Paper server answers "That position is not loaded" and writes
 * nothing, and writes the blocks a tick or two after the chunk is handed out, so a scenario asks for
 * its course again until the block the course is there to place reads. A Fabric server has the chunk
 * when the command is first issued, so the first asking is the only one and the scenario is on the
 * same tick there as it would be without this.</p>
 */
final class Courses
{
    /** How many ticks to wait between two askings for a course that has not arrived. */
    private static final int ASK_EVERY = 5;

    /** The tick each witness was last asked about, so a course is not written over and over. */
    private static final Map<BlockPos, Integer> ASKED = new HashMap<>();

    private Courses() {}

    /**
     * Asks for a course until the block it is there to place reads, and reports whether it is there.
     *
     * @param witness a position of the course whose block says the course has landed
     */
    static boolean laid(MinecraftServer server, List<String> course, BlockPos witness, Block block)
    {
        if (server.overworld().getBlockState(witness).is(block)) return true;
        int now = server.getTickCount();
        if (now - ASKED.getOrDefault(witness, now - ASK_EVERY) < ASK_EVERY) return false;
        ASKED.put(witness, now);
        for (String command : course)
        {
            SelfTest.run(server, command);
        }
        return false;
    }
}