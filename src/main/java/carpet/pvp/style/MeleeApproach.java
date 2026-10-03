package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.Perception;
import carpet.pvp.nav.DirectSteer;
import carpet.pvp.nav.LevelWalkability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Closing the distance to a target a sword has to be swung at.
 *
 * <p>While the walk over is clear the bot drives itself, because that is what actually closes ground: forward,
 * sprinting, and sprint-jumping when bunny-hopping is on, which is what lets it catch a target that walks away
 * instead of matching its pace forever. The instant something is in the way the walk is handed to the
 * navigation controller, in chase mode with the attack left off, so the navigation never swings at anything: a
 * click only ever happens because the style asked the body for one and the body's own reach and aim gate let it
 * through. The body has already turned the view onto the target by the time this runs, so the navigation only
 * has to walk.</p>
 */
final class MeleeApproach
{
    /** Ticks between looks at whether the target can still be walked to in a straight line. */
    private static final int RECHECK_TICKS = 3;
    /** Ticks between jumps while bunny-hopping, which is what it takes to land and go again. */
    private static final int HOP_TICKS = 11;
    /** Ticks of sprinting before the first jump, long enough to be at sprint speed when it goes off. */
    private static final int HOP_RUN_UP = 4;

    private final BotBody body;
    private LevelWalkability view;
    private UUID chase;
    private int recheck;
    private boolean clear = true;
    private int hopDelay;
    private int hopRunUp;

    MeleeApproach(BotBody body)
    {
        this.body = body;
    }

    /**
     * One tick of closing, called after the body has looked at the target and before the navigation ticks.
     *
     * @param aim where the bot believes the target is now
     * @return true while the navigation controller is the one walking
     */
    boolean run(BotPvpConfig cfg, LivingEntity target, Perception.Snapshot aim)
    {
        EntityPlayerActionPack pack = body.pack();
        if (hopDelay > 0)
        {
            hopDelay--;
        }
        if (!straight(aim))
        {
            return navigate(pack, cfg, target);
        }
        if (chase != null)
        {
            pack.stopNavigation();
            chase = null;
        }
        drive(pack, cfg, aim);
        return false;
    }

    /** Gives the distance up, so that the next chase starts from scratch. */
    void stop(EntityPlayerActionPack pack)
    {
        if (chase != null)
        {
            pack.stopNavigation();
            chase = null;
        }
        clear = true;
        recheck = 0;
    }

    /** Hands the walk to the navigation controller, which chases without ever attacking. */
    private boolean navigate(EntityPlayerActionPack pack, BotPvpConfig cfg, LivingEntity target)
    {
        if (!target.getUUID().equals(chase))
        {
            chase = target.getUUID();
            pack.setNavApproach(chase, cfg.meleeRange);
        }
        return true;
    }

    /** Walks straight at the target, sprinting, and jumps when bunny-hopping is on and the jump is faster. */
    private void drive(EntityPlayerActionPack pack, BotPvpConfig cfg, Perception.Snapshot aim)
    {
        pack.setSneaking(false);
        pack.setSprinting(true);
        pack.setForward(1.0F);
        pack.setStrafing(0.0F);
        if (!cfg.bhop || horizontal(aim) < 3.0F || hopDelay > 0)
        {
            return;
        }
        // A sprint jump adds a fixed push on top of the sprint speed, so it is the faster way in, but it only
        // pays off once the bot is at sprint speed and there is room to land and go again.
        if (hopRunUp < HOP_RUN_UP || !body.bot().onGround())
        {
            hopRunUp++;
            return;
        }
        hopRunUp = 0;
        hopDelay = HOP_TICKS;
        pack.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.once());
    }

    /**
     * True while a player-sized box can walk from where the bot stands to where the target is, on one level: a
     * wall, a drop or a ledge in between is what the navigation controller is for.
     */
    private boolean straight(Perception.Snapshot aim)
    {
        if (recheck > 0)
        {
            recheck--;
            return clear;
        }
        recheck = RECHECK_TICKS;
        if (!(body.bot().level() instanceof ServerLevel level))
        {
            return true;
        }
        if (view == null || view.level() != level)
        {
            view = new LevelWalkability(level);
        }
        int y = (int) Math.floor(body.bot().getY());
        BlockPos at = body.bot().blockPosition();
        if (!view.canStand(at.getX(), y, at.getZ()))
        {
            return true;
        }
        clear = DirectSteer.canWalkLine(view, at.getX(), y, at.getZ(),
                (int) Math.floor(aim.x), (int) Math.floor(aim.y), (int) Math.floor(aim.z));
        return clear;
    }

    private double horizontal(Perception.Snapshot aim)
    {
        double dx = aim.x - body.bot().getX();
        double dz = aim.z - body.bot().getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}