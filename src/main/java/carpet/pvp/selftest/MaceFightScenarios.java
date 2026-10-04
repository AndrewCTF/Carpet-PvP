package carpet.pvp.selftest;

import carpet.pvp.BotStats;
import carpet.pvp.mace.MaceGear;
import carpet.pvp.mace.MaceSwap;
import carpet.pvp.selftest.SelfTest.Bot;
import carpet.pvp.selftest.SelfTest.Probe;
import carpet.pvp.selftest.SelfTest.Scenario;
import carpet.pvp.sim.AttributeSwap;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.SmashTiming;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The mace techniques in a live fight: the smash made off another item's cooldown, the Breach swap on the
 * ground, the elytra dive at fighting range, the stun slam taken inside one fall, and the chain off a Wind
 * Burst smash.
 *
 * <p>Each scenario puts one dummy in front of an expert (or skilled) mace bot and measures what the game did
 * against what {@link AttributeSwap} says the same moment was worth. What the bot is holding is read off the
 * same {@link MaceGear} the style reads it from and the same hotbar slot numbers the kit gives, so nothing here
 * assumes a particular item: the kit expectations test is what pins the kit, this is what pins what the bot
 * does with it.</p>
 *
 * <p>Every reading is taken one tick late on purpose. The bot acts at the head of its own tick and the scenario
 * is polled at the head of the server's, so what the scenario sees after a hit is the state the bot left behind:
 * the hotbar as the hit was made and the target's health with the hit already in it. The fall distance and the
 * charge the swing went out with were read on the tick before, which is the tick the bot decided to swing on.</p>
 */
final class MaceFightScenarios
{
    /** How far off a measured hit the model may sit, as a share of it. */
    private static final float TOLERANCE = 0.15F;
    /** Share of the model's damage below which a hit was cut down by the invulnerability window. */
    private static final float INVULNERABLE_SHARE = 0.4F;
    /** Smashes a technique has to be seen making before it is measured. */
    private static final int SMASHES_WANTED = 2;
    /** Ground hits the Breach swap has to be seen making. */
    private static final int HITS_WANTED = 4;
    /** Ticks a swing is given to show up in the dummy's health. */
    private static final int SETTLE = 2;
    /** Ticks a breach swap has to be worth at least, on top of the model's own number. */
    private static final float MIN_GAIN = 0.2F;
    /**
     * Ticks the mace of a stun slam is allowed to take to follow the axe out of the same fall: the shield is
     * down for a hundred, but the hotbar change that puts the mace in the hand takes a tick, and the axe has
     * only just been swung.
     */
    private static final int STUN_GAP_TICKS = 2;

    private MaceFightScenarios() {}

    /**
     * The swap smash: the sword is in the hand through the whole descent, the mace goes in for the tick of the
     * hit and the sword comes straight back. Each hit is checked against {@link AttributeSwap} priced at the
     * sword's own base damage and charge with the fall the bot actually had, and the gap between two of them
     * against the thirty four ticks a mace's own cooldown needs.
     */
    static Scenario swapSmash(String a, String b, String c, Vec3 origin)
    {
        return scenario(a, b, origin, origin.add(0.0D, 0.0D, 4.5D), "skilled", 900, false,
                SelfTest.fmt("%s has the mace kit and %s is four and a half blocks away in a chest plate", a, b),
                new SwapSmash());
    }

    /**
     * The Breach swap on the ground: the hand holds the item the cadence runs on and the Breach mace is put in
     * for the tick of every charged swing, so each hit is worth what the model gives for a Breach swap against
     * that armour and more than the same swing without it.
     */
    static Scenario breachSwap(String a, String b, String c, Vec3 origin)
    {
        // Inside the range a wind charge launch is thrown from, which is where a mace fighter has nothing to
        // launch with and trades instead.
        // Its wind charges are taken away, which is the state a mace fighter on the ground is in: with no
        // launch left the mace does nothing of its own, so every hit it lands is one it made off the sword.
        return scenario(a, b, origin, origin.add(0.0D, 0.0D, 1.0D), "expert", 1800, true, false, false, true,
                SelfTest.fmt("%s has the mace kit without its wind charges and its rockets and is one block from %s,"
                        + " which wears the whole netherite kit", a, b), new BreachSwap());
    }

    /**
     * The elytra dive at fighting range: the wings go on from the hotbar, a rocket or two go off under them,
     * the wings come off again before the hit because a smash does not count while they are open, and the smash
     * lands on the model of the fall the dive actually had.
     */
    static Scenario elytraDive(String a, String b, String c, Vec3 origin)
    {
        return scenario(a, b, origin, origin.add(0.0D, 0.0D, 4.5D), "expert", 1200, false, false, false, true,
                SelfTest.fmt("%s has the mace kit without its wind charges and %s is four and a half blocks away",
                        a, b), new Dive());
    }

    /**
     * The stun slam inside one fall: the axe takes the raised shield down on the way down and the mace lands a
     * tick later out of the same fall, carrying the axe's base damage and charge with the mace's fall bonus on
     * top of it, which is what the swap is worth.
     */
    static Scenario stunSlamOneFall(String a, String b, String c, Vec3 origin)
    {
        // The ground stun slam is a technique of its own, covered by mace_stun_slam, and it would take the
        // shield down on the ground before the bot ever got into the air. Switching it off leaves the bot with
        // nothing to do about a raised shield but the axe on the way down, which is what is being measured.
        return scenario(a, b, origin, origin.add(0.0D, 0.0D, 4.5D), "expert", 1200, true, true, true,
                SelfTest.fmt("%s is raising its shield at %s from four and a half blocks", b, a), new FallStun());
    }

    /**
     * The chain off a Wind Burst smash: the smash carries Wind Burst, so the second hit of the pair is made out
     * of the same fall, without the bot having touched the ground in between.
     */
    static Scenario windBurstChain(String a, String b, String c, Vec3 origin)
    {
        return scenario(a, b, origin, origin.add(0.0D, 0.0D, 3.0D), "expert", 1200, true,
                SelfTest.fmt("%s has the mace kit and %s is three blocks away", a, b), new Chain());
    }

    /**
     * The Breach swap for a sword bot: the same trick the mace style uses on the ground, put in by the sword
     * style at the point it decides to click. The bot is given the mace kit so that its hotbar has a Breach
     * mace and a netherite sword in it, and it fights with the sword style.
     */
    static Scenario swordBreachSwap(String a, String b, String c, Vec3 origin)
    {
        SwordBreach fight = new SwordBreach(a, b);
        return new Scenario(900, List.of(new Bot(a, origin, 0.0D), new Bot(b, origin.add(0.0D, 0.0D, 4.6D),
                180.0D)), List.of(), server -> fight.tick(server));
    }

    /** A sword bot trading with a Breach mace swapped in on the tick of every charged swing. */
    private static final class SwordBreach
    {
        private final String a;
        private final String b;
        private MaceGear gear;

        SwordBreach(String a, String b)
        {
            this.a = a;
            this.b = b;
        }

        private boolean started;
        private int hits;
        private float worst;
        private float leastGain = Float.MAX_VALUE;
        private double dealtBefore;
        private double fallBefore;
        private float chargeBefore;
        private float armor;
        private float toughness;
        private float epf;

        Probe tick(MinecraftServer server)
        {
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer target = SelfTest.player(server, b);
            if (bot == null || target == null)
            {
                return SelfTest.pending("waiting for the fighters to log in");
            }
            if (SelfTest.warmingUp(server, a, b))
            {
                return SelfTest.pending("waiting for the fighters to finish loading");
            }
            if (!started)
            {
                started = true;
                SelfTest.run(server, "bot kit give " + a + " mace");
                SelfTest.run(server, "bot option " + a + " combatstyle sword");
                SelfTest.run(server, "bot option " + a + " difficulty expert");
                SelfTest.run(server, "bot option " + a + " combat true");
                for (String piece : List.of("helmet", "chestplate", "leggings", "boots"))
                {
                    SelfTest.run(server, SelfTest.cmd(b + " equip " + pieceName(piece) + " minecraft:netherite_"
                            + piece));
                }
                ServerPlayer dummy = SelfTest.player(server, b);
                if (dummy.getAttribute(Attributes.MAX_HEALTH) != null)
                {
                    dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
                    dummy.setHealth((float) dummy.getAttributeValue(Attributes.MAX_HEALTH));
                }
                gear = new MaceGear(bot);
                dealtBefore = SelfTest.stats(bot).damageDealt;
                return new Probe(false, SelfTest.fmt("%s is a sword bot with %s and %s in its hotbar, %s wears"
                        + " %.0f armour and %.0f toughness", bot.getName().getString(),
                        gear.breachSlot() >= 0 ? "a Breach mace" : "no Breach mace",
                        gear.chargerSlot() >= 0 ? "a netherite sword" : "no sword", target.getName().getString(),
                        (double) target.getArmorValue(),
                        (double) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS)));
            }
            float hurt = (float) (SelfTest.stats(bot).damageDealt - dealtBefore);
            dealtBefore = SelfTest.stats(bot).damageDealt;
            float scale = chargeBefore;
            double fall = fallBefore;
            armor = target.getArmorValue();
            toughness = (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            epf = protection(target);
            fallBefore = bot.fallDistance;
            chargeBefore = bot.getAttackStrengthScale(0.5F);
            if (hurt <= 0.5F)
            {
                return SelfTest.pending(SelfTest.fmt("%d hit(s) so far; %s is %.2f blocks from %s holding %s",
                        hits, bot.getName().getString(), bot.distanceTo(target), target.getName().getString(),
                        bot.getMainHandItem().getHoverName().getString()));
            }
            int ticks = CombatMath.ticksOfCharge(scale, gear.chargerSpeed());
            boolean crit = CombatMath.isCritical(fall > 0.0, false, false, false, false, false, true, false, true);
            boolean mace = bot.getMainHandItem().is(Items.MACE);
            float breached = AttributeSwap.damage(gear.chargerDamage(), gear.chargerSpeed(), ticks, crit, fall,
                    mace ? 0 : 0, 0.0F, armor, toughness, 4, epf);
            float plain = AttributeSwap.damage(gear.chargerDamage(), gear.chargerSpeed(), ticks, crit, fall, 0, 0.0F,
                    armor, toughness, 0, epf);
            float off = off(hurt, breached);
            worst = Math.max(worst, off);
            leastGain = Math.min(leastGain, hurt - plain);
            hits++;
            boolean ok = hits >= HITS_WANTED && mace && worst <= TOLERANCE && leastGain >= MIN_GAIN;
            return new Probe(ok, SelfTest.fmt("%d hit(s) by %s, the last with %s in the hand: %.2f against the model's"
                    + " %.2f for a Breach swap (%+.1f%%) and %.2f without it; the smallest gain %.2f, worst %.1f%%",
                    hits, bot.getName().getString(), mace ? "a mace" : "something else", hurt, breached,
                    100.0 * (hurt - breached) / Math.max(breached, 0.01F), plain,
                    leastGain == Float.MAX_VALUE ? 0.0F : leastGain, 100.0 * worst));
        }
    }

    // ===== the shared scaffolding =====

    /** One dummy, one mace bot and one check. */
    private static Scenario scenario(String a, String b, Vec3 origin, Vec3 dummy, String difficulty, int timeout,
            boolean armoured, String ready, Check check)
    {
        return scenario(a, b, origin, dummy, difficulty, timeout, armoured, false, ready, check);
    }

    /**
     * The same, with the dummy holding a shield up for the whole fight, which is what a stun slam needs to have
     * something to take down.
     */
    private static Scenario scenario(String a, String b, Vec3 origin, Vec3 dummy, String difficulty, int timeout,
            boolean armoured, boolean shielding, String ready, Check check)
    {
        return scenario(a, b, origin, dummy, difficulty, timeout, armoured, shielding, false, ready, check);
    }

    /**
     * The same, with the ground stun slam switched off as well, which leaves the bot with nothing to do about a
     * raised shield but the axe on the way down.
     */
    private static Scenario scenario(String a, String b, Vec3 origin, Vec3 dummy, String difficulty, int timeout,
            boolean armoured, boolean shielding, boolean noGroundStun, String ready, Check check)
    {
        return scenario(a, b, origin, dummy, difficulty, timeout, armoured, shielding, noGroundStun, false, ready,
                check);
    }

    /**
     * The same, with the bot's wind charges taken away, which is the state a mace fighter is in once it has
     * spent them and what makes a dive worth opening at all.
     */
    private static Scenario scenario(String a, String b, Vec3 origin, Vec3 dummy, String difficulty, int timeout,
            boolean armoured, boolean shielding, boolean noGroundStun, boolean noCharges, String ready, Check check)
    {
        Fighter fight = new Fighter(check, a, b);
        return new Scenario(timeout, List.of(new Bot(a, origin, 0.0D), new Bot(b, dummy, 180.0D)), List.of(),
                server -> fight.tick(server, origin, dummy, difficulty, armoured, shielding, noGroundStun, noCharges,
                        ready));
    }

    /**
     * The readings a technique is judged on, one tick at a time. What the bot was holding and how charged it was
     * is kept for the tick before, and a hit is measured on the tick the target's health comes down by more than
     * a regeneration step.
     */
    private static final class Fighter
    {
        private final Check check;
        private final String a;
        private final String b;
        private MaceGear gear;
        private boolean chargerBefore;
        private double fallBefore;
        private float chargeBefore;
        private double dealtBefore;
        private int clicksSeen = -1;
        private int clickTick = -1;
        private boolean clickAxe;
        private boolean clickBeforeAxe;
        private boolean wasBlocking;
        private boolean started;
        private int tick;

        Fighter(Check check, String a, String b)
        {
            this.check = check;
            this.a = a;
            this.b = b;
        }

        Probe tick(MinecraftServer server, Vec3 origin, Vec3 dummy, String difficulty, boolean armoured,
                boolean shielding, boolean noGroundStun, boolean noCharges, String ready)
        {
            tick++;
            ServerPlayer bot = SelfTest.player(server, a);
            ServerPlayer target = SelfTest.player(server, b);
            if (bot == null || target == null)
            {
                return SelfTest.pending("waiting for the fighters to log in");
            }
            if (SelfTest.warmingUp(server, a, b))
            {
                return SelfTest.pending("waiting for the fighters to finish loading");
            }
            if (!started)
            {
                started = true;
                SelfTest.run(server, "bot kit give " + a + " mace");
                SelfTest.run(server, "bot option " + a + " combatstyle mace");
                SelfTest.run(server, "bot option " + a + " difficulty " + difficulty);
                SelfTest.run(server, "bot option " + a + " combat true");
                if (armoured)
                {
                    for (String piece : List.of("helmet", "chestplate", "leggings", "boots"))
                    {
                        SelfTest.run(server, SelfTest.cmd(b + " equip " + pieceName(piece) + " minecraft:netherite_"
                                + piece));
                    }
                }
                else
                {
                    SelfTest.run(server, SelfTest.cmd(b + " equip chest minecraft:netherite_chestplate"));
                }
                if (noGroundStun)
                {
                    SelfTest.run(server, "bot option " + a + " mace.stunslam false");
                }
                if (noCharges)
                {
                    // A dive is what a mace fighter does once its wind charges are gone: the mace does nothing
                    // on the ground and there is no launch left to put the target on the floor with.
                    SelfTest.run(server, "clear " + a + " minecraft:wind_charge");
                }
                check.prepare(server, a);
                if (shielding)
                {
                    SelfTest.shieldKit(b).forEach(command -> SelfTest.run(server, command));
                    SelfTest.run(server, SelfTest.cmd(b + " use continuous"));
                }
                topUp(server, target);
                gear = new MaceGear(bot);
                dealtBefore = SelfTest.stats(bot).damageDealt;
                return new Probe(false, ready);
            }
            ItemStack held = bot.getMainHandItem();
            BotStats stats = SelfTest.stats(bot);
            int clicks = stats.clicks;
            boolean clicked = clicks != clicksSeen;
            int gap = clickTick < 0 ? -1 : tick - 1 - clickTick;
            if (clicked)
            {
                // Which item each click went out with, so a pair of them a tick apart can be told apart from
                // two of the bot's own ground hits, and which click came before this one.
                clickBeforeAxe = clickAxe;
                clickAxe = held.is(ItemTags.AXES);
                clickTick = tick - 1;
                clicksSeen = clicks;
            }
            // What the swing was worth is read off the bot's own counter, which is the health either side of
            // the attack itself: a dummy on a thousand health regenerates, and its health a tick later says
            // nothing about what the swing did.
            double dealt = stats.damageDealt - dealtBefore;
            dealtBefore = stats.damageDealt;
            float hurt = (float) dealt;
            boolean charger = gear.chargerSlot() >= 0 && held == bot.getInventory().getItem(gear.chargerSlot());
            // Whether the shield was up on the tick before, which is how a hit that only ever brings a shield
            // down is told apart from one the target simply was not holding up for.
            boolean blocking = target.isBlocking();
            Hit hit = new Hit(hurt, fallBefore, chargeBefore, enchantment(bot, held, "density"),
                    enchantment(bot, held, "breach"), chargerBefore,
                    held.is(Items.MACE), clicked && held.is(ItemTags.AXES), clicked && held.is(ItemTags.SWORDS),
                    clickBeforeAxe, gap, bot.onGround(), gear.wearingElytra(), blocking, wasBlocking);
            wasBlocking = blocking;
            chargerBefore = charger;
            fallBefore = bot.fallDistance;
            chargeBefore = bot.getAttackStrengthScale(0.5F);
            if (hurt > 0.5F)
            {
                return check.hit(server, bot, target, hit, tick);
            }
            return check.waiting(bot, target, hit, tick);
        }

        MaceGear gear()
        {
            return gear;
        }
    }

    /** One hit, as the game made it and as the model priced it. */
    private record Hit(float hurt, double fall, float charge, int density, int breach, boolean chargerBefore,
            boolean maceOnHit,
            boolean axeOnHit, boolean swordOnHit, boolean axeBefore, int gap, boolean onGround, boolean wearingElytra,
            boolean blocking, boolean wasBlocking)

    {
        /** True on the tick a raised shield came down, which only an axe hit can do. */
        boolean shieldCameDown()
        {
            return wasBlocking && !blocking;
        }

        boolean smash()
        {
            return fall > CombatMath.SMASH_FALL_THRESHOLD;
        }

        /** Ticks since the last swing, which is what the game's own charge scale was read at. */
        int ticksOf(double attackSpeed)
        {
            return CombatMath.ticksOfCharge(charge, attackSpeed);
        }

        /**
         * A fake player crits on any swing made off the ground, which is the rule its own mixin leaves in place:
         * the fall decides, not the charge gate.
         */
        boolean crit()
        {
            return CombatMath.isCritical(fall > 0.0, false, false, false, false, false, true, false, true);
        }
    }

    /** What one technique looks for. */
    private interface Check
    {
        /** Anything this technique needs taken from the bot before it is let loose. */
        default void prepare(MinecraftServer server, String bot)
        {
        }

        Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick);

        Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick);
    }

    /**
     * The swap smash. Every hit has to have been made with the sword in the hand the tick before and the mace in
     * the hand on the tick of it, be worth what the swap is worth, and two of them have to come round in fewer
     * ticks than a mace's own cooldown needs.
     */
    private static final class SwapSmash implements Check
    {
        private int smashes;
        private int pairs;
        private int lastTick = -1;
        private int pendingFrom = -1;
        private int fastest = Integer.MAX_VALUE;
        private float worst;

        public Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            // What the technique buys is the hand back: the cooldown of the item in the hand comes round in
            // thirteen ticks rather than the thirty four a mace needs, so the bot is ready again far sooner.
            if (pendingFrom >= 0 && bot.getAttackStrengthScale(0.5F) >= 1.0F)
            {
                int back = tick - pendingFrom;
                fastest = Math.min(fastest, back);
                if (back < MaceSwap.MACE_TICKS)
                {
                    pairs++;
                }
                pendingFrom = -1;
            }
            else if (pendingFrom >= 0 && tick - pendingFrom >= MaceSwap.MACE_TICKS)
            {
                pendingFrom = -1;
            }
            return SelfTest.pending(SelfTest.fmt(
                    "%d swap smash(es) so far, %s is %.2f blocks up with %.2f of fall holding %s, %d charges,"
                            + " %d clicks, %s has %.1f health", smashes, bot.getName().getString(), bot.getY(),
                    bot.fallDistance, bot.getMainHandItem().getHoverName().getString(), charges(bot),
                    SelfTest.stats(bot).clicks, target.getName().getString(), target.getHealth()));
        }

        public Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (!hit.smash())
            {
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f off the ground, waiting for a smash",
                        bot.getName().getString(), target.getName().getString(), hit.hurt()));
            }
            if (!hit.chargerBefore() || !hit.maceOnHit())
            {
                return SelfTest.pending(SelfTest.fmt("a smash was made with %s in the hand the tick before and %s in"
                        + " the hand on the tick of it, which is not the swap",
                        hit.chargerBefore() ? "the sword" : "another item",
                        hit.maceOnHit() ? "a mace" : "another item"));
            }
            pendingFrom = tick;
            MaceGear gear = new MaceGear(bot);
            float model = swapped(gear, hit, target, hit.breach());
            if (hit.hurt() < model * INVULNERABLE_SHARE)
            {
                return SelfTest.pending(SelfTest.fmt("the swing of %.2f was cut to %.2f by the invulnerability window",
                        model, hit.hurt()));
            }
            worst = Math.max(worst, off(hit.hurt(), model));
            lastTick = tick;
            smashes++;
            boolean ok = smashes >= SMASHES_WANTED && pairs >= SMASHES_WANTED - 1 && worst <= TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "%d swap smash(es) with the sword held through the descent and the mace swapped in on the tick of"
                            + " the hit: %.2f against the model's %.2f (%+.1f%%) out of %.2f of fall at charge %.2f; %d"
                            + " of them back in the hand sooner than the %d ticks a mace needs, the fastest in %d,"
                            + " worst so far %.1f%%", smashes, hit.hurt(), model,
                    100.0 * (hit.hurt() - model) / Math.max(model, 0.01F), hit.fall(), hit.charge(), pairs,
                    MaceSwap.MACE_TICKS, fastest == Integer.MAX_VALUE ? 0 : fastest, 100.0 * worst));
        }
    }

    /** The Breach swap on the ground: every hit through the mace's enchantment and better than the same hit without. */
    private static final class BreachSwap implements Check
    {
        private int hits;
        private float worst;
        private float leastGain = Float.MAX_VALUE;

        public void prepare(MinecraftServer server, String bot)
        {
            // On the ground means with nothing left that gets it off the ground. A bot that still has its
            // rockets puts the wings on once the charges are gone and comes down on the target instead of
            // trading with it, which is the dive and not what is being measured here.
            SelfTest.run(server, "clear " + bot + " minecraft:firework_rocket");
        }

        public Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks from %s holding %s, %s has %.1f health",
                    bot.getName().getString(), bot.distanceTo(target), target.getName().getString(),
                    bot.getMainHandItem().getHoverName().getString(), target.getName().getString(),
                    target.getHealth()));
        }

        public Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (hit.smash())
            {
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f out of a fall of %.2f, waiting for a ground hit",
                        bot.getName().getString(), target.getName().getString(), hit.hurt(), hit.fall()));
            }
            if (!hit.maceOnHit())
            {
                return SelfTest.pending(SelfTest.fmt("a ground hit was made with %s in the hand, which is not the"
                        + " Breach swap", hit.maceOnHit() ? "a mace" : "the sword"));
            }
            MaceGear gear = new MaceGear(bot);
            float model = swapped(gear, hit, target, hit.breach());
            float plain = swapped(gear, hit, target, 0);
            worst = Math.max(worst, off(hit.hurt(), model));
            leastGain = Math.min(leastGain, hit.hurt() - plain);
            hits++;
            boolean ok = hits >= HITS_WANTED && worst <= TOLERANCE && leastGain >= MIN_GAIN;
            return new Probe(ok, SelfTest.fmt(
                    "%d ground hit(s) with the Breach mace swapped in on the tick of each: %.2f against the model's %.2f"
                            + " (%+.1f%%) and %.2f without the swap; the smallest gain so far %.2f, worst %.1f%%", hits,
                    hit.hurt(), model, 100.0 * (hit.hurt() - model) / Math.max(model, 0.01F), plain,
                    leastGain == Float.MAX_VALUE ? 0.0F : leastGain, 100.0 * worst));
        }
    }

    /**
     * The dive. The wings have to have gone on and come off again, the bot has to have been flying, the hit has
     * to have been made with the wings already folded, and it has to be worth what the fall was worth.
     */
    private static final class Dive implements Check
    {
        private int flying;
        private boolean flew;
        private int dived;
        private float worst;

        public Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (bot.isFallFlying())
            {
                flew = true;
                flying++;
            }
            return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks up, %s, holding %s", bot.getName().getString(),
                    bot.getY(), bot.isFallFlying() ? "gliding" : "falling",
                    bot.getMainHandItem().getHoverName().getString()));
        }

        public Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (!hit.smash())
            {
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f off the ground",
                        bot.getName().getString(), target.getName().getString(), hit.hurt()));
            }
            if (!flew || bot.isFallFlying())
            {
                return SelfTest.pending(SelfTest.fmt("a smash was made after %d gliding tick(s) and the wings are %s"
                        + " open now: a smash does not count while they are", flying,
                        bot.isFallFlying() ? "still" : "not"));
            }
            MaceGear gear = new MaceGear(bot);
            float model = swapped(gear, hit, target, hit.breach());
            worst = Math.max(worst, off(hit.hurt(), model));
            dived++;
            boolean wingsOff = !hit.wearingElytra();
            boolean ok = dived >= 1 && wingsOff && worst <= TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "%d dive(s): %s flew %d tick(s), folded the wings and smashed out of %.2f of fall for %.2f against"
                            + " the model's %.2f (%+.1f%%), wearing %s at the end", dived,
                    bot.getName().getString(), flying, hit.fall(), hit.hurt(), model,
                    100.0 * (hit.hurt() - model) / Math.max(model, 0.01F),
                    hit.wearingElytra() ? "the elytra" : "its chest plate"));
        }
    }

    /**
     * The stun slam inside one fall. The axe is the only thing that can take a raised shield down, so the shield
     * going is the axe landing, and the mace has to follow it out of the same fall: within the tick or two the
     * hotbar change takes, with at least as much fall as the axe had, for what the swap off the axe's charge is
     * worth.
     */
    private static final class FallStun implements Check
    {
        private int pairs;
        private float worst;
        private int brokenTick = -1;
        private double axeFall;

        public Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (hit.shieldCameDown())
            {
                brokenTick = tick;
                axeFall = hit.fall();
                return SelfTest.pending(SelfTest.fmt("the axe took the shield down out of a fall of %.2f", axeFall));
            }
            return SelfTest.pending(SelfTest.fmt("%d pair(s) so far; %s is %.2f blocks up with %.2f of fall holding"
                    + " %s, %s is %sblocking", pairs, bot.getName().getString(), bot.getY(), bot.fallDistance,
                    bot.getMainHandItem().getHoverName().getString(), target.getName().getString(),
                    hit.blocking() ? "" : "not "));
        }

        public Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            int after = brokenTick < 0 ? -1 : tick - brokenTick;
            if (brokenTick < 0 || !hit.smash() || after > STUN_GAP_TICKS || hit.fall() < axeFall)
            {
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f out of %.2f of fall, %s", bot.getName().getString(),
                        target.getName().getString(), hit.hurt(), hit.fall(), brokenTick < 0 ? "with no shield window open"
                        : SelfTest.fmt("%d tick(s) after the shield came down, which is not the same fall", after)));
            }
            MaceGear gear = new MaceGear(bot);
            // The mace lands a tick or two after the axe, so it is charged at whatever the axe click left behind,
            // which is the scale the game itself reported on the tick before.
            float model = offAxe(gear, hit, target);
            worst = Math.max(worst, off(hit.hurt(), model));
            pairs++;
            brokenTick = -1;
            boolean ok = pairs >= 1 && worst <= TOLERANCE;
            return new Probe(ok, SelfTest.fmt(
                    "%d stun slam(s) inside one fall: the axe took the shield down out of %.2f of fall and the mace"
                            + " landed %d tick(s) later out of %.2f for %.2f against the model's %.2f (%+.1f%%), worst %.1f%%",
                    pairs, axeFall, after, hit.fall(), hit.hurt(), model,
                    100.0 * (hit.hurt() - model) / Math.max(model, 0.01F), 100.0 * worst));
        }
    }

    /**
     * The chain off a Wind Burst smash. The second hit of a pair has to be made out of the same fall as the
     * first, which means the bot never touched the ground in between.
     */
    private static final class Chain implements Check
    {
        private int smashes;
        private int pairs;
        private boolean grounded = true;
        private double firstFall;
        private double lastFall;
        private int firstTick = -1;

        public Probe waiting(ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (bot.onGround())
            {
                grounded = true;
            }
            return SelfTest.pending(SelfTest.fmt("%s is %.2f blocks up with %.2f of fall, %s has %.1f health",
                    bot.getName().getString(), bot.getY(), bot.fallDistance, target.getName().getString(),
                    target.getHealth()));
        }

        public Probe hit(MinecraftServer server, ServerPlayer bot, ServerPlayer target, Hit hit, int tick)
        {
            if (!hit.smash())
            {
                return SelfTest.pending(SelfTest.fmt("%s hit %s for %.2f off the ground",
                        bot.getName().getString(), target.getName().getString(), hit.hurt()));
            }
            smashes++;
            if (!grounded && !hit.onGround())
            {
                if (firstTick < 0)
                {
                    firstFall = hit.fall();
                }
                pairs++;
                lastFall = hit.fall();
            }
            else
            {
                firstTick = -1;
            }
            grounded = hit.onGround();
            boolean ok = pairs >= 1 && smashes >= SMASHES_WANTED;
            return new Probe(ok, SelfTest.fmt(
                    "%d smash(es) out of the wind burst mace, %d of them in a row without touching the ground"
                            + " (falls of %.2f and %.2f)", smashes, pairs, firstFall, lastFall));
        }
    }

    // ===== the model a hit is priced against =====

    /**
     * What a swap is worth: the base damage and the charge of the item the hand held before it, with the fall
     * and the enchantments of the item that was in the hand on the tick of the hit added on top.
     */
    private static float swapped(MaceGear gear, Hit hit, ServerPlayer target, int breach)
    {
        return AttributeSwap.damage(gear.chargerDamage(), gear.chargerSpeed(), hit.ticksOf(gear.chargerSpeed()),
                hit.crit(), hit.fall(), hit.density(), 0.0F, target.getArmorValue(),
                (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS), breach, protection(target));
    }

    /** The same, off the axe's charge and base damage, which is what the mace of a stun slam is made off. */
    private static float offAxe(MaceGear gear, Hit hit, ServerPlayer target)
    {
        return AttributeSwap.damage(gear.axeDamage(), gear.axeSpeed(), hit.ticksOf(gear.axeSpeed()), hit.crit(),
                hit.fall(), hit.density(), 0.0F, target.getArmorValue(),
                (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS), hit.breach(), protection(target));
    }

    /** Protection level of everything the target wears, which is what its epf comes from. */
    private static float protection(ServerPlayer target)
    {
        int levels = 0;
        for (EquipmentSlot slot : EquipmentSlot.values())
        {
            levels += enchantment(target, target.getItemBySlot(slot), "protection");
        }
        return CombatMath.epf(levels, 0, false);
    }

    /** Wind charges left in the hotbar, which is where the mace kit keeps them. */
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

    private static void topUp(MinecraftServer server, ServerPlayer target)
    {
        if (target.getAttribute(Attributes.MAX_HEALTH) == null)
        {
            return;
        }
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000.0);
        target.setHealth((float) target.getAttributeValue(Attributes.MAX_HEALTH));
    }

    private static String pieceName(String piece)
    {
        return switch (piece)
        {
            case "helmet" -> "head";
            case "chestplate" -> "chest";
            case "leggings" -> "legs";
            default -> "feet";
        };
    }

    /** Level of an enchantment on a stack, from the registry the server is running. */
    private static int enchantment(ServerPlayer any, ItemStack stack, String name)
    {
        RegistryAccess registries = any.level().registryAccess();
        Holder<Enchantment> enchantment = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(Identifier.parse("minecraft:" + name)).orElse(null);
        return enchantment == null ? 0 : stack.getEnchantments().getLevel(enchantment);
    }

    private static float off(float measured, float model)
    {
        return Math.abs(measured - model) / Math.max(model, 0.01F);
    }
}