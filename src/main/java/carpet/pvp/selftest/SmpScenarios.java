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
     * Ticks one round may last. A round is decided on a death, which takes a bot with two totems and a kit
     * of healing a long time to bring off, so this is long enough for the healing to be what decides it.
     */
    private static final int ROUND_TICKS = 2400;
    /** Ticks between the two being put back on their marks, so a round is not decided by chasing. */
    private static final int REMARK = 50;
    /** Ticks the scenario waits between two hits it lands itself. */
    private static final int HURT_GAP = 20;
    /** Blocks a bot has to move in one tick for a pearl to count as having landed. */
    private static final double JUMP = 1.5D;
    /** Ticks a pearl has to land in, being well under a hundred for the longest throw. */
    private static final int PEARL_FLIGHT = 160;
    /** How far the style is asked to buy with a pearl, the same as its default `smp.peelback`. */
    private static final double PEARL_BACK = 12.0D;
    /** The closest a pearl throw may start, which is SmpHands.PEARL_MIN_REACH. */
    private static final double PEARL_MIN_REACH = 1.6D;

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
                        // The bot buys distance to heal and has to be allowed to come back for the dummy from
                        // there, as /bot duel does: beyond its target range it would stand where it healed.
                        SelfTest.run(server, "bot option " + a + " targetrange 64");
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
     * Out of every way of healing, a bot that is losing pearls away, and the bot lands where the
     * ballistics solver said the pearl would put it.
     *
     * <p>What is measured is the bot, not the pearl: a pearl is spent the tick it lands, and following it
     * from where it was thrown says nothing once it is more than a few blocks out, so the landing is read
     * off the tick the thrower is put somewhere it was not. The solve behind the throw is unit tested in
     * SmpAimTest and the decision to throw it in SmpPlanTest.</p>
     */
    static Scenario pearlRetreat(String a, String b, String c, Vec3 origin)
    {
        Vec3 stand = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] flight = {0};
        int[] seenPearls = {0};
        /** Feet position, velocity and whether it was on the ground when the last pearl went. */
        double[] from = new double[7];
        /** Where the bot was the tick before it landed, and where it landed. */
        double[] last = {origin.x, origin.y, origin.z};
        double[] landed = {origin.x, origin.y, origin.z};
        double[] throwYaw = {0.0D};
        double[] throwPitch = {0.0D};
        StringBuilder notes = new StringBuilder();
        double[] target0 = {origin.x, origin.z};
        return new Scenario(PEARL_TICKS + 500, List.of(new Bot(a, origin), new Bot(b, stand)), List.of(),
                SelfTest.NOTHING, server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            target0[0] = SelfTest.player(server, b).getX();
            target0[1] = SelfTest.player(server, b).getZ();
            if (phase[0] > 0)
            {
                SelfTest.run(server, "execute at " + a + " run kill @e[type=!minecraft:player,distance=..48]");
            }
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                // The flat world keeps stocking itself with animals, and a pearl that goes into one is
                // spent without putting its thrower anywhere, so the ground is cleared again all the way
                // through the wait.
                SelfTest.run(server, "execute at " + a + " run kill @e[type=!minecraft:player,distance=..48]");
                // Natural regeneration walks a four heart bot back up to twenty while it is deciding, and a
                // bot that is whole again has no reason to run.
                SelfTest.run(server, "gamerule natural_health_regeneration false");
                SelfTest.run(server, "bot kit give " + a + " smp");
                // Nothing left to heal with, or the model would rather spend a gap than run.
                SelfTest.run(server, "clear " + a + " minecraft:golden_apple");
                SelfTest.run(server, "clear " + a + " minecraft:splash_potion");
                fighting(a, "skilled").forEach(command -> SelfTest.run(server, command));
                // The armour off: through a full set of protection a hit of this size is worth little
                // enough that the model would not call the bot dead within the horizon, and a retreat it
                // does not believe in never happens.
                for (String slot : List.of("head", "chest", "legs", "feet"))
                {
                    SelfTest.run(server, SelfTest.cmd(a + " unequip " + slot));
                }
                // A strength four netherite sword is what the model weighs the bot's remaining health
                // against; the target holds it rather than swings it, so the bot has the ticks it needs to
                // line the throw up.
                SelfTest.run(server, SelfTest.cmd(b + " equip mainhand minecraft:netherite_sword"));
                SelfTest.run(server, "effect give " + b + " minecraft:strength 3600 3 true");
                SelfTest.run(server, SelfTest.cmd(b + " equip netherite"));
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt(
                        "%s has no gaps and no potions left, and the hit %s is holding would finish it", a, b));
            }
            if (phase[0] == 1)
            {
                if (SelfTest.result(server, "damage " + a + " 16.0 minecraft:magic") <= 0)
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s to be able to take a hit", a));
                }
                notes.append(SelfTest.fmt("%s was put on %.1f health; ", a, bot.getHealth()));
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("%s is down to %.1f health with nothing to heal with",
                        a, bot.getHealth()));
            }
            if (phase[0] == 2)
            {
                // Every pearl the bot throws is paired with the step it produced, so a bot that runs,
                // heals and throws again is measured pearl by pearl.
                SmpHands hands = hands(server, a);
                int thrown = hands == null ? 0 : hands.pearls;
                if (thrown > seenPearls[0])
                {
                    seenPearls[0] = thrown;
                    System.arraycopy(hands.pearlFrom, 0, from, 0, 7);
                    throwYaw[0] = hands.pearlYaw;
                    throwPitch[0] = hands.pearlPitch;
                    flight[0] = 0;
                    last[0] = bot.getX();
                    last[1] = bot.getY();
                    last[2] = bot.getZ();
                    phase[0] = 3;
                    return SelfTest.pending(SelfTest.fmt("%s threw a pearl from %.2f %.2f %.2f facing %.1f %.1f",
                            a, from[0], from[1], from[2], throwYaw[0], throwPitch[0]));
                }
                return SelfTest.pending(SelfTest.fmt(
                        "%s is at %.1f health, %.1f blocks from %s, and has thrown %d pearls; %s %s", a,
                        bot.getHealth(), bot.distanceTo(SelfTest.player(server, b)), b, thrown,
                        thinking(server, a), notes));
            }
            // The landing: the thrower is put where the pearl stopped, on one tick, from wherever it stood
            // when the pearl went. The bot is walking towards the target again on the very next tick, so
            // where it landed has to be read on the tick of the jump itself.
            double moved = Math.hypot(bot.getX() - last[0], bot.getZ() - last[2]);
            last[0] = bot.getX();
            last[1] = bot.getY();
            last[2] = bot.getZ();
            // A landing is the thrower being put somewhere it was not in one tick. A pearl that stopped on
            // a short throw, or a bot that was already walking, moves less than a jump, so a bot that has
            // clearly ended up away from where the pearl left it counts too.
            boolean away = Math.hypot(bot.getX() - from[0], bot.getZ() - from[2]) > 3.0D;
            if (moved < JUMP && !away)
            {
                if (++flight[0] > PEARL_FLIGHT)
                {
                    return new Probe(false, SelfTest.fmt("%s threw a pearl that neither landed nor spent the"
                            + " pearl in the %d ticks it was given; %s", a, PEARL_FLIGHT, notes));
                }
                return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks from where the pearl left it, %d ticks"
                        + " into the flight", a, moved, flight[0]));
            }
            landed[0] = bot.getX();
            landed[1] = bot.getY();
            landed[2] = bot.getZ();
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
            // What the throw has to achieve is room: the bot ends up down the line it was running away
            // along, well outside the reach it was in, and no further than the solve asked for. The throw
            // itself is the ballistics of ProjectileAim, which SmpAimTest checks.
            double before0 = Math.hypot(from[0] - target0[0], from[2] - target0[2]);
            double after0 = Math.hypot(landed[0] - target0[0], landed[2] - target0[2]);
            double line = distanceToLine(landed[0], landed[2], from[0], from[2], target0[0], target0[2]);
            double asked = Math.max(PEARL_MIN_REACH * 2.0D, PEARL_BACK);
            return new Probe(after0 > before0 + 3.0D && line <= 3.0D && after0 - before0 <= asked + 3.0D,
                    SelfTest.fmt("the pearl thrown from %.2f %.2f facing %.1f %.1f put %s down at %.2f %.2f"
                                    + " %.2f, %.1f blocks further from %s than it was and %.1f off the line it ran"
                                    + " away along, against a solve that put it at %.2f %.2f; %s", from[0], from[1],
                            throwYaw[0], throwPitch[0], a, landed[0], landed[1], landed[2], after0 - before0, b, line,
                            where[0], where[2], notes));
        });
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
     * The duel the whole style is for: an expert SMP bot against an expert sword bot with the same kit,
 * the same armour and the same difficulty, where the only difference is that one of them heals, re-totems
 * and keeps its buffs up.
 *
 * <p>A round is decided on a death, which is what {@link EntityPlayerMPFake#diedTick()} records, because a
 * fake player is put back on one health and respawns a tick later rather than staying dead: nothing else
 * about it says the fight was over. Both sides carry the whole kit including the totems, and both are put
 * back on their marks every second and a half, because a knockback that carries one of them out of reach
 * ends a round on the walk back rather than on the healing.</p>
 */
    static Scenario duel(String a, String b, String c, Vec3 origin)
    {
        List<Bot> bots = new ArrayList<>();
        for (int round = 0; round < ROUNDS; round++)
        {
            bots.add(new Bot(fighter(a, round, "a"), origin.add(0.0D, 0.0D, round * 300.0D)));
            bots.add(new Bot(fighter(a, round, "b"), origin.add(0.0D, 0.0D, round * 300.0D + 3.0D)));
        }
        int[] round = {-1};
        int[] roundTicks = {0};
        int[] smpWins = {0};
        int[] swordWins = {0};
        int[] draws = {0};
        long[] smpDeath = {Long.MIN_VALUE};
        long[] swordDeath = {Long.MIN_VALUE};
        /** Ticks the round has been running, and whether both fighters are back on their feet in it. */
        int[] settling = {0};
        boolean[] armed = {false};
        StringBuilder rounds = new StringBuilder();
        return new Scenario(ROUND_TICKS * ROUNDS + 900, bots, List.of(), server ->
        {
            round[0] = -1;
            roundTicks[0] = 0;
            smpWins[0] = 0;
            swordWins[0] = 0;
            draws[0] = 0;
            smpDeath[0] = Long.MIN_VALUE;
            swordDeath[0] = Long.MIN_VALUE;
            rounds.setLength(0);
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
                armed[0] = false;
                settling[0] = 0;
                startRound(server, a, 0, origin);
                return SelfTest.pending(SelfTest.fmt("round 1 of %d: an expert SMP bot against an expert sword"
                        + " bot, both in the full kit", ROUNDS));
            }
            if (round[0] >= ROUNDS)
            {
                // The verdict is returned again on every following tick, so a failing duel still reports
                // what it scored rather than what the runner happens to see next.
                return new Probe(smpWins[0] >= ROUNDS_TO_WIN, SelfTest.fmt(
                        "of %d rounds of up to %d ticks between an expert SMP bot and an expert sword bot in"
                                + " the same kit, the SMP bot killed the sword bot %d times, the sword bot killed"
                                + " the SMP bot %d and %d ran out of time. %s", ROUNDS, ROUND_TICKS, smpWins[0],
                        swordWins[0], draws[0], rounds));
            }
            roundTicks[0]++;
            // The two sides swap every round, so neither of them always starts in front.
            boolean smpIsFirst = round[0] % 2 == 0;
            String first = fighter(a, round[0], "a");
            String second = fighter(a, round[0], "b");
            if (roundTicks[0] % REMARK == 0)
            {
                Vec3 spot = origin.add(0.0D, 0.0D, round[0] * 300.0D);
                SelfTest.run(server, "tp " + first + " " + SelfTest.coords(spot));
                SelfTest.run(server, "tp " + second + " " + SelfTest.coords(spot.add(0.0D, 0.0D, 3.0D)));
            }
            EntityPlayerMPFake smp = bot(server, smpIsFirst ? first : second);
            EntityPlayerMPFake sword = bot(server, smpIsFirst ? second : first);
            if (smp == null || sword == null)
            {
                // A bot that died is out of the player list for the tick it takes to come back.
                return SelfTest.pending(SelfTest.fmt("round %d: waiting for %s and %s to be on the server, %d"
                        + " of %d won so far", round[0] + 1, first, second, smpWins[0], ROUNDS));
            }
            // A bot that died is put back on one health a tick later, so the first ticks of a round are
            // left alone until both fighters are on their feet again: otherwise the tail of the last round
            // counts as a kill in this one.
            if (!armed[0])
            {
                settling[0]++;
                if (smp.getHealth() > 1.0F && sword.getHealth() > 1.0F)
                {
                    armed[0] = true;
                    smpDeath[0] = smp.diedTick();
                    swordDeath[0] = sword.diedTick();
                }
                else if (settling[0] > 40)
                {
                    armed[0] = true;
                    smpDeath[0] = smp.diedTick();
                    swordDeath[0] = sword.diedTick();
                }
                return SelfTest.pending(SelfTest.fmt("round %d: waiting for both fighters to be back on their"
                        + " feet, the SMP bot is on %.1f and the sword bot %.1f", round[0] + 1, smp.getHealth(),
                        sword.getHealth()));
            }
            boolean smpDown = smp.diedTick() != smpDeath[0];
            boolean swordDown = sword.diedTick() != swordDeath[0];
            if (smpDown || swordDown)
            {
                smpDeath[0] = smp.diedTick();
                swordDeath[0] = sword.diedTick();
                // One of them is down, and a bot that dies is put back on one health: the round is over and
                // the next one starts both of them whole.
                if (smpDown && swordDown)
                {
                    draws[0]++;
                    rounds.append(SelfTest.fmt("round %d: both down after %d ticks (%s); ", round[0] + 1,
                            roundTicks[0], summary(server, smpIsFirst ? first : second)));
                }
                else if (swordDown)
                {
                    smpWins[0]++;
                    rounds.append(SelfTest.fmt("round %d: the SMP bot killed the sword bot with %d ticks to"
                            + " spare (%s); ", round[0] + 1, ROUND_TICKS - roundTicks[0],
                            summary(server, smpIsFirst ? first : second)));
                }
                else
                {
                    swordWins[0]++;
                    rounds.append(SelfTest.fmt("round %d: the sword bot killed the SMP bot after %d ticks (%s); ",
                            round[0] + 1, roundTicks[0], summary(server, smpIsFirst ? first : second)));
                }
                round[0]++;
                roundTicks[0] = 0;
                if (SelfTest.player(server, fighter(a, round[0], "a")) == null
                        || SelfTest.player(server, fighter(a, round[0], "b")) == null)
                {
                    return SelfTest.pending("waiting for the bots of the next round to be on the server");
                }
                startRound(server, a, round[0], origin);
                armed[0] = false;
                settling[0] = 0;
                return SelfTest.pending(SelfTest.fmt("%d of %d so far", smpWins[0], ROUNDS));
            }
            if (roundTicks[0] <= ROUND_TICKS)
            {
                return SelfTest.pending(SelfTest.fmt(
                        "round %d after %d ticks: the SMP bot has %.1f health and the sword bot %.1f", round[0] + 1,
                        roundTicks[0], smp.getHealth(), sword.getHealth()));
            }
            draws[0]++;
            rounds.append(SelfTest.fmt("round %d: out of time with the SMP bot on %.1f and the sword bot on"
                    + " %.1f (%s); ", round[0] + 1, smp.getHealth(), sword.getHealth(),
                    summary(server, smpIsFirst ? first : second)));
            round[0]++;
            roundTicks[0] = 0;
            if (SelfTest.player(server, fighter(a, round[0], "a")) == null
                    || SelfTest.player(server, fighter(a, round[0], "b")) == null)
            {
                return SelfTest.pending("waiting for the bots of the next round to be on the server");
            }
            startRound(server, a, round[0], origin);
            armed[0] = false;
            settling[0] = 0;
            return SelfTest.pending(SelfTest.fmt("%d of %d so far", smpWins[0], ROUNDS));
        });
    }

    /** The fake player behind a name, or null while it is off the server. */
    private static EntityPlayerMPFake bot(MinecraftServer server, String name)
    {
        return SelfTest.player(server, name) instanceof EntityPlayerMPFake fake ? fake : null;
    }

    /**
     * Arms one round: the same kit and the same difficulty on both, and the same style but one.
     *
     * <p>Everything the kit hands out goes on both of them, totems and the shield included, because what is
     * being measured is what one bot does with a kit the other is carrying: healing, re-toteming and buffs
     * against a fighter that has the same stock and does none of it.</p>
     */
    private static void startRound(MinecraftServer server, String a, int round, Vec3 origin)
    {
        String first = fighter(a, round, "a");
        String second = fighter(a, round, "b");
        Vec3 spot = origin.add(0.0D, 0.0D, round * 300.0D);
        for (int side = 0; side < 2; side++)
        {
            String name = side == 0 ? first : second;
            // Whatever the last round left behind is cleared out before the next one starts, so the two bots
            // meet each other from the same place every round.
            SelfTest.run(server, SelfTest.cmd(name + " stop"));
            SelfTest.run(server, "bot option " + name + " combat false");
            SelfTest.run(server, "tp " + name + " " + SelfTest.coords(side == 0 ? spot : spot.add(0.0D, 0.0D, 3.0D)));
            SelfTest.run(server, "bot kit give " + name + " smp");
            SelfTest.run(server, "bot option " + name + " autototem false");
            // Hunger on both: a full food bar regenerates faster than two players can hurt each other, who
            // are only hurt again once the damage cooldown has run out, so without it no round could ever
            // end. It costs both of them the same starvation.
            SelfTest.run(server, "effect give " + name + " minecraft:hunger 4000 3 true");
            // Every round starts both of them whole, with nothing of the last one still on them.
            SelfTest.run(server, "effect give " + name + " minecraft:instant_health 1 9 true");
            SelfTest.run(server, "effect clear " + name + " minecraft:absorption");
            SelfTest.run(server, "bot option " + name + " difficulty expert");
            SelfTest.run(server, "bot option " + name + " combat true");
        }
        SelfTest.run(server, "bot option " + first + " combatstyle smp");
        SelfTest.run(server, "bot option " + second + " combatstyle sword");
        // The shields stay in the kit and stay off the ground, but neither of them puts one up: two bots
        // that both raise a shield trade blocks instead of health, and a round of that is decided by which
        // of them breaks first rather than by what one of them does with a kit it is carrying.
        SelfTest.run(server, "bot option " + first + " smp.guard false");
        SelfTest.run(server, "bot option " + second + " shieldplay false");
        SelfTest.run(server, "bot duel " + first + " " + second);
    }

    /** How far from the line between two points a third one is, in the plane. */
    private static double distanceToLine(double x, double z, double ax, double az, double bx, double bz)
    {
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6D)
        {
            return Math.hypot(x - ax, z - az);
        }
        return Math.abs((x - ax) * dz - (z - az) * dx) / len;
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
     * Hurts a bot with a hit its armour takes nothing off, which is how a scenario puts it in a spot where
     * the next hit of that size would finish it. Asked once a tick and answered false while the game is
     * still refusing the hit, so a scenario never has to know how long that lasts.
     */
    private static boolean wound(MinecraftServer server, String name, float amount)
    {
        return SelfTest.result(server, "damage " + name + " " + amount + " minecraft:magic") > 0;
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