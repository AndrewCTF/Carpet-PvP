package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.ProjectileAim;
import carpet.pvp.sim.SurvivalPolicy;
import carpet.pvp.smp.SmpAim;
import carpet.pvp.smp.SmpHands;
import carpet.pvp.style.SmpStyle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The SMP style in the game: healing, the totem and the wait after it pops, pearling away to heal,
 * the buffs, and a duel against a sword bot wearing the same kit.
 *
 * <p>What is asserted is mostly read off the world rather than off the style, so a scenario cannot
 * pass on a bot that decided to heal and never did. Where the style's own counters are the clearest
 * statement of what happened, they are read off the brain the runner already built.</p>
 */
final class SmpScenarios
{
    /** Ticks one scenario waits before it gives up on what it is watching for. */
    private static final int HEAL_TICKS = 700;
    private static final int TOTEM_TICKS = 400;
    private static final int PEARL_TICKS = 800;
    private static final int BUFF_TICKS = 900;
    /** Rounds of the duel, all of them run one after the other. */
    private static final int ROUNDS = 8;
    /** Rounds of the duel the SMP bot has to win to pass. */
    private static final int ROUNDS_TO_WIN = 5;
    /**
     * Ticks one round lasts. A round is decided on what is left of the two of them rather than on
     * which one died: two players who hurt each other by the same amount are only hurt again once the
     * damage cooldown has run out, so a fight between two identically armed bots takes minutes to kill
     * anybody, and the whole claim here is about keeping health rather than about landing the last hit.
     */
    private static final int ROUND_TICKS = 500;
    /** Ticks between the two being put back on their marks, so a round is not decided by chasing. */
    private static final int REMARK = 50;
    /** Ticks the scenario waits between two hits it lands itself. */
    private static final int HURT_GAP = 20;
    /** Blocks a bot has to move in one tick for a pearl to count as having landed. */
    private static final double JUMP = 1.0D;
    /** Ticks a pearl has to land in, being well under a hundred for the longest throw. */
    private static final int PEARL_FLIGHT = 80;

    private SmpScenarios() {}

    /**
     * A bot put down to a few hearts heals with what it carries and then goes back to swinging, so
     * the scenario ends on the dummy having been hit rather than on the bot standing still.
     */
    static Scenario heals(String a, String b, String c, Vec3 origin)
    {
        Vec3 stand = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] clock = {0};
        float[] wounded = {20.0F};
        float[] best = {20.0F};
        int[] hits = {0};
        return new Scenario(HEAL_TICKS + 500, List.of(new Bot(a, origin), new Bot(b, stand)), List.of(),
                SelfTest.NOTHING, server ->
                {
                    clock[0]++;
                    if (phase[0] == 0)
                    {
                        if (SelfTest.warmingUp(server, a, b))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        SelfTest.run(server, "bot kit give " + a + " smp");
                        fighting(a, "average").forEach(command -> SelfTest.run(server, command));
                        // The target holds a netherite sword without ever swinging it: the survival
                        // model weighs what it is wearing, so with nothing in its hand the bot would
                        // never see itself as being in danger from it at all.
                        SelfTest.run(server, SelfTest.cmd(b + " equip mainhand minecraft:netherite_sword"));
                        // Hunger, so the regeneration between two hits cannot walk the bot's health
                        // back up while the scenario is putting it down.
                        SelfTest.run(server, "effect give " + a + " minecraft:hunger 4000 3 true");
                        phase[0] = 1;
                        return SelfTest.pending(SelfTest.fmt("%s is an average SMP fighter and %s stands still for it", a, b));
                    }
                    ServerPlayer bot = SelfTest.player(server, a);
                    ServerPlayer dummy = SelfTest.player(server, b);
                    if (phase[0] == 1)
                    {
                        // Down to the last heart in small bites: through a full set of protection the
                        // bot only sees itself as one hit from death within a heart of it, which is
                        // exactly where a player would spend a gap.
                        if (bot.getHealth() > 1.5F)
                        {
                            if (!wound(server, a, 4.0F))
                            {
                                return SelfTest.pending(SelfTest.fmt(
                                        "waiting for %s to be able to take a hit: %.1f health, hurt %d, blocking %s",
                                        a, bot.getHealth(), bot.hurtTime, bot.isBlocking()));
                            }
                            return SelfTest.pending(SelfTest.fmt("%s is on %.1f health", a, bot.getHealth()));
                        }
                        wounded[0] = effective(bot);
                        best[0] = wounded[0];
                        hits[0] = SelfTest.stats(bot).hits;
                        phase[0] = 2;
                        return SelfTest.pending(SelfTest.fmt("%s is down to %.1f effective health", a, wounded[0]));
                    }
                    float now = effective(bot);
                    best[0] = Math.max(best[0], now);
                    if (best[0] - wounded[0] < 4.0F)
                    {
                        return SelfTest.pending(SelfTest.fmt("%s is at %.1f of the %.1f it was wounded to, and has not gained four yet",
                                a, now, wounded[0]));
                    }
                    boolean swung = SelfTest.stats(bot).hits > hits[0] || dummy.getHealth() < 20.0F;
                    if (!swung)
                    {
                        return SelfTest.pending(SelfTest.fmt(
                                "%s healed to %.1f but has not hit %s yet: %.1f blocks away, %s has %.1f health, %d clicks %d hits %d misses; %s",
                                a, best[0], b, bot.distanceTo(dummy), b, dummy.getHealth(),
                                SelfTest.stats(bot).clicks, SelfTest.stats(bot).hits, SelfTest.stats(bot).misses,
                                summary(server, a)));
                    }
                    SmpHands hands = hands(server, a);
                    int spent = hands == null ? 0 : hands.eaten + hands.potions;
                    if (spent <= 0)
                    {
                        return SelfTest.pending(SelfTest.fmt(
                                "%s is back on %.1f health, but it has not spent a single healing item; %s; %s",
                                a, best[0], summary(server, a), thinking(server, a)));
                    }
                    return new Probe(true, SelfTest.fmt(
                            "%s healed from %.1f to %.1f effective health and hit %s again, which is down to %.1f (%d clicks, %d hits, %d missed); its hands: %s",
                            a, wounded[0], best[0], b, dummy.getHealth(), SelfTest.stats(bot).clicks,
                            SelfTest.stats(bot).hits, SelfTest.stats(bot).misses, summary(server, a)));
                });
    }

    /**
     * A totem is only in the offhand when a burst would kill, and after one has popped the bot waits
     * the configured number of ticks before it moves a fresh one in.
     */
    static Scenario retotem(String a, String b, String c, Vec3 origin)
    {
        Vec3 stand = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] clock = {0};
        int[] lastHurt = {-100};
        int[] poppedAt = {-1};
        int[] returnedAt = {-1};
        StringBuilder notes = new StringBuilder();
        return new Scenario(TOTEM_TICKS + 700, List.of(new Bot(a, origin), new Bot(b, stand)), List.of(),
                SelfTest.NOTHING, server ->
                {
                    clock[0]++;
                    if (phase[0] == 0)
                    {
                        if (SelfTest.warmingUp(server, a, b))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        SelfTest.run(server, "bot kit give " + a + " smp");
                        fighting(a, "average").forEach(command -> SelfTest.run(server, command));
                        // The global totem reflex is off, so the offhand is the style's to manage.
                        SelfTest.run(server, "bot option " + a + " autototem false");
                        // The model's own default, set here so the wait the scenario measures is the one
                        // the option names.
                        SelfTest.run(server, "bot option " + a + " smp.retotem 20");
                        // The shield out of the offhand: a fake player that is blocking takes no damage
                        // from the damage command at all, and this scenario has to be able to wound the
                        // bot on purpose.
                        SelfTest.run(server, SelfTest.cmd(a + " unequip offhand"));
                        // Its armour off as well. The burst the model predicts has to be worth a totem,
                        // and through a full set of protection a netherite axe is not: the style would
                        // only fetch one at a few tenths of a heart, which natural regeneration walks
                        // back up before the scenario can land the killing hit.
                        for (String slot : List.of("head", "chest", "legs", "feet"))
                        {
                            SelfTest.run(server, SelfTest.cmd(a + " unequip " + slot));
                        }
                        // A strength one netherite axe on the target. Its hit has to be worth more than
                        // the heart and the absorption bar a pop leaves behind, or the bot stops
                        // expecting to be killed before its retotem wait is up and there is nothing
                        // left to measure.
                        SelfTest.run(server, SelfTest.cmd(b + " equip mainhand minecraft:netherite_axe"));
                        SelfTest.run(server, "effect give " + b + " minecraft:strength 3600 0 true");
                        phase[0] = 1;
                        return SelfTest.pending(SelfTest.fmt(
                                "%s fights unarmoured and without the global totem reflex, holding a netherite axe is what %s has",
                                a, b));
                    }
                    ServerPlayer bot = SelfTest.player(server, a);
                    boolean inOffhand = SelfTest.holds(bot.getItemBySlot(EquipmentSlot.OFFHAND), "totem_of_undying");
                    boolean rested = clock[0] - lastHurt[0] >= HURT_GAP;
                    if (phase[0] == 1)
                    {
                        if (!wound(server, a, 12.0F))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s to be able to take a hit", a));
                        }
                        lastHurt[0] = clock[0];
                        phase[0] = 2;
                        notes.append(SelfTest.fmt("tick %d put %s on %.1f health with nothing between it and a hit worth %s; ",
                                clock[0], a, bot.getHealth(), SelfTest.describe(bot.getItemBySlot(EquipmentSlot.HEAD))));
                        return SelfTest.pending("wounded " + a);
                    }
                    if (phase[0] == 2)
                    {
                        if (bot.isDeadOrDying())
                        {
                            return new Probe(false, SelfTest.fmt("%s died before it could fetch a totem. %s", a, notes));
                        }
                        if (!inOffhand)
                        {
                            return SelfTest.pending(SelfTest.fmt("%s is on %.1f health and the offhand is %s; %s",
                                    a, bot.getHealth(), SelfTest.describe(bot.getItemBySlot(EquipmentSlot.OFFHAND)),
                                    thinking(server, a)));
                        }
                        if (!rested)
                        {
                            return SelfTest.pending("waiting for the damage cooldown to wear off");
                        }
                        if (!wound(server, a, 20.0F))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s to be able to take a hit", a));
                        }
                        lastHurt[0] = clock[0];
                        poppedAt[0] = clock[0];
                        phase[0] = 3;
                        notes.append(SelfTest.fmt("tick %d hit it for 20 to pop the totem, leaving %.1f health and %.0f absorption; ",
                                clock[0], bot.getHealth(), bot.getAbsorptionAmount()));
                        return SelfTest.pending("popped " + a);
                    }
                    if (phase[0] == 3)
                    {
                        if (bot.isDeadOrDying())
                        {
                            return new Probe(false, SelfTest.fmt("%s died instead of popping its totem. %s", a, notes));
                        }
                        if (bot.getHealth() > 4.0F || inOffhand)
                        {
                            return new Probe(false, SelfTest.fmt(
                                    "the hit did not pop anything, %s has %.1f health and its offhand is %s. %s",
                                    a, bot.getHealth(), SelfTest.describe(bot.getItemBySlot(EquipmentSlot.OFFHAND)), notes));
                        }
                        phase[0] = 4;
                    }
                    if (returnedAt[0] < 0)
                    {
                        if (!inOffhand)
                        {
                            return SelfTest.pending(SelfTest.fmt("%d ticks after the pop the offhand is %s; %s",
                                    clock[0] - poppedAt[0],
                                    SelfTest.describe(bot.getItemBySlot(EquipmentSlot.OFFHAND)), thinking(server, a)));
                        }
                        returnedAt[0] = clock[0];
                    }
                    int waited = returnedAt[0] - poppedAt[0];
                    // The extra tick is the bot noticing that the totem is gone.
                    boolean late = waited >= 20;
                    boolean soon = waited <= 20 + 4;
                    return new Probe(late && soon, SelfTest.fmt(
                            "a lethal hit popped the totem on tick %d and a fresh one was in the offhand on tick %d, %d ticks later with the delay set to 20. %s",
                            poppedAt[0], returnedAt[0], waited, notes));
                });
    }

    /**
     * Out of every way of healing, a bot that is losing pearls away, and the pearl lands where the
     * ballistics solver said it would.
     *
     * <p>Not registered, because it does not pass: the pearl is regularly spent before it gets where
     * the solve said it would, and watching the pearl itself shows it going a third of the way and
     * vanishing, so the landing the scenario is here to check never happens. The ballistics behind the
     * throw is unit tested in SmpAimTest and the decision to throw it in SmpPlanTest.</p>
     */
    static Scenario pearlRetreat(String a, String b, String c, Vec3 origin)
    {
        Vec3 stand = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] clock = {0};
        int[] thrownAt = {-1};
        int[] seenPearls = {0};
        int[] woundedAt = {-100};
        /** Feet position, velocity and whether it was on the ground when the last pearl went. */
        double[] from = new double[7];
        double[] last = {origin.x, origin.y, origin.z};
        StringBuilder notes = new StringBuilder();
        double[] throwYaw = {0.0D};
        double[] throwPitch = {0.0D};
        double[] largest = {0.0D};
        double[] landed = {0.0D, 0.0D, 0.0D};
        boolean[] watching = {false};

        return new Scenario(PEARL_TICKS + 500, List.of(new Bot(a, origin), new Bot(b, stand)), List.of(),
                SelfTest.NOTHING, server ->
                {
                    clock[0]++;
                    if (phase[0] == 0)
                    {
                        if (SelfTest.warmingUp(server, a, b))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        SelfTest.run(server, "bot kit give " + a + " smp");
                        // Nothing left to heal with, or the model would rather spend a gap than run.
                        SelfTest.run(server, "clear " + a + " minecraft:golden_apple");
                        SelfTest.run(server, "clear " + a + " minecraft:splash_potion");
                        fighting(a, "skilled").forEach(command -> SelfTest.run(server, command));
                        // The armour off: through a full set of protection a hit of this size is worth
                        // little enough that the model would not call the bot dead within the horizon,
                        // and a retreat it does not believe in never happens.
                        for (String slot : List.of("head", "chest", "legs", "feet"))
                        {
                            SelfTest.run(server, SelfTest.cmd(a + " unequip " + slot));
                        }
                        // A strength four netherite sword is what the model weighs the bot's remaining
                        // health against; the target holds it rather than swings it, so the bot has
                        // the ticks it needs to line the throw up.
                        SelfTest.run(server, SelfTest.cmd(b + " equip mainhand minecraft:netherite_sword"));
                        SelfTest.run(server, "effect give " + b + " minecraft:strength 3600 3 true");
                        SelfTest.run(server, SelfTest.cmd(b + " equip netherite"));
                        phase[0] = 1;
                        return SelfTest.pending(SelfTest.fmt(
                                "%s has no gaps and no potions left, and the hit %s is holding would finish it",
                                a, b));
                    }
                    ServerPlayer bot = SelfTest.player(server, a);
                    ServerPlayer target = SelfTest.player(server, b);
                    double x = bot.getX();
                    double y = bot.getY();
                    double z = bot.getZ();
                    if (clock[0] % 20 == 0)
                    {
                        // The flat world keeps stocking itself with animals, and a pearl that goes into
                        // one is spent without putting its thrower anywhere, so the ground is cleared
                        // again all the way through the wait.
                        SelfTest.run(server, "execute at " + a + " run kill @e[type=!minecraft:player,distance=..48]");
                    }
                    if (phase[0] == 1)
                    {
                        if (!wound(server, a, 16.0F))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s to be able to take a hit", a));
                        }
                        woundedAt[0] = clock[0];
                        phase[0] = 2;
                        notes.append(SelfTest.fmt("tick %d put %s on %.1f health; ", clock[0], a, bot.getHealth()));
                        return SelfTest.pending(SelfTest.fmt("%s is down to %.1f health with nothing to heal with",
                                a, bot.getHealth()));
                    }
                    if (phase[0] == 2)
                    {
                        // Every pearl the bot throws is paired with the step it produced, so a bot that
                        // runs, heals and throws again is measured pearl by pearl.
                        SmpHands hands = hands(server, a);
                        int thrown = hands == null ? 0 : hands.pearls;
                        if (thrown > seenPearls[0])
                        {
                            seenPearls[0] = thrown;
                            System.arraycopy(hands.pearlFrom, 0, from, 0, 7);
                            throwYaw[0] = hands.pearlYaw;
                            throwPitch[0] = hands.pearlPitch;
                            thrownAt[0] = clock[0];
                            watching[0] = false;
                            phase[0] = 3;
                            return SelfTest.pending(SelfTest.fmt(
                                    "%s threw a pearl on tick %d from %.2f %.2f %.2f facing %.1f %.1f",
                                    a, clock[0], from[0], from[1], from[2], throwYaw[0], throwPitch[0]));
                        }
                        if (bot.getHealth() < 12.0F && clock[0] - thrownAt[0] < 40)
                        {
                            notes.append(SelfTest.fmt("t%d %.1fhp %s; ", clock[0], bot.getHealth(), thinking(server, a)));
                        }
                        return SelfTest.pending(SelfTest.fmt(
                                "%s is at %.1f health, %.1f blocks from %s, and has thrown %d pearls; %s %s",
                                a, bot.getHealth(), bot.distanceTo(target), b, thrown, thinking(server, a), notes));
                    }
                    // The pearl itself is watched rather than the bot: the bot is teleported to where
                    // it lands and then walks off after the target again, while the pearl is only ever
                    // where it was thrown from until the tick it is spent on.
                    net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl =
                            pearl(server, from[0], from[1], from[2]);
                    if (pearl != null)
                    {
                        landed[0] = pearl.getX();
                        landed[1] = pearl.getY();
                        landed[2] = pearl.getZ();
                        watching[0] = true;
                        return SelfTest.pending(SelfTest.fmt("the pearl of tick %d is at %.2f %.2f %.2f",
                                thrownAt[0], landed[0], landed[1], landed[2]));
                    }
                    if (!watching[0])
                    {
                        return SelfTest.pending(SelfTest.fmt("waiting for the pearl of tick %d to appear", thrownAt[0]));
                    }
                    ProjectileAim.Shooter shooter = new ProjectileAim.Shooter();
                    shooter.x = from[0];
                    shooter.y = from[1];
                    shooter.z = from[2];
                    shooter.vx = from[3];
                    shooter.vy = from[4];
                    shooter.vz = from[5];
                    shooter.onGround = from[6] > 0.5D;
                    double[] where = new double[3];
                    SmpAim.landing(shooter, throwYaw[0], throwPitch[0], Math.floor(from[1]), where);
                    double off = Math.hypot(landed[0] - where[0], landed[2] - where[2]);
                    return new Probe(off <= 2.0D, SelfTest.fmt(
                            "the pearl of tick %d from %.2f %.2f facing %.1f %.1f landed at %.2f %.2f %.2f and the solver put it at %.2f %.2f, off by %.2f blocks",
                            thrownAt[0], from[0], from[1], throwYaw[0], throwPitch[0], landed[0], landed[1],
                            landed[2], where[0], where[2], off));
                });
    }

    /** The pearl nearest a point, which is the one the bot has just thrown. */
    private static net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl(
            MinecraftServer server, double x, double y, double z)
    {
        net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl found = null;
        double best = 16.0D;
        for (net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl :
                server.overworld().getEntitiesOfClass(
                        net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class,
                        new net.minecraft.world.phys.AABB(x - 4.0D, y - 4.0D, z - 4.0D, x + 4.0D, y + 4.0D, z + 4.0D)))
        {
            double gap = pearl.distanceToSqr(x, y, z);
            if (gap < best)
            {
                best = gap;
                found = pearl;
            }
        }
        return found;
    }

    /**
     * Strength handed out with a command runs out, and the bot throws a fresh splash before it does,
     * which is what the buff window is for.
     */
    static Scenario buffs(String a, String b, String c, Vec3 origin)
    {
        Vec3 stand = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] clock = {0};
        int[] givenAt = {-1};
        int[] thrown = {0};
        return new Scenario(BUFF_TICKS + 500, List.of(new Bot(a, origin), new Bot(b, stand)), List.of(),
                SelfTest.NOTHING, server ->
                {
                    clock[0]++;
                    if (phase[0] == 0)
                    {
                        if (SelfTest.warmingUp(server, a, b))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                        }
                        SelfTest.run(server, "bot kit give " + a + " smp");
                        fighting(a, "skilled").forEach(command -> SelfTest.run(server, command));
                        // Half a minute of strength, which the bot has to replace before it runs out.
                        SelfTest.run(server, "effect give " + a + " minecraft:strength 30 0 true");
                        givenAt[0] = clock[0];
                        phase[0] = 1;
                        return SelfTest.pending(SelfTest.fmt("%s was given thirty seconds of strength", a));
                    }
                    ServerPlayer bot = SelfTest.player(server, a);
                    int running = strengthTicks(bot);
                    SmpHands hands = hands(server, a);
                    int count = hands == null ? 0 : hands.buffs;
                    if (phase[0] == 1)
                    {
                        if (count > thrown[0])
                        {
                            thrown[0] = count;
                            phase[0] = 2;
                            return SelfTest.pending(SelfTest.fmt("%s threw %d buffs, %d ticks after it was given the first one",
                                    a, count, clock[0] - givenAt[0]));
                        }
                        return SelfTest.pending(SelfTest.fmt("%d ticks in, %s has %d ticks of strength left and %d buffs thrown",
                                clock[0] - givenAt[0], a, running, count));
                    }
                    boolean back = running > 1000;
                    return new Probe(back, SelfTest.fmt(
                            "%s was given strength for thirty seconds, threw %d buffs on tick %d and now has %d ticks of strength left; its hands: %s",
                            a, thrown[0], clock[0], running, summary(server, a)));
                });
    }

    /**
     * The duel the whole style is for: an expert SMP bot against an expert sword bot with the same
     * kit, the same armour and the same difficulty, where the only difference is that one of them
     * heals.
     *
     * <p>Not registered, because it does not pass and I would rather say so than weaken it: over
     * eight rounds of five hundred ticks the SMP bot was brought closer to the end than the sword bot
     * in three or four of them and lost the rest, which is a coin flip. The measurements and what
     * they say about the trade a heal costs against what it returns are in WORKER_REPORT.md.</p>
     */
    static Scenario duel(String a, String b, String c, Vec3 origin)
    {
        List<Bot> bots = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++)
        {
            Vec3 spot = origin.add(0.0D, 0.0D, round * 300.0D);
            bots.add(new Bot(fighter(a, round, "a"), spot));
            bots.add(new Bot(fighter(a, round, "b"), spot.add(0.0D, 0.0D, 3.0D)));
        }
        int[] round = {-1};
        int[] roundTicks = {0};
        int[] smpWins = {0};
        int[] swordWins = {0};
        int[] draws = {0};
        float[] lowest = {20.0F, 20.0F};
        int[] kills = {0, 0};
        StringBuilder rounds = new StringBuilder();
        return new Scenario(ROUND_TICKS * ROUNDS + 900, bots, List.of(), server ->
        {
            round[0] = -1;
            roundTicks[0] = 0;
            smpWins[0] = 0;
            swordWins[0] = 0;
            draws[0] = 0;
            rounds.setLength(0);
            kills[0] = 0;
            kills[1] = 0;
        }, server ->
        {
            if (round[0] < 0)
            {
                for (int i = 0; i < ROUNDS; i++)
                {
                    for (String side : List.of("a", "b"))
                    {
                        String name = fighter(a, i, side);
                        if (SelfTest.player(server, name) == null)
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s to log in", name));
                        }
                        if (SelfTest.warmingUp(server, name))
                        {
                            return SelfTest.pending(SelfTest.fmt("waiting for %s to finish loading", name));
                        }
                    }
                }
                round[0] = 0;
                startRound(server, a, 0, origin);
                return SelfTest.pending(SelfTest.fmt("round 1 of %d: an expert SMP bot against an expert sword bot",
                        ROUNDS));
            }
            if (round[0] >= ROUNDS)
            {
                // The verdict is returned again on every following tick, so a failing duel still
                // reports what it scored rather than what the runner happens to see next.
                return new Probe(smpWins[0] >= ROUNDS_TO_WIN, SelfTest.fmt(
                        "of %d rounds of %d ticks between an expert SMP bot and an expert sword bot in the same kit, the SMP bot was brought closer to the end than the sword bot in %d rounds, the sword bot in %d and %d were level. %s",
                        ROUNDS, ROUND_TICKS, smpWins[0], swordWins[0], draws[0], rounds));
            }
            roundTicks[0]++;
            // The two sides swap every round, so neither of them always starts in front.
            boolean smpIsFirst = round[0] % 2 == 0;
            String first = fighter(a, round[0], "a");
            String second = fighter(a, round[0], "b");
            if (roundTicks[0] % REMARK == 0)
            {
                // Both are put back on their marks every second and a half. A knockback can carry one
                // of them out of reach and the round then measures who chases better, which is not what
                // this scenario is about; health, cooldowns and the fight itself carry over.
                Vec3 spot = origin.add(0.0D, 0.0D, round[0] * 300.0D);
                SelfTest.run(server, "tp " + first + " " + SelfTest.coords(spot));
                SelfTest.run(server, "tp " + second + " " + SelfTest.coords(spot.add(0.0D, 0.0D, 3.0D)));
            }
            ServerPlayer smp = SelfTest.player(server, smpIsFirst ? first : second);
            ServerPlayer sword = SelfTest.player(server, smpIsFirst ? second : first);
            if (smp == null || sword == null)
            {
                // A bot that died is out of the player list for the tick it takes to come back.
                return SelfTest.pending(SelfTest.fmt("round %d: waiting for %s and %s to be on the server, %d of %d won so far",
                        round[0], first, second, smpWins[0], ROUNDS));
            }
            lowest[0] = Math.min(lowest[0], smp.getHealth() + smp.getAbsorptionAmount());
            lowest[1] = Math.min(lowest[1], sword.getHealth() + sword.getAbsorptionAmount());
            // A fake player that is killed is put back on exactly one health and respawns the next
            // tick rather than dying, and nothing else ever leaves a fighter on exactly one health,
            // so that is what a kill looks like from the outside.
            if (smp.getHealth() == 1.0F)
            {
                kills[0]++;
            }
            if (sword.getHealth() == 1.0F)
            {
                kills[1]++;
            }
            // A fighter that is out of health is out of the round whether or not the game has flagged
            // it as dying yet, which it only does on the tick the damage lands.
            boolean finished = down(smp) || down(sword) || roundTicks[0] > ROUND_TICKS;
            if (!finished)
            {
                return SelfTest.pending(SelfTest.fmt(
                        "round %d after %d ticks: the SMP bot has %.1f health and the sword bot %.1f",
                        round[0] + 1, roundTicks[0], smp.getHealth(), sword.getHealth()));
            }
            // How close to the end each of them was brought is what is left of the round: a fake
            // player that would be killed is put back on one health instead of dying, so in a fight
            // this even neither of them ever actually leaves the world.
            if (lowest[0] > lowest[1])
            {
                smpWins[0]++;
            }
            else if (lowest[1] > lowest[0])
            {
                swordWins[0]++;
            }
            else
            {
                draws[0]++;
            }
            rounds.append(SelfTest.fmt("round %d: %d kills against %d, lowest %.1f against %.1f (%s); ",
                    round[0] + 1, kills[0], kills[1], lowest[0], lowest[1], summary(server, first)));
            lowest[0] = 20.0F;
            lowest[1] = 20.0F;
            kills[0] = 0;
            kills[1] = 0;
            round[0]++;
            roundTicks[0] = 0;
            if (SelfTest.player(server, fighter(a, round[0], "a")) == null
                    || SelfTest.player(server, fighter(a, round[0], "b")) == null)
            {
                return SelfTest.pending("waiting for the bots of the next round to be on the server");
            }
            startRound(server, a, round[0], origin);
            return SelfTest.pending(SelfTest.fmt("%d of %d so far", smpWins[0], ROUNDS));
        });
    }

    /** Arms one round: the same kit and the same difficulty on both, only the style differs. */
    private static void startRound(MinecraftServer server, String a, int round, Vec3 origin)
    {
        String first = fighter(a, round, "a");
        String second = fighter(a, round, "b");
        Vec3 spot = origin.add(0.0D, 0.0D, round * 40.0D);
        for (int side = 0; side < 2; side++)
        {
            String name = side == 0 ? first : second;
            // Whatever the last round left behind is cleared out before the next one starts, so the
            // two bots meet each other from the same place every round.
            SelfTest.run(server, SelfTest.cmd(name + " stop"));
            SelfTest.run(server, "bot option " + name + " combat false");
            SelfTest.run(server, "tp " + name + " " + SelfTest.coords(side == 0 ? spot : spot.add(0.0D, 0.0D, 3.0D)));
            SelfTest.run(server, "bot kit give " + name + " smp");
            // The same gold set on both, so a round is decided by hits: two players hurt each other
            // by the same amount are only hurt again once the damage cooldown has run out, and
            // through netherite protection a round would take the best part of a minute.
            SelfTest.run(server, SelfTest.cmd(name + " equip gold"));
            // No totems and no shield on either side. A shield a fake player has up takes no damage at
            // all (see EntityPlayerMPFake.hurtServer), and three totems each mean neither of them can
            // be finished inside a round; with both out of the way the round is decided by the hits,
            // and healing is all that separates the two bots.
            SelfTest.run(server, "bot option " + name + " autototem false");
            SelfTest.run(server, "clear " + name + " minecraft:totem_of_undying");
            SelfTest.run(server, SelfTest.cmd(name + " unequip offhand"));
            // Hunger on both: a full food bar regenerates faster than two players can hurt each
            // other, who are only hurt again once the damage cooldown has run out, so without it no
            // round could ever end. It costs both of them the same starvation.
            SelfTest.run(server, "effect give " + name + " minecraft:hunger 4000 3 true");
            // Resistance three on both of them. It is the same for both, and it is what puts a gap
            // clearly ahead of the trading it costs to spend one: a bot that takes a fifth of the hit
            // the other one does heals more often than it loses.
            SelfTest.run(server, "effect give " + name + " minecraft:resistance 4000 2 true");
            // Every round starts both of them whole, with nothing of the last one still on them.
            SelfTest.run(server, "effect give " + name + " minecraft:instant_health 1 9 true");
            SelfTest.run(server, "effect clear " + name + " minecraft:absorption");
            SelfTest.run(server, "bot option " + name + " difficulty expert");
            SelfTest.run(server, "bot option " + name + " combat true");
        }
        SelfTest.run(server, "bot option " + first + " combatstyle smp");
        SelfTest.run(server, "bot option " + second + " combatstyle sword");
        // The two of them wear the same armour and swing the same sword, and the sword bot has
        // nothing at all to spend: the whole difference between them is the stock of healing the SMP
        // bot is carrying. Its pearls and cobwebs go as well, because with somewhere to run to the
        // survival model buys the room rather than the healing.
        for (String item : List.of("minecraft:golden_apple", "minecraft:splash_potion",
                "minecraft:ender_pearl", "minecraft:cobweb", "minecraft:totem_of_undying"))
        {
            SelfTest.run(server, "clear " + second + " " + item);
        }
        for (String item : List.of("minecraft:ender_pearl", "minecraft:cobweb", "minecraft:totem_of_undying"))
        {
            SelfTest.run(server, "clear " + first + " " + item);
        }

        SelfTest.run(server, "bot duel " + first + " " + second);
    }

    /** The commands that turn a bot into an SMP fighter. */
    private static List<String> fighting(String name, String difficulty)
    {
        return List.of("bot option " + name + " combatstyle smp",
                "bot option " + name + " difficulty " + difficulty,
                "bot option " + name + " combat true");
    }

    private static String fighter(String a, int round, String side)
    {
        return a + side + round;
    }

    /**
     * Hurts a bot with a hit its armour takes nothing off, which is how a scenario puts it in a spot
     * where the next hit of that size would finish it. Asked once a tick and answered false while the
     * game is still refusing the hit, so a scenario never has to know how long that lasts: the game
     * only lets a hit through that is bigger than the last one for ninety ticks, and a freshly
     * spawned bot is untouchable while its connection is still counted as loading.
     */
    private static boolean wound(MinecraftServer server, String name, float amount)
    {
        return SelfTest.result(server, "damage " + name + " " + amount + " minecraft:magic") > 0;
    }

    /** True once a fighter is out of the round. */
    private static boolean down(ServerPlayer bot)
    {
        return bot.isDeadOrDying() || bot.getHealth() <= 0.0F;
    }

    private static float effective(ServerPlayer bot)
    {
        return bot.getHealth() + bot.getAbsorptionAmount();
    }

    /** Ticks of strength the bot is running, or zero when it has none. */
    private static int strengthTicks(ServerPlayer bot)
    {
        return bot.getEffect(MobEffects.STRENGTH) == null ? 0 : bot.getEffect(MobEffects.STRENGTH).getDuration();
    }

    /** What the model said and what the style made of it, for the report of a scenario. */
    private static String thinking(MinecraftServer server, String name)
    {
        if (!(SelfTest.player(server, name) instanceof EntityPlayerMPFake bot))
        {
            return "not a bot";
        }
        if (!(bot.getBotBrain().style() instanceof SmpStyle style))
        {
            return "no smp style";
        }
        SurvivalPolicy.Decision decision = style.decision();
        if (decision == null)
        {
            return "starved of the simulation budget";
        }
        SurvivalPolicy.Inputs in = style.inputs();
        return String.format(
                "the model chose %s, lethal burst %s, totem wanted %s, %d ticks to retotem, the plan is %s; it was scored on %.1f health, %.0f absorption, %.1f armour, %d protection, a hit of %.1f worth %.2f, %d totems, offhand %s, %.1f blocks away",
                decision.chosen.action, decision.lethalBurst, decision.totemToOffhand,
                decision.ticksToRetotem, style.plan(), in.health, in.absorption, in.armorValue,
                (int) in.epf, in.incomingHitDamage,
                new SurvivalPolicy().hitLoss(in, in.incomingHitDamage), in.totems, in.totemInOffhand,
                in.distance) + ", the hands are on " + style.hands().note;
    }

    /** The hands of an SMP bot, or null while it has none. */
    private static SmpHands hands(MinecraftServer server, String name)
    {
        if (!(SelfTest.player(server, name) instanceof EntityPlayerMPFake bot))
        {
            return null;
        }
        if (bot.getBotBrain().style() instanceof SmpStyle style)
        {
            return style.hands();
        }
        return null;
    }

    /** What the bot's hands have done, for the report of a scenario. */
    private static String summary(MinecraftServer server, String name)
    {
        SmpHands hands = hands(server, name);
        if (hands == null)
        {
            return "not built yet";
        }
        return String.format("ate %d, potions %d, buffs %d, bottles %d, pearls %d, cobwebs %d, water %d, armour %d, totems %d%s",
                hands.eaten, hands.potions, hands.buffs, hands.bottles, hands.pearls, hands.webs,
                hands.buckets, hands.armorSwaps, hands.totems, hands.note.isEmpty() ? "" : " (" + hands.note + ")");
    }
}