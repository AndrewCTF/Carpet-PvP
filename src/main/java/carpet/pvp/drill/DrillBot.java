package carpet.pvp.drill;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.Perception;
import carpet.pvp.sim.DuelSim;
import net.minecraft.world.entity.LivingEntity;

import java.util.Random;

/**
 * What a drill needs from the bot it puts in front of the player.
 *
 * <p>Aiming and movement go through the combat {@link BotBody}, so the view turns at the bot's speed
 * in whole mouse steps and the view a drill's judgement is based on lags behind what the bot can see
 * by its reaction time. The actions a drill wants that a fight never does - using an item, throwing a
 * pearl, looking at a block - go through the action pack, so the item cooldown and the one action per
 * tick of a real client hold.</p>
 */
public final class DrillBot
{
    /** Ticks the drill bot's view lags behind the player, as a bot's reaction time does. */
    private static final int REACTION_TICKS = 2;
    /** Skill the drill bots aim with, in the 0 to 1 range. */
    private static final double SKILL = 0.6;
    private static final double CLICKS_PER_SECOND = 8.0;

    private final EntityPlayerMPFake bot;
    private final EntityPlayerActionPack pack;
    private final BotBody body;
    private final Perception perception = new Perception();

    public DrillBot(EntityPlayerMPFake bot)
    {
        this.bot = bot;
        this.pack = ((ServerPlayerInterface) bot).getActionPack();
        this.body = new BotBody(bot, pack, BotBody.profileFor(SKILL, BotPvpConfig.SENSITIVITY),
                new Random(bot.getRandom().nextLong()), CLICKS_PER_SECOND);
    }

    public EntityPlayerMPFake bot()
    {
        return bot;
    }

    public BotBody body()
    {
        return body;
    }

    public EntityPlayerActionPack pack()
    {
        return pack;
    }

    /**
     * One tick of the bot: it looks at {@code at}, moves as asked, keeps its shield up as asked and,
     * for the drills that need it, swings. A drill that does not pass {@code swing} never attacks.
     */
    public void tick(LivingEntity at, int forward, int strafe, boolean sprint, boolean jump, boolean shield,
            boolean swing)
    {
        perception.update(bot, at);
        Perception.Snapshot seen = at == null ? null : perception.target(REACTION_TICKS);
        body.tick(seen, swing ? at : null, DuelSim.action(forward, strafe, jump, sprint, swing), shield);
    }

    /** Uses whatever the bot holds in its main hand once, which is how it throws a pearl. */
    public void useOnce()
    {
        pack.start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.once());
    }

    /** The bot as it was, with its shield down and nothing held down. */
    public void stop()
    {
        body.reset();
        pack.stopMovement();
    }

    /** Horizontal distance between two fighters, which is what a drill's spacing is measured in. */
    public static double flatDistance(LivingEntity one, LivingEntity other)
    {
        double dx = other.getX() - one.getX();
        double dz = other.getZ() - one.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

}