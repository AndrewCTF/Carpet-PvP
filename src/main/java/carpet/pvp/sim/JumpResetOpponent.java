package carpet.pvp.sim;

import java.util.Random;

/**
 * Jump reset: reads the opponent's swing coming off cooldown and jumps, so the fighter is already off the ground
 * when the hit lands. Being airborne drops the vertical part of the knockback and takes the ground friction away
 * on the way down, which is worth several points of health over a long exchange.
 *
 * <p>The read has to happen before the opponent is in range, not when it appears they are: their swing waits for
 * reach, so the charge clock runs on for however long it takes them to close, and a jump taken on the tick they
 * finally can reach is a tick too late.
 */
public final class JumpResetOpponent extends ScriptedOpponent
{
    private static final int LEAD_TICKS = 1;
    private static final int MIN_TICKS_BETWEEN_JUMPS = 6;

    private int lastJump = -100;

    public JumpResetOpponent(Random random, int reactionDelay)
    {
        super(random, reactionDelay);
    }

    @Override
    protected int decide(View view)
    {
        boolean armed = view.theirSwingAge >= view.theirGateTicks - LEAD_TICKS;
        boolean jump = armed && view.onGround && tick() - lastJump >= MIN_TICKS_BETWEEN_JUMPS;
        if (jump)
        {
            lastJump = tick();
        }
        return DuelSim.action(1, 0, jump, view.swingAge > 2, view.charged && view.inReach);
    }
}