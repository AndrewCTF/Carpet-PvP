package carpet.pvp;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Per-bot PvP combat-AI driver. One instance per {@link EntityPlayerMPFake}, ticked once per
 * game tick from {@link EntityPlayerActionPack#onUpdate()}.
 *
 * <p>The brain does not reimplement combat or navigation — it <em>drives</em> the existing
 * action-pack primitives ({@code setNavChase}, {@code setStrafing}, {@code stopNavigation},
 * ...) and survival helpers in {@link CombatUtils}. It runs a fixed priority pipeline each
 * tick: survival reflexes → retreat check → target acquisition → engage + realism.</p>
 */
public final class BotBrain
{
    /** Number of ticks a revenge target stays "hot" after the last hit taken. */
    private static final long REVENGE_MEMORY_TICKS = 100L;

    private final EntityPlayerMPFake bot;

    private UUID pendingTarget;
    private int reactionCountdown;
    private int strafeDir = 1;
    private int strafeTimer;

    public BotBrain(EntityPlayerMPFake bot)
    {
        this.bot = bot;
    }

    public void tick()
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        if (cfg == null) return;

        EntityPlayerActionPack pack = ((ServerPlayerInterface) bot).getActionPack();
        if (pack == null) return;

        // 1) Survival reflexes run regardless of combat toggle.
        applySurvival(cfg);

        // 2) Combat disabled -> make sure we are not chasing.
        if (!cfg.combat)
        {
            disengage(pack);
            return;
        }

        // 3) Retreat: below the configured HP threshold, break off the engagement.
        if (bot.getHealth() <= cfg.retreatHealth)
        {
            disengage(pack);
            return;
        }

        // Expose realism knobs to the chase-attack code.
        pack.botMissChancePercent = cfg.missChance;

        // 4) Acquire a target (revenge first, then auto-target scan).
        LivingEntity target = resolveTarget(cfg);
        if (target == null)
        {
            disengage(pack);
            return;
        }

        UUID current = pack.getNavChaseTarget();
        if (current == null || !current.equals(target.getUUID()))
        {
            // New target: honour reaction delay before engaging.
            if (pendingTarget == null || !pendingTarget.equals(target.getUUID()))
            {
                pendingTarget = target.getUUID();
                reactionCountdown = cfg.reactionDelay;
                return;
            }
            if (reactionCountdown > 0)
            {
                reactionCountdown--;
                return;
            }
            pack.setNavChase(target.getUUID(), cfg.critical, cfg.meleeRange, cfg.attackCooldown);
        }

        // 5) Strafe while in melee range for less predictable movement.
        if (cfg.strafe && bot.onGround())
        {
            double reach = cfg.meleeRange + 1.0;
            if (bot.distanceToSqr(target) <= reach * reach)
            {
                if (--strafeTimer <= 0)
                {
                    strafeDir = -strafeDir;
                    strafeTimer = 10 + bot.getRandom().nextInt(15);
                }
                pack.setStrafing(strafeDir);
            }
        }
    }

    private void applySurvival(BotPvpConfig cfg)
    {
        if (cfg.autoTotem)
        {
            CombatUtils.ensureTotemInOffhand(bot);
        }
        else if (cfg.autoShield && bot.getHealth() <= 8.0F)
        {
            CombatUtils.ensureShieldInOffhand(bot);
        }
        // auto-food is handled by the action pack's existing maybeAutoEat() path.
    }

    private void disengage(EntityPlayerActionPack pack)
    {
        if (pack.getNavChaseTarget() != null)
        {
            pack.stopNavigation();
        }
        pendingTarget = null;
    }

    private LivingEntity resolveTarget(BotPvpConfig cfg)
    {
        // Revenge: retaliate against a recent attacker if still valid.
        if (cfg.revenge && bot.lastAttackerUUID != null)
        {
            long age = bot.level().getGameTime() - bot.lastAttackerTick;
            if (age >= 0 && age < REVENGE_MEMORY_TICKS && bot.level() instanceof ServerLevel sl)
            {
                Entity a = sl.getEntity(bot.lastAttackerUUID);
                if (a instanceof LivingEntity le && le.isAlive()
                        && TargetSelector.isValidTarget(bot, cfg, le)
                        && bot.distanceToSqr(le) <= cfg.targetRange * cfg.targetRange)
                {
                    return le;
                }
            }
        }

        if (!cfg.autoTarget)
        {
            return null;
        }
        return TargetSelector.select(bot, cfg);
    }
}
