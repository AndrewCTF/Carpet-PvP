package carpet.pvp.selftest;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.mace.MaceChoice;
import carpet.pvp.mace.MaceLaunch;
import carpet.pvp.mace.MaceSwap;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.SmashTiming;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** The mace style: the height a launch gains, the smash that comes down, and the launch that misses. */
final class MaceScenarios
{
    /** Blocks of launch height the game may differ from the model by, the slack the model's burst leaves. */
    private static final double HEIGHT_TOLERANCE = MaceLaunch.HEIGHT_SLACK;
    /**
     * How far a measured smash may sit from the model's, as a share of it. The swing is resolved inside
     * the tick after the one the scenario samples, and the game counts that tick's descent and its own
     * read of the cooldown, which together move a long fall's bonus by a few percent.
     */
    private static final float DAMAGE_TOLERANCE = 0.15F;
    /** Share of the model's damage below which a swing was cut down by the invulnerability window. */
    private static final float INVULNERABLE_SHARE = 0.4F;
    /** Distance the fighters of the duel start apart, close enough that the model reaches for a launch. */
    private static final double DUEL_RANGE = 3.5;
    /** Rounds of the duel, half of them with the mace bot on the far side. */
    private static final int DUELS = 6;
    /** Rounds the mace bot has to win for the duel to pass. */
    private static final int DUELS_TO_WIN = 4;
    /** Ticks one round may take before it is called a draw. */
    private static final int DUEL_TICKS = 600;
    /** Knockdowns on one side that end a round: a round is a knockout, whoever hits it first. */
    private static final int KNOCKDOWNS = 1;
    /** How much more damage than it took a fighter has to trade to win a round nobody was put down in. */
    private static final float TRADE_MARGIN = 1.25F;
    /** Health a fake player jumps back up by when it is put back on its feet after a killing blow. */
    private static final float KNOCKDOWN_JUMP = 1.5F;
    /** Height the dummy of the miss is lifted to, out of reach of anything a launch reaches. */
    private static final double OUT_OF_ARC = 14.0D;
    /** Height above the ground that only a launch reaches, a jump peaking at 1.25 blocks. */
    private static final double LAUNCH_HEIGHT = 2.5;

    private MaceScenarios() {}

    /**
     * A wind charge thrown at the bot's own feet lifts the bot as high as the model's arc and no higher.
     * The model puts the burst a quarter of a block above the ground face, while the game bursts where the
     * centre of the charge is when its half-block box touches down, so the real launch comes up
     * {@link MaceLaunch#HEIGHT_SLACK} blocks under the model; anything past that is a real difference.
     * The charge is thrown by hand here, so what this measures is the model and not the style using it.
     */
    static Scenario launchHeight(String a, String b, String c, Vec3 origin)
    {
        int[] phase = {0};
        double[] apex = {origin.y};
        return new Scenario(400, List.of(new Bot(a, origin)), List.of(), server ->
        {
            ServerPlayer bot = SelfTest.player(server, a);
            if (bot == null) return SelfTest.pending(a + " has not joined yet");
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a)) return SelfTest.pending(a + " is still loading");
                SelfTest.run(server, "bot kit give " + a + " mace");
                if (charges(bot) <= 0) return SelfTest.pending(a + " has no wind charges yet");
                SelfTest.run(server, "player " + a + " hotbar 3");
                SelfTest.run(server, "player " + a + " look down");
                phase[0] = 1;
                return SelfTest.pending(a + " is looking down at its own feet");
            }
            if (phase[0] == 1)
            {
                if (bot.getXRot() < 85.0F) return SelfTest.pending(a + " has not looked down yet");
                SelfTest.run(server, "player " + a + " use once");
                phase[0] = 2;
                return SelfTest.pending(a + " threw a wind charge at its feet");
            }
            apex[0] = Math.max(apex[0], bot.getY());
            if (!bot.onGround() || apex[0] < origin.y + 0.5)
            {
                return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks above the ground", a, apex[0] - origin.y));
            }
            double reached = apex[0] - origin.y;
            boolean ok = reached <= MaceLaunch.APEX[0] + 0.05 && reached >= MaceLaunch.APEX[0] - HEIGHT_TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "a wind charge thrown at its own feet lifted %s %.3f blocks; the model gives %.3f, and the game"
                            + " bursts a little higher off the ground than the model assumes, which is the %.3f it"
                            + " falls short by", a, reached, MaceLaunch.APEX[0], MaceLaunch.APEX[0] - reached));
        });
    }

    /**
     * A smash out of a measured fall deals what {@link CombatMath} says for that fall and that armour.
     * The fall distance is the one the bot had on the tick before the hit, since the mace clears its own
     * counter on the hit, and the enchantments are read off the mace the bot was holding.
     */
    static Scenario smashDamage(String a, String b, String c, Vec3 origin)
    {
        Vec3 dummy = origin.add(0.0D, 0.0D, 4.5D);
        boolean[] ready = {false};
        float[] before = {20.0F};
        double[] fall = {0.0};
        int[] since = {0};
        int[] clicksSeen = {-1};
        int[] density = {0};
        int[] breach = {0};
        float[] grounded = {0.0F};
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, dummy, 180.0D)), List.of(), server ->
        {
            if (!ready[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return new Probe(false, SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                maceBot(server, a, "expert");
                SelfTest.run(server, "player " + b + " equip chest minecraft:netherite_chestplate");
                // A dummy that cannot die keeps the bot landing smashes instead of killing it once and
                // being put back at its spawn point with its health and its position made new.
                topUp(server, b);
                ready[0] = true;
                return new Probe(false, SelfTest.fmt("%s is four and a half blocks from the chestplate of %s", a, b));
            }
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer target = SelfTest.player(server, b);
            // What the bot looked like on the tick before this one is what it swung with: the fall distance
            // it had, the click that charged it, the mace it held and the target's health before the hit.
            double wasFall = fall[0];
            int wasSince = since[0];
            int wasDensity = density[0];
            int wasBreach = breach[0];
            float hurt = before[0] - target.getHealth();
            ItemStack held = bot.getMainHandItem();
            int clicks = SelfTest.stats(bot).clicks;
            since[0] = clicks == clicksSeen[0] ? since[0] + 1 : 0;
            clicksSeen[0] = clicks;
            fall[0] = bot.fallDistance;
            density[0] = level(server, held, "density");
            breach[0] = level(server, held, "breach");
            before[0] = target.getHealth();
            if (hurt <= 0.0F)
            {
                return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks up, %s has %.1f health", a,
                        bot.getY() - origin.y, b, target.getHealth()));
            }
            if (wasFall <= CombatMath.SMASH_FALL_THRESHOLD)
            {
                // A hit off the ground is the sword the bot falls back on between launches.
                grounded[0] += hurt;
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f off the ground, still waiting for the smash",
                        a, b, hurt));
            }
            float scale = CombatMath.chargeScale(wasSince, SmashTiming.MACE_ATTACK_SPEED);
            // A fake player crits on any swing made while falling: Player_fakePlayerCritMixin takes the
            // charge gate out of canCriticalAttack for bots, so only the fall decides the crit.
            boolean crit = CombatMath.isCritical(true, false, false, false, false, false, true, false, true);
            float raw = CombatMath.attackDamage((float) SmashTiming.MACE_BASE_DAMAGE,
                    CombatMath.maceSmashBonus(wasFall, false, wasDensity), 0.0F, scale, crit);
            float armor = target.getArmorValue();
            float toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            float model = CombatMath.damageAfterDefences(raw, armor, toughness, wasBreach, 0.0F);
            if (hurt < model * INVULNERABLE_SHARE)
            {
                // The dummy was still inside the invulnerability window of an earlier hit, so this swing
                // was thrown away. The next one that gets through is the one to measure.
                return SelfTest.pending(SelfTest.fmt("the swing of %.2f was cut to %.2f by the invulnerability window",
                        model, hurt));
            }
            boolean ok = Math.abs(hurt - model) <= model * DAMAGE_TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "%s fell %.2f blocks and hit %s for %.2f with a mace of density %d and breach %d at charge %.2f"
                            + " against %.0f armour and %.0f toughness; CombatMath gives %.2f, a difference of %.2f"
                            + " (%.1f%%), after %.1f of hits taken off the ground", a, wasFall, b, hurt, wasDensity,
                    wasBreach, scale, armor, toughness, model, hurt - model, 100.0 * (hurt - model) / model, grounded[0]));
        });
    }

    /**
     * The stun slam. A raised shield has to be taken off with an axe first, and the mace then comes down
     * inside the hundred ticks the axe put it on cooldown for. The axe hit is eaten by the shield, so any
     * damage the target takes after the break is the smash.
     */
    static Scenario stunSlam(String a, String b, String c, Vec3 origin)
    {
        Vec3 holder = origin.add(0.0D, 0.0D, 2.5D);
        int[] phase = {0};
        int[] held = {0};
        boolean[] broken = {false};
        float[] afterAxe = {20.0F};
        float[] lowest = {20.0F};
        boolean[] withMace = {false};
        boolean[] wasBlocking = {false};
        int[] opened = {0};
        int[] maceOut = {0};
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, holder, 180.0D)), List.of(), server ->
        {
            ServerPlayer target = SelfTest.player(server, b);
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return new Probe(false, SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                SelfTest.shieldKit(b).forEach(command -> SelfTest.run(server, command));
                SelfTest.run(server, "player " + b + " equip chest minecraft:netherite_chestplate");
                SelfTest.run(server, "player " + b + " use continuous");
                phase[0] = 1;
                held[0] = 0;
                return new Probe(false, SelfTest.fmt("%s is raising its shield", b));
            }
            if (phase[0] == 1)
            {
                // A raised shield only starts blocking once it has been up for a few ticks, and a hit in
                // that window neither gets blocked nor puts the shield on cooldown, so the bot is let loose
                // only once the shield is really up.
                if (held[0]++ < SHIELD_UP || !target.isBlocking())
                {
                    return SelfTest.pending(SelfTest.fmt("%s has held its shield up for %d ticks, blocking %s", b,
                            held[0], target.isBlocking()));
                }
                maceBot(server, a, "skilled");
                phase[0] = 2;
                return new Probe(false, SelfTest.fmt("%s is holding its shield up against %s", b, a));
            }
            if (target.isBlocking())
            {
                wasBlocking[0] = true;
            }
            if (!broken[0])
            {
                // The opener is the shield going down: a shield only goes down when a hit that a weapon can
                // block against lands on it while it is already up, which for a raised shield is an axe.
                if (!wasBlocking[0])
                {
                    return SelfTest.pending(b + " has not finished raising its shield yet");
                }
                if (target.isBlocking())
                {
                    return SelfTest.pending(a + " has not got the shield down yet, it landed "
                            + SelfTest.stats(SelfTest.player(server, a)).shieldBreaks + " axe hits");
                }
                broken[0] = true;
                opened[0] = 0;
                afterAxe[0] = target.getHealth();
                lowest[0] = afterAxe[0];
                return SelfTest.pending(SelfTest.fmt("%s took the shield down, %s still has %.1f health", a, b,
                        afterAxe[0]));
            }
            if (opened[0]++ <= INVULNERABLE_TICKS)
            {
                // The axe hit that took the shield down also starts the target's invulnerability window,
                // which cuts whatever the bot throws in the next few ticks; a window that was already open
                // before it is what the stun slam is about.
                return SelfTest.pending(SelfTest.fmt("the shield window is %d ticks old", opened[0]));
            }
            // The mace has to have been out around the tick the damage landed: the bot changes slot as soon
            // as it has nothing left to hit with.
            boolean mace = SelfTest.player(server, a).getMainHandItem().is(Items.MACE);
            maceOut[0] = mace ? MACE_WINDOW : 0;
            if (target.getHealth() < lowest[0])
            {
                lowest[0] = target.getHealth();
                withMace[0] = maceOut[0] > 0;
            }
            float hurt = afterAxe[0] - lowest[0];
            boolean ok = hurt >= MACE_HIT && withMace[0];
            return new Probe(ok, SelfTest.fmt(
                    "%s broke %s's shield with the axe and the mace hit that followed inside the %d tick window"
                            + " took %.2f off the %.1f health it had left; %s landed %d hits and blocked %d ticks", a, b,
                    SmashTiming.AXE_DISABLE_TICKS, hurt, target.getHealth(), a,
                    SelfTest.stats(SelfTest.player(server, a)).hits,
                    SelfTest.stats(SelfTest.player(server, a)).blockTicks));
        });
    }

    /**
     * A launch that cannot land anything still has to come down harmless. The dummy is lifted out of the
     * arc the moment the bot leaves the ground, and the bot has to throw the charge that keeps the fall
     * from costing it health.
     */
    static Scenario noFallDamageOnMiss(String a, String b, String c, Vec3 origin)
    {
        Vec3 dummy = origin.add(0.0D, 0.0D, 4.5D);
        boolean[] ready = {false};
        boolean[] lifted = {false};
        double[] apex = {origin.y};
        int[] spentBefore = {0};
        return new Scenario(900, List.of(new Bot(a, origin), new Bot(b, dummy, 180.0D)), List.of(), server ->
        {
            if (!ready[0])
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return new Probe(false, SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                maceBot(server, a, "expert");
                ready[0] = true;
                return new Probe(false, SelfTest.fmt("%s is four and a half blocks from %s", a, b));
            }
            ServerPlayer bot = SelfTest.player(server, a);
            apex[0] = Math.max(apex[0], bot.getY());
            if (!lifted[0])
            {
                // A jump peaks at 1.25 blocks and a wind charge launch at nearly seven, so the height tells
                // the two apart without having to guess what the bot did.
                if (apex[0] < origin.y + LAUNCH_HEIGHT) return SelfTest.pending(a + " has not launched yet");
                spentBefore[0] = charges(bot);
                SelfTest.run(server, "tp " + b + " ~ ~" + SelfTest.fmt("%.0f", OUT_OF_ARC) + " ~");
                lifted[0] = true;
                return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks up and %s is out of the arc", a,
                        apex[0] - origin.y, b));
            }
            if (!bot.onGround() || bot.getY() > origin.y + 0.5)
            {
                return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks up, coming down", a, bot.getY() - origin.y));
            }
            int spent = spentBefore[0] - charges(bot);
            float fall = bot.getMaxHealth() - bot.getHealth();
            boolean ok = fall <= 0.0F && spent >= 2;
            return new Probe(ok, SelfTest.fmt(
                    "%s reached %.2f blocks on a launch that hit nothing, spent %d of its %d wind charges and came"
                            + " down on %.1f health, so the fall cost it %.2f", a, apex[0] - origin.y, spent,
                    spentBefore[0], bot.getHealth(), fall));
        });
    }

    /**
     * Whether this version still lets a fighter combine one item's attack cooldown with another item's
     * damage. The same dummy is hit twice with the same wait, once with the cooldown charged under the mace
     * and once with it charged under the axe and the mace swapped in on the tick of the hit. The scenario
     * passes either way; what it records is the answer the style obeys.
     */
    static Scenario attributeSwapProbe(String a, String b, String c, Vec3 origin)
    {
        Vec3 dummy = origin.add(0.0D, 0.0D, 2.0D);
        int[] phase = {0};
        int[] chargedFor = {0};
        int[] settle = {0};
        float[] top = {20.0F};
        float[] bottom = {20.0F};
        float[] charged = {0.0F};
        return new Scenario(600, List.of(new Bot(a, origin), new Bot(b, dummy, 180.0D)), List.of(), server ->
        {
            if (phase[0] == 0)
            {
                if (SelfTest.warmingUp(server, a, b))
                {
                    return new Probe(false, SelfTest.fmt("waiting for %s and %s to finish loading", a, b));
                }
                // The axe goes into the hotbar slot the bot holds, the mace into the next free one, so the
                // two can be swapped between on the tick of a hit.
                SelfTest.run(server, "player " + a + " equip mainhand minecraft:iron_axe");
                SelfTest.run(server, "give " + a + " minecraft:mace");
                topUp(server, b);
                phase[0] = 1;
                chargedFor[0] = 0;
                settle[0] = 0;
                return new Probe(false, SelfTest.fmt("%s holds an axe in one hotbar slot and a mace in another", a));
            }
            ServerPlayer target = SelfTest.player(server, b);
            float health = target.getHealth();
            if (phase[0] == 1 || phase[0] == 3)
            {
                if (chargedFor[0]++ == 0)
                {
                    SelfTest.run(server, "player " + a + " hotbar " + (phase[0] == 1 ? 2 : 1));
                    top[0] = target.getMaxHealth();
                    bottom[0] = health;
                    settle[0] = 0;
                }
                int wait = phase[0] == 1 ? MaceSwap.MACE_TICKS : MaceSwap.AXE_TICKS;
                if (chargedFor[0] <= wait)
                {
                    return SelfTest.pending(SelfTest.fmt("charging the %s for %d of %d ticks",
                            phase[0] == 1 ? "mace" : "axe", chargedFor[0], wait));
                }
                if (phase[0] == 3)
                {
                    // The swap the technique is about: the item in hand changes on the tick of the hit.
                    SelfTest.run(server, "player " + a + " hotbar 2");
                }
                SelfTest.run(server, "player " + a + " attack once");
                phase[0]++;
                settle[0] = SETTLE_TICKS;
                chargedFor[0] = 0;
                return SelfTest.pending(a + " swung with the mace in hand");
            }
            if (settle[0] > 0)
            {
                // Whatever the swing was worth, the dummy's health at its lowest over the next few ticks.
                bottom[0] = Math.min(bottom[0], health);
                settle[0]--;
                return SelfTest.pending(SelfTest.fmt("reading what the swing did: %.2f so far",
                        top[0] - bottom[0]));
            }
            float dealt = top[0] - bottom[0];
            if (phase[0] == 2)
            {
                charged[0] = dealt;
                phase[0] = 3;
                chargedFor[0] = 0;
                return SelfTest.pending(SelfTest.fmt("the mace charged under the mace hit for %.2f, now the swap",
                        dealt));
            }
            MaceSwap.record(charged[0], dealt);
            return new Probe(true, SelfTest.fmt(
                    "a mace hit charged under the mace itself did %.2f, and the same wait charged under an axe with"
                            + " the mace swapped in on the tick of the hit did %.2f, so on this version the swap is %s",
                    charged[0], dealt, MaceSwap.describe()));
        });
    }

    /**
     * The mace style against the sword style, both expert and both in netherite, with the mace bot on the
     * far side every other round. The rounds are the point of the scenario: the mace bot has to win a
     * clear majority of them.
     */
    static Scenario duel(String a, String b, String c, Vec3 origin)
    {
        int[] round = {-1};
        boolean[] setUp = {false};
        int[] ticks = {0};
        int[] wins = {0};
        int[] losses = {0};
        int[] down = {0};
        int[] downSword = {0};
        float[] lastHealth = {20.0F};
        float[] lastSword = {20.0F};
        float[] maceHits = {0.0F};
        float[] swordHits = {0.0F};
        Probe[] verdict = {null};
        return new Scenario(DUELS * DUEL_TICKS + 900, List.of(), List.of(), server ->
        {
            if (verdict[0] != null)
            {
                // Every round is done: the verdict is repeated until the runner stops on the timeout, which
                // is how a failing scenario's numbers reach the report.
                return verdict[0];
            }
            if (round[0] < 0)
            {
                round[0] = 0;
                setUp[0] = false;
            }
            String maceName = fighter(round[0], "m");
            String swordName = fighter(round[0], "s");
            if (!setUp[0])
            {
                if (!startRound(server, origin, round[0], maceName, swordName))
                {
                    return SelfTest.pending(SelfTest.fmt("spawning the fighters of round %d", round[0]));
                }
                setUp[0] = true;
                return SelfTest.pending(SelfTest.fmt("round %d: %s with the mace kit against %s in netherite",
                        round[0], maceName, swordName));
            }
            ServerPlayer mace = SelfTest.player(server, maceName);
            ServerPlayer sword = SelfTest.player(server, swordName);
            if (mace == null || sword == null)
            {
                return SelfTest.pending(SelfTest.fmt("waiting for the fighters of round %d to log in", round[0]));
            }
            if (SelfTest.warmingUp(server, maceName, swordName))
            {
                return SelfTest.pending(SelfTest.fmt("waiting for the fighters of round %d to finish loading", round[0]));
            }
            // A fake player is put back on its feet with full health a tick after a killing blow instead of
            // lying down, so a round is scored on the knockdowns: the health sitting at its last heart.
            // A fake player is put back on its feet with full health a tick after a killing blow, and the
            // respawn is over before the scenario looks again, so a knockdown is the health jumping back up.
            float maceHealth = mace.getHealth();
            float swordHealth = sword.getHealth();
            if (maceHealth - lastHealth[0] > KNOCKDOWN_JUMP) down[0]++;
            if (swordHealth - lastSword[0] > KNOCKDOWN_JUMP) downSword[0]++;
            lastHealth[0] = maceHealth;
            lastSword[0] = swordHealth;
            if (ticks[0]++ <= DUEL_TICKS && down[0] < KNOCKDOWNS && downSword[0] < KNOCKDOWNS)
            {
                return SelfTest.pending(SelfTest.fmt("round %d after %d ticks: the mace bot has %.1f health and %d"
                        + " knockdowns, the sword bot %.1f and %d", round[0], ticks[0], maceHealth, down[0],
                        swordHealth, downSword[0]));
            }
            // A round is won by putting the other fighter down; when neither went down in the time a round
            // was given, it is won on the damage traded, which is what a bot that cannot finish a fight
            // before both sides are put back on their feet has to be judged on.
            float dealt = (float) SelfTest.stats(mace).damageDealt;
            float taken = (float) SelfTest.stats(mace).damageTaken;
            if (downSword[0] > down[0])
            {
                wins[0]++;
            }
            else if (down[0] > downSword[0])
            {
                losses[0]++;
            }
            else if (dealt > taken * TRADE_MARGIN)
            {
                wins[0]++;
            }
            else if (taken > dealt * TRADE_MARGIN)
            {
                losses[0]++;
            }
            maceHits[0] += (float) SelfTest.stats(mace).damageDealt;
            swordHits[0] += (float) SelfTest.stats(sword).damageDealt;
            SelfTest.run(server, "player " + maceName + " disconnect");
            SelfTest.run(server, "player " + swordName + " disconnect");
            round[0]++;
            ticks[0] = 0;
            down[0] = 0;
            downSword[0] = 0;
            lastHealth[0] = 20.0F;
            lastSword[0] = 20.0F;
            setUp[0] = false;
            if (round[0] >= DUELS)
            {
                verdict[0] = new Probe(wins[0] >= DUELS_TO_WIN, SelfTest.fmt(
                        "the expert mace bot won %d of %d rounds against the expert sword bot in netherite, %d went the"
                                + " other way and the rest ran out of time; it put %.0f damage into the sword bot and took"
                                + " %.0f", wins[0], DUELS, losses[0], maceHits[0], swordHits[0]));
            }
            return SelfTest.pending(SelfTest.fmt("%d of %d rounds won so far", wins[0], DUELS));
        });
    }

    /** Spawns one round of the duel and hands both bots their kit and their difficulty. */
    private static boolean startRound(MinecraftServer server, Vec3 origin, int round, String maceName,
            String swordName)
    {
        Vec3 spot = origin.add(0.0D, 0.0D, round * 40.0D);
        Vec3 near = round % 2 == 0 ? spot : spot.add(0.0D, 0.0D, DUEL_RANGE);
        Vec3 far = round % 2 == 0 ? spot.add(0.0D, 0.0D, DUEL_RANGE) : spot;
        SelfTest.run(server, "player " + maceName + " spawn at " + SelfTest.coords(near)
                + " facing 0 0 in minecraft:overworld in survival");
        SelfTest.run(server, "player " + swordName + " spawn at " + SelfTest.coords(far)
                + " facing 180 0 in minecraft:overworld in survival");
        ServerPlayer mace = SelfTest.player(server, maceName);
        ServerPlayer sword = SelfTest.player(server, swordName);
        if (mace == null || sword == null)
        {
            return false;
        }
        SelfTest.run(server, "bot kit give " + maceName + " mace");
        SelfTest.run(server, "bot kit give " + swordName + " smp");
        maceBot(server, maceName, "expert");
        SelfTest.run(server, "bot option " + swordName + " difficulty expert");
        // A round is decided by a knockout, so neither side gets the totems or the apples that would
        // otherwise keep both of them alive long past anything a duel can show.
        for (String name : List.of(maceName, swordName))
        {
            SelfTest.run(server, "clear " + name + " minecraft:totem_of_undying");
            SelfTest.run(server, "clear " + name + " minecraft:golden_apple");
        }
        SelfTest.run(server, "bot duel " + maceName + " " + swordName);
        return true;
    }

    private static String fighter(int round, String side)
    {
        return "SelfM" + round + side;
    }

    /** Turns a bot of the scenario into a mace fighter of the given difficulty. */
    private static void maceBot(MinecraftServer server, String name, String difficulty)
    {
        SelfTest.run(server, "bot kit give " + name + " mace");
        SelfTest.run(server, "bot option " + name + " combatstyle mace");
        SelfTest.run(server, "bot option " + name + " difficulty " + difficulty);
        SelfTest.run(server, "bot option " + name + " combat true");
        if (SelfTest.player(server, name) instanceof EntityPlayerMPFake fake)
        {
            BotPvpConfig cfg = fake.getPvpConfig();
            if (!cfg.combat || cfg.combatStyle != BotPvpConfig.CombatStyle.MACE)
            {
                SelfTest.log(server, name + " did not come up as a mace fighter: " + cfg.describe());
            }
        }
    }

    /**
     * Gives a dummy a health pool it cannot die in, so that the bot has to keep landing smashes instead
     * of killing it once and being put back at its spawn point with its health and its position made new.
     * The attribute command takes an entity selector that does not accept a bare fake player name, so the
     * entity is found by name and the value is set on the attribute itself.
     */
    private static void topUp(MinecraftServer server, String name)
    {
        ServerPlayer dummy = SelfTest.player(server, name);
        if (dummy == null) return;
        AttributeInstance maxHealth = dummy.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null)
        {
            maxHealth.setBaseValue(1000.0);
            dummy.setHealth((float) maxHealth.getValue());
        }
    }

    /** Ticks a swing of the probe is given to show up in the dummy's health. */
    private static final int SETTLE_TICKS = 4;
    /** Health a mace has to take off a target before the hit counts as the mace's own. */
    private static final float MACE_HIT = 2.5F;
    /** Ticks a shield window has to have been open before a swing in it is worth measuring. */
    private static final int INVULNERABLE_TICKS = 22;
    /** Ticks around a swing the bot still has to have the mace out for it to count. */
    private static final int MACE_WINDOW = 3;
    /** Ticks a shield is given to come all the way up before the bot is let at it. */
    private static final int SHIELD_UP = 12;

    /** Wind charges left in the bot's hotbar, which is where the mace kit keeps them. */
    private static int charges(ServerPlayer bot)
    {
        for (int slot = 0; slot < 9; slot++)
        {
            if (bot.getInventory().getItem(slot).is(Items.WIND_CHARGE))
            {
                return bot.getInventory().getItem(slot).getCount();
            }
        }
        return 0;
    }

    /** Level of an enchantment on a stack, for the mace the bot chose to swing. */
    private static int level(MinecraftServer server, ItemStack stack, String name)
    {
        RegistryAccess registries = server.registryAccess();
        Holder<Enchantment> enchantment = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse("minecraft:" + name)).orElse(null);
        return enchantment == null ? 0 : stack.getEnchantments().getLevel(enchantment);
    }
}
