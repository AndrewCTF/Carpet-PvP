package carpet.pvp.selftest;

import carpet.pvp.mace.MaceSwap;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * What the attribute swap is worth on this Minecraft version, measured against the game rather than against
 * the style that uses it. Both probes put the item that charges a swing in one hand and the mace in the other,
 * then make the same hit twice: once with the mace in the hand and once with it swapped in on the tick of the
 * swing. The only thing that differs between the two numbers is where the mace was, which is the whole
 * technique.
 *
 * <p>A fall is made by lifting the fighter with {@code /tp} rather than with a wind charge, because the swap
 * needs the hand to be holding the charging item on the tick of the swing and throwing a charge would mean
 * putting it there. {@code /tp} moves a fighter and zeroes its velocity but leaves its attack cooldown alone,
 * so both passes fall the same distance and reach the swing on the same tick.</p>
 */
final class MaceSwapScenarios
{
    /** Hotbar slots of the mace kit, as {@code /player hotbar} counts them from one. */
    static final int SMASH_MACE = 1;
    static final int BREACH_MACE = 2;
    static final int WIND_CHARGE = 3;
    static final int PEARL = 4;
    static final int ROCKET = 5;
    static final int SWORD = 6;
    static final int AXE = 7;
    static final int ELYTRA = 8;

    /** How far above its spawn point a pass lifts the fighter before the fall. */
    private static final double LIFT = 8.0;
    /**
     * How far the fighter must have dropped before it swings, which is what the smash gate turns on. The
     * height is used rather than the counter because the two passes have to come down on the same tick, and
     * the counter of a fighter that was teleported carries the height the teleport moved it.
     */
    private static final double MIN_DROP = 1.6;
    /**
     * How far the falling fighter's eyes may still be over the top of the dummy when it swings. The action
     * pack traces a ray three blocks long from the eyes to find what a click would hit, which is shorter than
     * the reach the attack itself allows, so the swing has to be taken before the fighter is right on top of
     * the target.
     */
    private static final double RAY_REACH = 2.6;
    /** How close under the falling fighter the dummy stands. */
    private static final double UNDER = 0.8;
    /** Ticks a swing is given to show up in the dummy's health. */
    private static final int SETTLE = 4;
    /** Ticks the charge is given to come back to a full strength hit before the probe gives up. */
    private static final int RECHARGE = 80;

    private final String fighter;
    private final String other;
    private final String dummy;
    private final Vec3 spot;
    private final Vec3 rest;
    private final boolean falls;
    private int phase;
    private int ticks;
    private int settle;
    private int[] settling = {0};
    private float top = 1000.0F;
    private float bottom = 1000.0F;
    private float fallA = -1.0F;
    private float fallB = -1.0F;
    /** Counter the fighter already carried when it was lifted, which the two passes are read against. */
    private final float[] base = {0.0F};
    private float dealtA;
    private float dealtB;
    private int cadenceA;
    private int cadenceB;
    private boolean ready;
    private String report;

    /**
     * @param fighter the fighter of the first pass
     * @param other   the fighter of the second pass, a player of its own, because a teleport adds the height
     *                it moved to the fall distance of the fighter it moved and a second lift would leave
     *                the counter of the first pass on the second
     */
    private MaceSwapScenarios(String fighter, String other, String dummy, Vec3 spot, Vec3 rest, boolean falls)
    {
        this.fighter = fighter;
        this.other = other;
        this.dummy = dummy;
        this.spot = spot;
        this.rest = rest;
        this.falls = falls;
        this.phase = falls ? 0 : 8;
    }

    static Scenario swapProbe(String a, String b, String c, Vec3 origin)
    {
        Vec3 spot = origin.add(0.0D, 0.0D, UNDER);
        MaceSwapScenarios probe = new MaceSwapScenarios(a, c, b, spot, origin, true);
        return new Scenario(700, List.of(new Bot(a, origin), new Bot(b, spot, 180.0D), new Bot(c, origin)),
                List.of(), probe::tick);
    }

    static Scenario breachSwapProbe(String a, String b, String c, Vec3 origin)
    {
        Vec3 spot = origin.add(0.0D, 0.0D, UNDER);
        MaceSwapScenarios probe = new MaceSwapScenarios(a, c, b, spot, origin, false);
        return new Scenario(500, List.of(new Bot(a, origin), new Bot(b, spot, 180.0D), new Bot(c, origin)),
                List.of(), probe::tick);
    }

    /**
     * The fall probe charges the mace at its own rate, lifts the fighter, swings out of the fall, reads what
     * that did and counts the ticks the mace needs to come back to a full strength hit. Then it does the same
     * four steps with a netherite sword collecting the cooldown and the mace swapped in on the tick of the
     * swing. The ground probe skips the fall and only does the two charged hits.
     */
    private Probe tick(MinecraftServer server)
    {
        // The odd phases are the fall probe's two passes and the even ones its two readings; the ground probe
        // starts at phase eight, and each pass is made by a fighter of its own so neither is lifted twice.
        boolean swap = phase >= 2 && phase < 8 && (phase - 2) / 2 % 2 == 1;
        String name = phase >= 8 || swap ? other : fighter;
        ServerPlayer bot = SelfTest.player(server, name);
        ServerPlayer target = SelfTest.player(server, dummy);
        if (bot == null || target == null) return SelfTest.pending("the fighters have not joined yet");
        if (SelfTest.warmingUp(server, fighter, other, dummy))
        {
            return new Probe(false, SelfTest.fmt("waiting for %s, %s and %s to finish loading", fighter, other, dummy));
        }
        if (!ready)
        {
            ready = true;
            SelfTest.run(server, "bot kit give " + fighter + " mace");
            SelfTest.run(server, "bot kit give " + other + " mace");
            SelfTest.run(server, "bot kit give " + dummy + " mace");
            if (falls)
            {
                // The fall probe prices its two numbers against CombatMath, which has nothing to say about
                // armour, so that dummy is stripped down to its skin. The ground probe is about armour, so this
                // one keeps the whole kit on.
                SelfTest.run(server, "clear " + dummy);
            }
            topUp(server, dummy);
            return new Probe(false, SelfTest.fmt("%s holds the mace kit, %s wears the netherite kit and cannot die",
                    fighter, dummy));
        }
        // The action pack's own attack path traces a ray from the bot's eyes along its view, so the fighter
        // has to be looking at the dummy on the tick of the swing, the way a player would be.
        face(bot, target);
        String progress;
        switch (phase)
        {
            case 0 -> {
                select(server, name, SMASH_MACE);
                if (++ticks > MaceSwap.MACE_TICKS)
                {
                    lift(server, name);
                    phase = 1;
                }
                progress = SelfTest.fmt("charging the mace %d/%d", ticks, MaceSwap.MACE_TICKS);
            }
            case 1 -> {
                settle(server, name);
                if (base[0] < 0.0F)
                {
                    base[0] = (float) bot.fallDistance;
                }
                if ((rest.add(0.0D, LIFT, 0.0D).y - bot.getY()) < MIN_DROP || !above(bot, target))
                {
                    return SelfTest.pending(SelfTest.fmt("falling, %.2f of the way down", bot.fallDistance));
                }
                fallA = (float) bot.fallDistance - base[0];
                swing(server, name);
                phase = 2;
                progress = SelfTest.fmt("swung out of %.2f of fall with the mace already in hand", fallA);
            }
            case 2 -> {
                ticks++;
                if (!read(target))
                {
                    return SelfTest.pending(SelfTest.fmt("reading what the swing did: %.2f so far", top - bottom));
                }
                dealtA = top - bottom;
                select(server, name, SMASH_MACE);
                phase = 3;
                progress = SelfTest.fmt("the mace held through did %.2f", dealtA);
            }
            case 3 -> {
                if (!charged(bot))
                {
                    return SelfTest.pending(SelfTest.fmt("the mace recharging at %.2f",
                            bot.getAttackStrengthScale(0.5F)));
                }
                cadenceA = ticks;
                select(server, name, SWORD);
                ticks = 0;
                phase = 4;
                progress = SelfTest.fmt("the mace was ready again after %d ticks", cadenceA);
            }
            case 4 -> {
                select(server, name, SWORD);
                if (++ticks > MaceSwap.SWORD_TICKS)
                {
                    lift(server, name);
                    phase = 5;
                }
                progress = SelfTest.fmt("charging the sword %d/%d", ticks, MaceSwap.SWORD_TICKS);
            }
            case 5 -> {
                settle(server, name);
                if (base[0] < 0.0F)
                {
                    base[0] = (float) bot.fallDistance;
                }
                if ((rest.add(0.0D, LIFT, 0.0D).y - bot.getY()) < MIN_DROP || !above(bot, target))
                {
                    return SelfTest.pending(SelfTest.fmt("falling, %.2f of the way down", bot.fallDistance));
                }
                fallB = (float) bot.fallDistance - base[0];
                // The swap the technique is about: the item in the hand changes on the tick of the swing.
                select(server, name, SMASH_MACE);
                swing(server, name);
                phase = 6;
                progress = SelfTest.fmt("swung out of %.2f of fall with the mace swapped in on the tick", fallB);
            }
            case 6 -> {
                ticks++;
                if (!read(target))
                {
                    return SelfTest.pending(SelfTest.fmt("reading what the swing did: %.2f so far", top - bottom));
                }
                dealtB = top - bottom;
                select(server, name, SWORD);
                phase = 7;
                progress = SelfTest.fmt("the swapped smash did %.2f", dealtB);
            }
            case 7 -> {
                if (!charged(bot))
                {
                    return SelfTest.pending(SelfTest.fmt("the sword recharging at %.2f",
                            bot.getAttackStrengthScale(0.5F)));
                }
                cadenceB = ticks;
                MaceSwap.recordSmash(dealtA, cadenceA, dealtB, cadenceB);
                report = SelfTest.fmt(
                        "the mace held through fell %.2f and did %.2f, ready again after %d ticks; the same drop"
                                + " charged off the netherite sword fell %.2f and did %.2f with the mace swapped in on"
                                + " the tick of the hit, ready again after %d ticks, so %s",
                        (double) fallA, dealtA, cadenceA, (double) fallB, dealtB, cadenceB, MaceSwap.describe());
                phase = 16;
                return SelfTest.pending(report);
            }
            case 8 -> {
                select(server, name, SWORD);
                mark(server);
                ticks = 0;
                phase = 9;
                progress = "charging the sword on the ground";
            }
            case 9 -> {
                if (++ticks > MaceSwap.SWORD_TICKS)
                {
                    swing(server, name);
                    phase = 10;
                }
                progress = SelfTest.fmt("charging the sword %d/%d", ticks, MaceSwap.SWORD_TICKS);
            }
            case 10 -> {
                if (!read(target))
                {
                    return SelfTest.pending(SelfTest.fmt("reading what the swing did: %.2f so far", top - bottom));
                }
                dealtA = top - bottom;
                select(server, name, SWORD);
                ticks = 0;
                phase = 11;
                progress = SelfTest.fmt("the sword on its own did %.2f through the netherite", dealtA);
            }
            case 11 -> {
                select(server, name, SWORD);
                if (++ticks > MaceSwap.SWORD_TICKS)
                {
                    select(server, name, BREACH_MACE);
                    swing(server, name);
                    phase = 12;
                }
                progress = SelfTest.fmt("charging the sword %d/%d", ticks, MaceSwap.SWORD_TICKS);
            }
            case 12 -> {
                if (!read(target))
                {
                    return SelfTest.pending(SelfTest.fmt("reading what the swing did: %.2f so far", top - bottom));
                }
                dealtB = top - bottom;
                MaceSwap.recordBreach(dealtA, dealtB);
                report = SelfTest.fmt(
                        "a charged sword hit did %.2f on its own and %.2f with the Breach mace swapped in on the"
                                + " tick of the hit, both against full protection netherite, so %s",
                        dealtA, dealtB, MaceSwap.breachDescribe());
                phase = 16;
                return SelfTest.pending(report);
            }
            default -> {
                return new Probe(true, report);
            }
        }
        return SelfTest.pending(progress);
    }

    /**
     * True while the falling fighter is inside the dummy's reach: the only term of the range that changes on
     * the way down is the height of the fighter's eyes over the top of the dummy's box.
     */
    private boolean above(ServerPlayer bot, ServerPlayer target)
    {
        return bot.getEyeY() - (target.getY() + target.getBbHeight()) <= RAY_REACH;
    }

    /** Turns the fighter's view onto the middle of the dummy, which is what a player aims before it swings. */
    private static void face(ServerPlayer bot, ServerPlayer target)
    {
        double dx = target.getX() - bot.getX();
        double dy = target.getY() + target.getBbHeight() * 0.5 - bot.getEyeY();
        double dz = target.getZ() - bot.getZ();
        double flat = Math.max(Math.sqrt(dx * dx + dz * dz), 1.0E-6);
        bot.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        bot.setXRot((float) -Math.toDegrees(Math.atan2(dy, flat)));
    }

    /** Two ticks of settling after a lift, which is how long the teleport takes to book the fall distance. */
    private static final int LIFT_SETTLE = 2;

    /** One hotbar change, which is what a player's scroll wheel does and what the game allows. */
    private void select(MinecraftServer server, String who, int slot)
    {
        SelfTest.run(server, SelfTest.cmd(who + " hotbar " + slot));
    }

    /** Lifts the fighter clear of the ground to begin the fall, and starts reading the dummy's health. */
    private void lift(MinecraftServer server, String who)
    {
        SelfTest.run(server, "tp " + who + " " + SelfTest.coords(rest.add(0.0D, LIFT, 0.0D)));
        SelfTest.run(server, "data merge entity " + fighter + " {FallDistance:0.0d,Motion:[0.0d,0.0d,0.0d]}");
        mark(server);
        settling[0] = LIFT_SETTLE;
        base[0] = -1.0F;
    }

    /**
     * A teleport adds the height it moved to the counter of the fighter it moved, because the game counts
     * fall distance from the movement between two positions, so the counter is put back on the first tick of
     * the fall as well. Without this the second pass would start with the first pass's fall still on it.
     */
    private void settle(MinecraftServer server, String who)
    {
        if (settling[0] > 0)
        {
            SelfTest.run(server, "data merge entity " + who + " {FallDistance:0.0d,Motion:[0.0d,0.0d,0.0d]}");
            settling[0]--;
        }
    }

    private void mark(MinecraftServer server)
    {
        ServerPlayer target = SelfTest.player(server, dummy);
        // A smash knocks the dummy back and pins the attacker's fall, so both are put back where they were
        // before the next pass: the only thing the two numbers may differ by is where the mace was.
        SelfTest.run(server, "tp " + dummy + " " + SelfTest.coords(spot));
        SelfTest.run(server, "data merge entity " + dummy + " {FallDistance:0.0d,Motion:[0.0d,0.0d,0.0d]}");
        // Both ends of the reading are the dummy's health as it stands, which is not the top of its pool
        // after an earlier hit and the regeneration that follows it.
        top = target.getHealth();
        bottom = top;
        settle = SETTLE;
        ticks = 0;
    }

    private void swing(MinecraftServer server, String who)
    {
        // The dummy regenerates in between, so the reading starts on the tick of the swing itself.
        ServerPlayer target = SelfTest.player(server, dummy);
        top = target.getHealth();
        bottom = top;
        SelfTest.run(server, SelfTest.cmd(who + " attack once"));
        settle = SETTLE;
        ticks = 0;
    }

    /** Watches the dummy's health until the swing has shown up. */
    private boolean read(ServerPlayer target)
    {
        bottom = Math.min(bottom, target.getHealth());
        return --settle <= 0;
    }

    /** Counts the ticks the hand needs to reach a full strength hit again. */
    private boolean charged(ServerPlayer bot)
    {
        if (bot.getAttackStrengthScale(0.5F) >= 1.0F)
        {
            return true;
        }
        return ++ticks > RECHARGE;
    }

    /** Gives a dummy a health pool it cannot die in, so a probe measures two hits instead of a kill. */
    private static void topUp(MinecraftServer server, String name)
    {
        ServerPlayer target = SelfTest.player(server, name);
        if (target == null || target.getAttribute(Attributes.MAX_HEALTH) == null) return;
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.setHealth((float) target.getAttributeValue(Attributes.MAX_HEALTH));
    }
}
