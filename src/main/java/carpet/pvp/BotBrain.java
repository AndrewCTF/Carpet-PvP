package carpet.pvp;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.style.BotStyle;
import carpet.pvp.style.StyleIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Random;
import java.util.UUID;

/**
 * Per-bot PvP combat-AI driver. One instance per {@link EntityPlayerMPFake}, ticked once per
 * game tick from {@link EntityPlayerActionPack#onUpdate()}.
 *
 * <p>The brain does not reimplement combat or navigation — it <em>drives</em> the existing
 * action-pack primitives ({@code setNavChase}, {@code setStrafing}, {@code stopNavigation},
 * ...) and survival helpers in {@link CombatUtils}. It runs a fixed priority pipeline each
 * tick: survival reflexes → retreat check → target acquisition → engage, and the engage step
 * belongs to the bot's combat {@link BotStyle}. The body and the style are built once and kept for
 * as long as the settings that shape them; the style is swapped when the combat style changes.</p>
 */
public final class BotBrain
{
    /** Number of ticks a revenge target stays "hot" after the last hit taken. */
    private static final long REVENGE_MEMORY_TICKS = 100L;
    /** How many target ranges away the fighter a bot is already fighting may get before the bot lets it go. */
    private static final double KEEP_RANGES = 2.0D;

    private final EntityPlayerMPFake bot;
    private final Perception perception = new Perception();

    private BotBody body;
    private BotStyle style;
    private BotPvpConfig.CombatStyle styleKind;
    private BotPvpConfig.Difficulty difficulty;
    private UUID perceivedTarget;
    /** Game tick the fight being watched started, and the damage the counters stood at when it did. */
    private long matchStart;
    private double matchDealt;
    private double matchTaken;
    /** The death of the current target this brain has already recorded, so a fight is recorded once. */
    private long recordedDeath = Long.MIN_VALUE;
    private UUID brainChaseTarget;
    private boolean warnedFallback;

    public BotBrain(EntityPlayerMPFake bot)
    {
        this.bot = bot;
    }

    /** The body of this bot, or null while combat has never been on. */
    public BotBody body()
    {
        return body;
    }

    /** The style that is driving the engage step, or null while there is none. */
    public BotStyle style()
    {
        return style;
    }

    /** What the bot knows of itself and its target. */
    public Perception perception()
    {
        return perception;
    }

    /**
     * The entity the bot is fighting right now, or null while it is not fighting one.
     */
    public LivingEntity target()
    {
        if (perceivedTarget == null)
        {
            return null;
        }
        return bot.level().getEntity(perceivedTarget) instanceof LivingEntity living ? living : null;
    }

    /** True when the bot has a body and a style, which fighting needs. */
    public boolean ready()
    {
        return body != null && style != null;
    }

    public void tick()
    {
        BotPvpConfig cfg = bot.getPvpConfig();
        if (cfg == null) return;

        EntityPlayerActionPack pack = bot.getActionPack();
        if (pack == null) return;

        // 1) Survival reflexes run regardless of the combat toggle.
        applySurvival(cfg, pack);

        // 2) Combat disabled -> make sure nothing is left running.
        if (!cfg.combat)
        {
            disengage(pack);
            return;
        }
        if (!ready())
        {
            body = new BotBody(bot, pack, BotBody.profileFor(cfg.skill, BotPvpConfig.SENSITIVITY),
                    new Random(bot.getRandom().nextLong()), cfg.clicksPerSecond);
        }
        styleKind = switchStyle(cfg);

        // 3) Retreat: below the configured HP threshold, break off the engagement.
        if (bot.getHealth() <= cfg.retreatHealth)
        {
            disengage(pack);
            return;
        }

        // 4) Acquire a target (revenge first, then auto-target scan).
        LivingEntity target = resolveTarget(cfg);
        if (target == null)
        {
            disengage(pack);
            return;
        }
        if (!target.getUUID().equals(perceivedTarget))
        {
            perception.reset();
            perceivedTarget = target.getUUID();
            startMatch();
        }
        perception.update(bot, target);
        watchMatch(target);

        // 5) Engage: what the bot perceives in, what the planner decides, what the body does.
        style.engage(body, perception, cfg, target, pack);
    }

    /**
     * Records the fight once it is over. A bot records it when it beat the target it was fighting: the loser is
     * the one that is dead and cannot, so between them exactly one of the two records anything, and
     * {@link MatchHistory} gets a result with both sides' damage in it for the web panel to show.
     */
    private void watchMatch(LivingEntity target)
    {
        if (body == null)
        {
            return;
        }
        // A fake player is put back a tick after it dies, before anyone else's brain runs again, so the death
        // is read off the tick it happened on rather than off a flag that is already gone by then.
        long died = target instanceof EntityPlayerMPFake dead ? dead.diedTick() : Long.MIN_VALUE;
        if (died == Long.MIN_VALUE || died == recordedDeath)
        {
            return;
        }
        recordedDeath = died;
        BotStats stats = body.stats();
        long ticks = bot.level().getGameTime() - matchStart;
        MatchHistory.record(new MatchHistory.Match(bot.getName().getString(), nameOf(target),
                bot.getName().getString(), ticks, stats.damageDealt - matchDealt,
                stats.damageTaken - matchTaken));
        matchDealt = stats.damageDealt;
        matchTaken = stats.damageTaken;
        matchStart = bot.level().getGameTime();
    }

    private static String nameOf(LivingEntity target)
    {
        return target instanceof EntityPlayerMPFake fake ? fake.getName().getString()
                : target.getName().getString();
    }

    /** One style per combat style; the ones without an implementation fall back to the sword. */
    private BotPvpConfig.CombatStyle switchStyle(BotPvpConfig cfg)
    {
        if (style != null && styleKind == cfg.combatStyle)
        {
            style.reconfigure(cfg);
            return styleKind;
        }
        styleKind = cfg.combatStyle;
        difficulty = cfg.difficulty;
        if (!StyleIndex.has(cfg.combatStyle) && !warnedFallback)
        {
            warnedFallback = true;
            bot.level().getServer().getPlayerList().broadcastSystemMessage(Component.literal(
                    "Bot " + bot.getName().getString() + ": combat style " + cfg.combatStyle
                            + " has no implementation yet, fighting with the sword"), false);
        }
        style = StyleIndex.create(cfg.combatStyle, bot, body, cfg, new Random(bot.getRandom().nextLong()));
        return styleKind;
    }

    private void applySurvival(BotPvpConfig cfg, EntityPlayerActionPack pack)
    {
        // The action pack's own auto-eat belongs to the navigation, so the per-bot switch is passed to it as
        // the navigation option of the same name: a bot with autoFood off never eats on its way somewhere.
        pack.setNavOption("autoEat", cfg.autoFood);
        if (cfg.autoTotem)
        {
            CombatUtils.ensureTotemInOffhand(bot);
        }
        else if (cfg.autoShield && bot.getHealth() <= 8.0F)
        {
            CombatUtils.ensureShieldInOffhand(bot);
        }
    }

    /** Opens the fight the match result is measured over, from where this bot's damage counters stand. */
    private void startMatch()
    {
        matchStart = bot.level().getGameTime();
        if (body != null)
        {
            matchDealt = body.stats().damageDealt;
            matchTaken = body.stats().damageTaken;
        }
    }

    private void disengage(EntityPlayerActionPack pack)
    {
        // Only stop a chase this brain started, so /player ... nav chase keeps working with combat off.
        if (brainChaseTarget != null && brainChaseTarget.equals(pack.getNavChaseTarget()))
        {
            pack.stopNavigation();
            brainChaseTarget = null;
        }
        if (style != null)
        {
            style.disengage(body);
            body.reset();
        }
        perceivedTarget = null;
        perception.reset();
        startMatch();
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
        LivingEntity selected = TargetSelector.select(bot, cfg);
        return selected != null ? selected : held(cfg);
    }

    /**
     * The fighter the bot is already fighting, kept while nobody is inside its target range. A fight does not end
     * because one side was thrown out of the range it would start one in: a mace's Wind Burst puts its wielder
     * more than twenty blocks over the target it has just hit, and it comes down on the same target.
     */
    private LivingEntity held(BotPvpConfig cfg)
    {
        LivingEntity current = target();
        if (current == null || !current.isAlive() || !TargetSelector.isValidTarget(bot, cfg, current))
        {
            return null;
        }
        double keep = cfg.targetRange * KEEP_RANGES;
        return bot.distanceToSqr(current) <= keep * keep ? current : null;
    }

    /** The difficulty the current style was built for. */
    public BotPvpConfig.Difficulty difficulty()
    {
        return difficulty;
    }
}