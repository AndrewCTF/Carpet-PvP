package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotStats;
import carpet.pvp.Perception;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.OpponentModel;
import carpet.pvp.sim.RollingHorizon;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Random;
import java.util.UUID;

/**
 * Sword fighting: a duel simulation decides what the bot does, the body carries it out.
 *
 * <p>Each tick the fight is written into a {@link DuelSim} from what perception knows — the bot's own
 * numbers from its equipment and attributes, the target's from what it holds and wears — and the
 * {@link RollingHorizon} planner picks the next action within this bot's share of the server's
 * simulation budget. On top of the plan the style does what a player does around the fight: it walks
 * the distance with the navigation controller until the target is close, it taps a shield up in front
 * of an incoming hit and puts it down to swing, and it swaps to an axe to break the target's shield.
 * A technique a difficulty forbids is removed from what the planner may choose, so a bot that may not
 * jump never plans a jump.</p>
 */
public final class SwordStyle implements BotStyle
{
    /** Ticks of warning a bot needs before it blocks a hit it can see coming. */
    private static final int BLOCK_REACTION_TICKS = 3;
    /** Distance in blocks at which a target could be about to hit, shield or not. */
    private static final double INCOMING_RANGE = 3.6;
    /** Horizontal speed above which a fighter counts as walking, blocks per tick. */
    private static final double WALKING_SPEED = 0.05;
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};

    private final EntityPlayerMPFake bot;
    private final BotStats stats;
    private final OpponentModel model = new OpponentModel();
    private final DuelSim sim = new DuelSim();

    private Techniques techniques;
    private RollingHorizon planner;
    private int horizon;
    private int population;
    private UUID observed;
    private int axeSlot = -1;
    private int swordSlot = -1;
    private boolean sprintLocked;
    private int sprintLock;
    private int lastAction = DuelSim.NOOP;
    private UUID currentTarget;

    public SwordStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        reconfigure(cfg, random);
    }

    @Override
    public void reconfigure(BotPvpConfig cfg)
    {
        reconfigure(cfg, null);
    }

    private void reconfigure(BotPvpConfig cfg, Random random)
    {
        if (techniques == null
                || techniques.jumpCrits() != cfg.critical
                || techniques.strafing() != cfg.strafe
                || techniques.wtap() != cfg.wtap
                || techniques.shield() != cfg.shieldPlay)
        {
            techniques = new Techniques(cfg.critical, cfg.strafe, cfg.wtap, cfg.shieldPlay);
            if (planner != null)
            {
                planner.setActionFilter(techniques);
            }
        }
        if (planner == null || cfg.plannerHorizon != horizon || cfg.plannerPopulation != population)
        {
            horizon = cfg.plannerHorizon;
            population = cfg.plannerPopulation;
            planner = new RollingHorizon(horizon, population,
                    new Random(random == null ? bot.getRandom().nextLong() : random.nextLong()))
                    .setActionFilter(techniques);
        }
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        Perception.Snapshot seen = perception.target(cfg.reactionDelay + cfg.pingTicks);
        Perception.Snapshot me = perception.self();
        if (seen == null || !seen.seen)
        {
            body.hold(null, target);
            return;
        }
        currentTarget = target.getUUID();
        sprintLocked = body.sprintLocked();
        sprintLock = techniques.sprintLock();
        chooseWeapon(body, cfg, seen, target);

        if (horizontal(me, seen) > cfg.plannerRange)
        {
            approach(body, pack, cfg, target, seen);
            return;
        }
        if (pack.isNavEnabled())
        {
            pack.stopNavigation();
        }
        observeOpponent(perception, seen);
        int action = plan(me, seen);
        body.tick(seen, target, action, wantsBlock(body, me, seen, action));
        techniques.tick();
    }

    @Override
    public void disengage(BotBody body)
    {
        model.reset();
        observed = null;
    }

    /** Spends this bot's share of the server's simulation budget on the next action. */
    private int plan(Perception.Snapshot me, Perception.Snapshot seen)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < horizon)
        {
            stats.starvedTicks++;
            return DuelSim.action(DuelSim.forward(lastAction), DuelSim.strafe(lastAction),
                    false, DuelSim.sprint(lastAction), false);
        }
        fill(me, seen);
        sim.a.sprintLocked = sprintLocked;
        int action = planner.plan(sim, 0, model, share);
        budget.spend(planner.ticksUsed());
        stats.plannerCalls++;
        stats.simulatedTicks += planner.ticksUsed();
        action = withSwing(action);
        lastAction = action;
        if (DuelSim.attack(action) && me.sprinting)
        {
            techniques.lockSprint(techniques.wtap() ? 1 : Techniques.NO_TAP_LOCK_TICKS);
            sprintLock = techniques.sprintLock();
        }
        return action;
    }

    /**
     * Swings when the swing is charged and the target is in reach. The simulation knows nothing about
     * shields or about a target that cannot be hurt yet, so a target that shrugs the hits off teaches the
     * planner that swinging is pointless; a player still takes the swing, and so does the bot.
     */
    private int withSwing(int action)
    {
        if (DuelSim.attack(action) || !DuelSim.inReach(sim.a, sim.b) || sim.a.ticksSinceSwing < sim.a.gateTicks)
        {
            return action;
        }
        // A bot that swings from the ground goes in with the sprint, walking forward as it does, which
        // is what makes the hit a sprint hit; the game then clears its sprint and the W-tap has to undo
        // that. A bot that may not tap sprint stays out of sprint altogether, and a bot that is already
        // in the air keeps the jump the planner gave it, since sprinting there would cost it the crit.
        boolean goIn = techniques.wtap() && sprintLock == 0 && sim.a.onGround && !DuelSim.jump(action);
        return DuelSim.action(goIn ? 1 : DuelSim.forward(action), DuelSim.strafe(action),
                DuelSim.jump(action), goIn, true);
    }

    /**
     * The navigation controller closes the distance while the target is out of the planner's range.
     * The body keeps looking at it but drives no movement of its own.
     */
    private void approach(BotBody body, EntityPlayerActionPack pack, BotPvpConfig cfg, LivingEntity target,
            Perception.Snapshot seen)
    {
        if (!target.getUUID().equals(pack.getNavChaseTarget()))
        {
            pack.setNavChase(target.getUUID(), false, cfg.meleeRange, cfg.attackCooldown);
            // The chase starts the action pack's own attack, which would hit the target without the aim
            // gate of the body; this style is the only one that decides when a click is a hit.
            pack.start(EntityPlayerActionPack.ActionType.ATTACK, null);
        }
        body.hold(seen, target);
        techniques.tick();
    }

    /** Picks the axe while the target is blocking behind a shield, and the sword back afterwards. */
    private void chooseWeapon(BotBody body, BotPvpConfig cfg, Perception.Snapshot seen, LivingEntity target)
    {
        int wanted = -1;
        if (cfg.shieldBreak)
        {
            if (axeSlot < 0)
            {
                axeSlot = findAxe();
                swordSlot = firstSlot(Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD,
                        Items.GOLDEN_SWORD, Items.WOODEN_SWORD);
            }
            boolean blocked = seen.blocking || target.isBlocking();
            if (blocked && axeSlot >= 0)
            {
                wanted = axeSlot;
            }
            else if (swordSlot >= 0)
            {
                wanted = swordSlot;
            }
        }
        else if (swordSlot < 0)
        {
            swordSlot = firstSlot(Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD);
        }
        if (wanted >= 0 && wanted != body.currentSlot() && wanted != body.pendingSlot())
        {
            body.requestSlot(wanted);
        }
    }

    private int findAxe()
    {
        for (Item axe : AXES)
        {
            int slot = BotBody.findSlot(bot, axe);
            if (slot >= 0)
            {
                return slot;
            }
        }
        return -1;
    }

    private int firstSlot(Item... items)
    {
        for (Item item : items)
        {
            int slot = BotBody.findSlot(bot, item);
            if (slot >= 0)
            {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Teaches the opponent model what the target actually did: a swing shows up as its attack charge
     * resetting, and it walks towards the bot whenever its own velocity points that way.
     */
    private void observeOpponent(Perception perception, Perception.Snapshot seen)
    {
        if (observed == null || !observed.equals(currentTarget))
        {
            observed = currentTarget;
            model.reset();
        }
        boolean swung = seen.ticksSinceSwing == 0 && perception.targetHistory().size() > 1;
        double toward = closing(seen);
        model.observe(sim, 1, DuelSim.action(toward > WALKING_SPEED ? 1 : 0, 0, false,
                toward > WALKING_SPEED, swung));
    }

    /** How fast the target moves towards the bot, blocks per tick. */
    private double closing(Perception.Snapshot seen)
    {
        double dx = bot.getX() - seen.x;
        double dz = bot.getZ() - seen.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6)
        {
            return 0.0;
        }
        return (seen.vx * dx + seen.vz * dz) / len;
    }

    /** The shield goes up when the target's charged hit is about to land, unless this bot swings. */
    private boolean wantsBlock(BotBody body, Perception.Snapshot me, Perception.Snapshot seen, int action)
    {
        if (!techniques.shield() || DuelSim.attack(action) || seen.blocking || !body.hasShield())
        {
            return false;
        }
        int gate = CombatMath.minTicksForGate(seen.attackSpeed);
        return seen.ticksSinceSwing >= gate - BLOCK_REACTION_TICKS && horizontal(me, seen) <= INCOMING_RANGE;
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Writes both fighters of the simulation from what perception knows. */
    private void fill(Perception.Snapshot me, Perception.Snapshot seen)
    {
        fill(sim.a, me);
        fill(sim.b, seen);
        // How much the last hit was worth is not observable from outside, so the simulation always
        // takes the hit as a fresh one.
        sim.b.lastHurt = 0.0f;
    }

    private void fill(DuelSim.Fighter fighter, Perception.Snapshot snapshot)
    {
        double yaw = Math.toRadians(snapshot.yaw);
        fighter.x = snapshot.x;
        fighter.y = snapshot.y;
        fighter.z = snapshot.z;
        fighter.vx = snapshot.vx;
        fighter.vy = snapshot.vy;
        fighter.vz = snapshot.vz;
        fighter.sinYaw = -Math.sin(yaw);
        fighter.cosYaw = Math.cos(yaw);
        fighter.onGround = snapshot.onGround;
        fighter.sprinting = snapshot.sprinting;
        fighter.health = snapshot.health;
        fighter.ticksSinceSwing = snapshot.ticksSinceSwing;
        fighter.invulTime = snapshot.invulnerableTime;
        fighter.baseDamage = snapshot.weaponDamage;
        fighter.attackSpeed = snapshot.attackSpeed;
        fighter.armor = snapshot.armor;
        fighter.toughness = snapshot.armorToughness;
        fighter.epf = snapshot.epf;
        fighter.knockbackResistance = snapshot.knockbackResistance;
        fighter.gateTicks = CombatMath.minTicksForGate(snapshot.attackSpeed);
    }
}