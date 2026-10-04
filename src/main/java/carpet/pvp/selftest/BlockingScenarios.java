package carpet.pvp.selftest;

import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * What a fake player takes off a hit it has blocked with the item in its hand.
 *
 * <p>All of it is {@code LivingEntity.hurtServer} and {@code BlocksAttacks}, which this mod used to
 * short-circuit for a fake player: {@code EntityPlayerMPFake.hurtServer} applied the blocking item itself and
 * threw the rest of the hit away, which left a fake player holding a shield up untouchable by any hit it could
 * see and gave it twenty ticks of invulnerability on top whenever a hit took the shield down. A real player has
 * the item applied inside {@code hurtServer} and carries on with the rest of the damage, the hurt time, the
 * knockback and the sounds.</p>
 *
 * <p>The hits are real swings from another fake player rather than the damage command, because a shield only
 * counts as up once it has been raised for the five ticks the game takes, and because the shield of the blocker
 * is what says how many hits were stopped: it takes durability damage for every one of them.</p>
 */
final class BlockingScenarios
{
    /** Health the blocker starts on, so anything below it came out of a hit. */
    private static final float WHOLE = 20.0F;
    /** What one of the fixed hits is worth, more than anything the shield in the way could stop. */
    private static final float HIT = 10.0F;
    /** What the smaller hit of the sword block is worth. */
    private static final float SMALL_HIT = 4.0F;
    /** Health a hit may cost and still be called fully blocked. */
    private static final float BLOCKED = 0.05F;
    /** Ticks the hit after a shield is broken is given to land. */
    private static final int NEXT_HIT_TICKS = 4;

    private BlockingScenarios() {}

    /**
     * A sword hit from the front stops at a shield that is up, and the same bot takes the same hits in full once
     * that shield is gone.
     *
     * <p>The attacker is a bot, because it has to keep closing the gap a hit knocks back: a fighter that only
     * swings at a fixed spot walks out of reach after the first one, and a scenario about blocking wants every
     * hit of the run to be a hit from the front.</p>
     */
    static Scenario front(String a, String b, String c, Vec3 origin)
    {
        Vec3 inFront = origin.add(0.0D, 0.0D, 2.0D);
        int[] phase = {0};
        int[] stopped = {0};
        return new Scenario(1200, List.of(new Bot(a, origin), new Bot(b, inFront)), List.of(), SelfTest.NOTHING,
                server ->
        {
            ServerPlayer shield = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                blocker(a).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordKit(b).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(b, "expert").forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "bot duel " + a + " " + b);
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt("%s holds its shield up against %s", a, b));
            }
            if (phase[0] == 1)
            {
                stopped[0] = worn(shield);
                if (stopped[0] <= 0)
                {
                    return SelfTest.pending(SelfTest.fmt("%s is on %.1f health, blocking %s, its shield %s with %d"
                            + " of damage on it; %s is %.2f blocks away with %d hits landed", a, shield.getHealth(),
                            shield.isBlocking(), SelfTest.describe(shield.getItemBySlot(EquipmentSlot.OFFHAND)),
                            stopped[0], b, SelfTest.player(server, b).distanceTo(shield),
                            SelfTest.stats(SelfTest.player(server, b)).hits));
                }
                if (shield.getHealth() < WHOLE)
                {
                    return new Probe(false, SelfTest.fmt(
                            "%s lost %.1f health to a hit from the front with its shield up, so the block took"
                                    + " only part of it", a, WHOLE - shield.getHealth()));
                }
                // The shield has stopped a hit and the health has not moved: now take it away and let the same
                // attacker carry on.
                SelfTest.run(server, SelfTest.cmd(a + " unequip offhand"));
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("%s blocked a hit with its shield and is still on %.1f health,"
                        + " its shield took %d of it", a, shield.getHealth(), stopped[0]));
            }
            if (shield.getHealth() < WHOLE)
            {
                float lost = WHOLE - shield.getHealth();
                return new Probe(lost > 0.0F, SelfTest.fmt(
                        "%s took %.1f from %s only once its shield was taken away, so the %d hits %s put into it"
                                + " from the front were stopped by the shield and not by nothing landing", a, lost, b,
                        stopped[0], b));
            }
            return SelfTest.pending(SelfTest.fmt("%s is on %.1f health without its shield", a, shield.getHealth()));
        });
    }

    /**
     * The same shield and the same fixed hit, with the source behind the blocker instead of in front: a shield
     * covers a cone ninety degrees wide, so a hit from outside it is not blocked at all.
     */
    static Scenario behind(String a, String b, String c, Vec3 origin)
    {
        Vec3 back = origin.add(0.0D, 1.0D, -3.0D);
        int[] phase = {0};
        float[] lost = {-1.0F};
        return new Scenario(700, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 6.0D))), List.of(),
                SelfTest.NOTHING, server ->
        {
            ServerPlayer shield = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s to finish loading", a));
                }
                blocker(a).forEach(command -> SelfTest.run(server, command));
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt("%s is holding its shield up", a));
            }
            if (!shield.isBlocking())
            {
                return SelfTest.pending(a + " has not got its shield up yet");
            }
            if (!SelfTest.hittable(shield))
            {
                return SelfTest.pending(SelfTest.hitWaitReason(shield));
            }
            lost[0] = hit(server, a, HIT, back);
            if (lost[0] < 0.0F)
            {
                return new Probe(false, SelfTest.fmt(
                        "%s refused a hit from behind on %.1f health, so nothing outside the shield's cone is"
                                + " reaching it either", a, shield.getHealth()));
            }
            // The shield is still up and still the one the scenario armed with: the hit came from behind it and
            // did nothing to it at all.
            return new Probe(lost[0] >= HIT - BLOCKED && shield.isBlocking() && worn(shield) == 0, SelfTest.fmt(
                    "a %.1f hit from behind cost the blocking %s %.2f health, its shield is still up and still"
                            + " has no damage on it", HIT, a, lost[0]));
        });
    }

    /**
     * An explosion behind a fake player with its shield up. The self-test server starts on peaceful, where
     * {@code Player.hurtServer} takes the whole of a blast away, so the difficulty goes up for the blast and back
     * down before the scenario is over.
     */
    static Scenario explosion(String a, String b, String c, Vec3 origin)
    {
        int x = (int) origin.x;
        int y = (int) origin.y;
        int z = (int) origin.z;
        BlockPos charge = new BlockPos(x, y, z - 3);
        Vec3 back = origin.add(0.0D, 1.0D, -3.0D);
        int[] phase = {0};
        boolean[] primed = {false};
        float[] blast = {0.0F};
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 6.0D))), List.of(),
                SelfTest.NOTHING, server ->
        {
            ServerPlayer shield = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s to finish loading", a));
                }
                // Bedrock under the fighter, so the crater the blast leaves is not a hole it falls into.
                SelfTest.run(server, SelfTest.fill(x - 3, y - 1, z - 3, x + 3, y - 1, z + 3, "minecraft:bedrock"));
                blocker(a).forEach(command -> SelfTest.run(server, command));
                // The self-test server starts on peaceful, where Player.hurtServer takes the whole of a blast
                // away, so the difficulty goes up for the blast and back down before the scenario is over.
                SelfTest.run(server, "difficulty normal");
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt("%s is holding its shield up on a bedrock pad", a));
            }
            if (phase[0] == 1)
            {
                if (!shield.isBlocking())
                {
                    return SelfTest.pending(a + " has not got its shield up yet");
                }
                SelfTest.run(server, SelfTest.summonTnt(charge));
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("a charge went off three blocks behind %s", a));
            }
            if (SelfTest.primed(server, charge) > 0)
            {
                primed[0] = true;
                return SelfTest.pending("waiting for the charge to go off");
            }
            blast[0] = WHOLE - shield.getHealth();
            // Whatever the blast itself did, the same damage type from behind has to reach the bot: this is the
            // claim the scenario is about, and it does not depend on how far a primed charge drifts before it
            // goes off.
            float lost = blast[0] > 0.0F ? blast[0] : hit(server, a, HIT, back);
            SelfTest.run(server, "difficulty peaceful");
            return new Probe(lost > 0.0F && shield.getHealth() > 0.0F, SelfTest.fmt(
                    "the blast three blocks behind the blocking %s cost it %.1f and the same explosion damage from"
                            + " behind cost it %.1f more, leaving it on %.1f with its shield still up (%s) and the"
                            + " charge primed %s", a, blast[0], Math.max(0.0F, lost), shield.getHealth(),
                    shield.isBlocking(), primed[0]));
        });
    }

    /**
     * An axe in front of a shield puts the shield down and on cooldown, and a hit from the same direction lands
     * while it is still on cooldown. The old code answered a broken shield with twenty ticks of invulnerability
     * instead, which is what {@code shieldStunning} exists to take away again.
     */
    static Scenario axeBreaks(String a, String b, String c, Vec3 origin)
    {
        Vec3 inFront = origin.add(0.0D, 0.0D, 2.0D);
        int[] phase = {0};
        int[] waited = {0};
        float[] followUp = {-1.0F};
        return new Scenario(1200, List.of(new Bot(a, origin), new Bot(b, inFront)), List.of(), SelfTest.NOTHING,
                server ->
        {
            ServerPlayer shield = SelfTest.player(server, a);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                blocker(a).forEach(command -> SelfTest.run(server, command));
                // The axe has to be the weapon that does the hitting: the game reads the weapon of the entity a
                // hit came from to work out how long the shield it breaks stays down.
                SelfTest.swordKit(b, true).forEach(command -> SelfTest.run(server, command));
                SelfTest.swordCombat(b, "skilled").forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "bot duel " + a + " " + b);
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt("%s holds its shield up against %s, which has an axe", a, b));
            }
            if (phase[0] == 1)
            {
                ItemStack in = shield.getItemBySlot(EquipmentSlot.OFFHAND);
                if (in.isEmpty() || !shield.getCooldowns().isOnCooldown(in))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for the axe of %s to break the shield of %s,"
                            + " which is on %.1f health and %s, holding %s", b, a, shield.getHealth(),
                            shield.isBlocking(), SelfTest.describe(in)));
                }
                if (shield.isBlocking())
                {
                    return new Probe(false, SelfTest.fmt(
                            "the shield of %s is on cooldown but it is still blocking, so it can never be hit"
                                    + " again", a));
                }
                // The axe has done its work, and nothing else is swinging at the shield any more.
                SelfTest.run(server, SelfTest.cmd(b + " stop"));
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("the axe took the shield of %s down at %.1f health", a,
                        shield.getHealth()));
            }
            float lost = hit(server, a, HIT, origin.add(0.0D, 1.0D, 3.0D));
            if (lost < 0.0F)
            {
                if (++waited[0] > NEXT_HIT_TICKS)
                {
                    return new Probe(false, SelfTest.fmt(
                            "%s could not be hit again %d ticks after its shield was broken, so breaking it left it"
                                    + " invulnerable for the twenty ticks a real player does not get", a, waited[0]));
                }
                return SelfTest.pending(SelfTest.fmt("waiting for %s to be able to take a hit, %d ticks after its"
                        + " shield went down", a, waited[0]));
            }
            followUp[0] = lost;
            return new Probe(followUp[0] >= HIT - BLOCKED, SelfTest.fmt(
                    "an axe from the front put the shield of %s down and on cooldown, and a %.1f hit from the front"
                            + " %d ticks later cost it %.2f health", a, HIT, waited[0], followUp[0]));
        });
    }

    /**
     * The 1.8 sword block is a partial one: with {@code swordBlockHitting} on, a fake player holding a sword up
     * loses {@code swordBlockDamageMultiplier} of a fixed hit and the rest of it goes through. The second half
     * turns the rule off rather than letting the sword down, because a fake player cannot let go of a sword that
     * is in use.
     */
    static Scenario swordBlock(String a, String b, String c, Vec3 origin)
    {
        Vec3 front = origin.add(0.0D, 1.0D, 3.0D);
        int[] phase = {0};
        float[] guarding = new float[2];
        float[] open = new float[2];
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, origin.add(0.0D, 0.0D, 6.0D)),
                new Bot(c, origin.add(0.0D, 0.0D, -6.0D))), List.of(), SelfTest.NOTHING, server ->
        {
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b, c))
                {
                    return SelfTest.pending(SelfTest.fmt("waiting for %s, %s and %s to finish loading", a, b, c));
                }
                SelfTest.run(server, "carpet swordBlockHitting true");
                SelfTest.run(server, SelfTest.cmd(a + " equip mainhand minecraft:diamond_sword"));
                SelfTest.run(server, SelfTest.cmd(a + " use continuous"));
                phase[0] = 1;
                return SelfTest.pending(SelfTest.fmt("%s is holding its sword up with the rule on", a));
            }
            if (phase[0] == 1)
            {
                ServerPlayer blocker = SelfTest.player(server, a);
                if (!blocker.isUsingItem())
                {
                    return SelfTest.pending(a + " has not got its sword up yet");
                }
                if (!SelfTest.hittable(blocker, SelfTest.player(server, c)))
                {
                    return SelfTest.pending(SelfTest.hitWaitReason(blocker, SelfTest.player(server, c)));
                }
                // The same fixed hit on the blocking bot and on one that is not blocking at all, so what is left
                // of the hit is what the block let through.
                guarding[0] = hit(server, a, SMALL_HIT, front);
                guarding[1] = hit(server, c, SMALL_HIT, origin.add(0.0D, 1.0D, -3.0D));
                if (guarding[0] < 0.0F || guarding[1] < 0.0F)
                {
                    return SelfTest.pending(SelfTest.fmt("with the rule on the blocking %s lost %.2f and %s lost"
                            + " %.2f of the hit", a, guarding[0], c, guarding[1]));
                }
                SelfTest.run(server, "carpet swordBlockHitting false");
                phase[0] = 2;
                return SelfTest.pending(SelfTest.fmt("with the rule on the blocking %s lost %.2f against %.2f for"
                        + " %s, and the rule is off for the same sword", a, guarding[0], guarding[1], c));
            }
            if (phase[0] == 2)
            {
                ServerPlayer blocker = SelfTest.player(server, a);
                if (!SelfTest.hittable(blocker, SelfTest.player(server, c)))
                {
                    return SelfTest.pending(SelfTest.hitWaitReason(blocker, SelfTest.player(server, c)));
                }
                open[0] = hit(server, a, SMALL_HIT, front);
                open[1] = hit(server, c, SMALL_HIT, origin.add(0.0D, 1.0D, -3.0D));
                if (open[0] < 0.0F || open[1] < 0.0F)
                {
                    return SelfTest.pending(SelfTest.fmt("with the rule off the blocking %s lost %.2f and %s lost"
                            + " %.2f of the hit", a, open[0], c, open[1]));
                }
                phase[0] = 3;
                return SelfTest.pending(SelfTest.fmt("with the rule off the same sword lost %.2f", open[0]));
            }
            // The rule's own multiplier leaves half of the hit, so the blocking bot loses half of it while the
            // one that never had a sword up loses all of it, and with the rule off the blocking bot loses it too.
            boolean halved = SelfTest.same(guarding[0], SMALL_HIT * 0.5F) && guarding[0] < open[0];
            boolean whole = SelfTest.same(open[0], SMALL_HIT) && SelfTest.same(guarding[1], SMALL_HIT)
                    && SelfTest.same(open[1], SMALL_HIT);
            return new Probe(halved && whole, SelfTest.fmt(
                    "a %.1f hit from the front cost the sword-blocking %s %.2f health with the rule on and %.2f"
                            + " with it off, while %s took %.2f and %.2f of the same hit either way", SMALL_HIT, a,
                    guarding[0], open[0], c, guarding[1], open[1]));
        });
    }

    /**
     * The commands that put a shield up and keep it there, which is what a player does with the offhand.
     */
    private static List<String> blocker(String name)
    {
        return List.of(SelfTest.cmd(name + " equip shield minecraft:shield"),
                SelfTest.cmd(name + " use continuous"));
    }

    /** How much wear a blocking item has taken, which is how many hits it stopped. */
    private static int worn(ServerPlayer player)
    {
        ItemStack blocking = player.getItemBlockingWith();
        return blocking == null ? 0 : blocking.getDamageValue();
    }

    /**
     * What one hit of a fixed size costs a fake player, with the source where it comes from.
     *
     * @return what it cost in health, or -1 while the game is refusing the hit
     */
    private static float hit(MinecraftServer server, String name, float amount, Vec3 from)
    {
        ServerPlayer bot = SelfTest.player(server, name);
        float before = bot.getHealth();
        return SelfTest.result(server, "damage " + name + " " + amount + " minecraft:player_attack at "
                + (int) from.x + " " + (int) from.y + " " + (int) from.z) > 0 ? before - bot.getHealth() : -1.0F;
    }
}
