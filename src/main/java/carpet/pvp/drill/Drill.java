package carpet.pvp.drill;

import carpet.pvp.BotPvpConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * One scripted practice for a player, with a bot standing in for an opponent.
 *
 * <p>A drill is written for the practice it measures rather than for a fight: it says what the player
 * needs to be able to do, it counts what the player managed, and it is over once that has been
 * measured or once its time is up. What it hands the player is taken back when it ends, and what it
 * puts in the world is put back too.</p>
 */
public interface Drill
{
    /** The name {@code /bot drill} takes. */
    String name();

    /** One line for {@code /bot drill list}. */
    String describe();

    /** The combat style the bot of this drill uses, which is also the kit it is given. */
    BotPvpConfig.CombatStyle style();

    /**
     * Null when this drill can run for this player as they are, otherwise why it cannot. The player
     * is not touched before this has been asked.
     */
    String unavailable(MinecraftServer server, ServerPlayer player);

    /** Sets the drill up: the bot it uses, what the player is given and where they start. */
    void begin(DrillRun run);

    /** One tick of the drill. */
    void tick(DrillRun run);

    /** True once the drill has measured what it was for, so it can end before its time is up. */
    boolean done(DrillRun run);

    /** Ticks the drill may take before it ends whatever happened. */
    int limit();

    /** The score line, shown on the action bar while the drill runs. */
    String score(DrillRun run);

    /** The line shown when the drill is over. */
    String summary(DrillRun run);
}