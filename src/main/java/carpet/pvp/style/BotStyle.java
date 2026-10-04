package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.Perception;
import net.minecraft.world.entity.LivingEntity;

/**
 * One combat style per {@link BotPvpConfig.CombatStyle}: the engage step of a bot, from perception to
 * a decision the {@link BotBody} can carry out. Styles never touch the game themselves.
 */
public interface BotStyle
{
    /**
     * One tick of fighting the given target. The style plans or not, and hands the result to the body.
     *
     * @param body       the body of the bot, the only thing allowed to act
     * @param perception what the bot knows of itself and of its target
     * @param cfg        the bot's configuration
     * @param target     the real target entity, for the reach test and the attack itself
     * @param pack       the action pack, for the navigation that closes long distances
     */
    void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack);

    /** Drops whatever the style left running when the bot stops fighting. */
    void disengage(BotBody body);

    /** Called when the bot's configuration changed enough that the style has to be rebuilt. */
    default void reconfigure(BotPvpConfig cfg)
    {
    }
}