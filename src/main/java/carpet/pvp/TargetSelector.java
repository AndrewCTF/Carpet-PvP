package carpet.pvp;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Chooses a combat target for a bot from nearby living entities, honouring the bot's
 * {@link BotPvpConfig} target filters and {@link FactionManager} allegiances.
 */
public final class TargetSelector
{
    private TargetSelector() {}

    /**
     * Returns the best target for {@code self} within range, or {@code null} if none qualifies.
     * Nearest valid entity wins.
     */
    public static LivingEntity select(EntityPlayerMPFake self, BotPvpConfig cfg)
    {
        AABB box = self.getBoundingBox().inflate(cfg.targetRange);
        List<LivingEntity> candidates = self.level().getEntitiesOfClass(
                LivingEntity.class, box,
                e -> e != self && e.isAlive() && isValidTarget(self, cfg, e));

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : candidates)
        {
            double d = self.distanceToSqr(e);
            if (d < bestDist)
            {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** True if {@code self} is allowed to attack {@code e} given filters and factions. */
    public static boolean isValidTarget(EntityPlayerMPFake self, BotPvpConfig cfg, LivingEntity e)
    {
        // Never attack faction allies.
        if (FactionManager.areFriendly(self.getUUID(), e.getUUID())) return false;

        if (e instanceof EntityPlayerMPFake)
        {
            return cfg.targetBots;
        }
        if (e instanceof ServerPlayer)
        {
            return cfg.targetPlayers;
        }
        if (e instanceof Mob)
        {
            return cfg.targetMobs;
        }
        return false;
    }
}
