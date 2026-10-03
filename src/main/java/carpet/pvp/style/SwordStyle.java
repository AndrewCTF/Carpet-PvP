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
import carpet.pvp.sim.PlannerParams;
import carpet.pvp.sim.RollingHorizon;
import net.minecraft.world.entity.LivingEntity;

import java.util.Random;
import java.util.UUID;

/**
 * Sword fighting: a duel simulation decides what the bot does, the body carries it out.
 *
 * <p>Each tick the fight is written into a {@link DuelSim} from what perception knows — the bot's own
 * numbers from its equipment and attributes, the target's from what it holds and wears — and the
 * {@link RollingHorizon} planner picks the next action within this bot's share of the server's simulation
 * budget. The planner runs on the parameters self-play tuning settled on for the bot's difficulty, so a
 * stronger bot searches harder and hits harder.</p>
 *
 * <p>The click is this style's, not the planner's. The planner can only see the world as a model of it, where
 * a swing always lands; the style looks at where it believes the target is now, with its own reaction delay
 * and ping taken out of it, and only asks the body for a click when the swing is charged past the gate and
 * the target is inside the reach and the aim that a real swing needs. Everything else the planner asks for —
 * how to close, how to circle, when to go in with the sprint — it is left free to decide.</p>
 *
 * <p>Around the fight the style does what a player does: it walks the distance with the navigation until the
 * target is close ({@link MeleeApproach}), it taps a shield up in front of an incoming hit and puts it down to
 * swing, it swaps to an axe to break the target's shield ({@link SwordLoadout}), and it throws the occasional
 * deliberate mistake so that it is not a machine.</p>
 */
public final class SwordStyle implements BotStyle
{
    /** Ticks of warning a bot needs before it blocks a hit it can see coming. */
    private static final int BLOCK_REACTION_TICKS = 3;
    /** Distance in blocks at which a target could be about to hit, shield or not. */
    private static final double INCOMING_RANGE = 3.6;
    /** Horizontal speed above which a fighter counts as walking, blocks per tick. */
    private static final double WALKING_SPEED = 0.05;
    /** Half the width of a player, which is what the box a click has to meet is built from. */
    private static final double HITBOX_HALF_WIDTH = 0.3;
    /**
     * How much of the attack range a click leaves unused. The bot believes where the target is rather than
     * where it is, so a click that only just reaches is a click a target that took a step is out of.
     */
    private static final double REACH_MARGIN = 0.4;
    /** Ticks between two deliberate targeting errors, so a bot that is set to make them still fights. */
    private static final int MISTAKE_SPACING = 40;
    /** Blocks to one side a deliberate targeting error puts the aim point, which no swing can reach. */
    private static final double MISTAKE_OFFSET = 4.0D;
    /** Ticks of velocity the aim point is led by, for a target that keeps moving while the view catches up. */
    private static final int LEAD_TICKS = 3;

    private final EntityPlayerMPFake bot;
    private final BotStats stats;
    private final OpponentModel model = new OpponentModel();
    private final DuelSim sim = new DuelSim();
    private final Perception.Snapshot now = new Perception.Snapshot();
    private final MeleeApproach approach;
    private final SwordLoadout loadout;

    private Techniques techniques;
    private RollingHorizon planner;
    private int horizon;
    private int population;
    private double preferredDistance;
    private double distanceWeight;
    private Random random;
    private UUID observed;
    private boolean sprintLocked;
    private int sprintLock;
    private int lastAction = DuelSim.NOOP;
    private UUID currentTarget;
    /** Ticks left of a deliberate targeting error, which is one click thrown at nothing. */
    private int mistake;
    private int mistakeCooldown = MISTAKE_SPACING;
    /** True while a swing is available and no share of an early swing has been thrown for it yet. */
    private boolean swingDue;
    /** True while the bot is holding its shield up against a hit it can see coming. */
    private boolean shielding;

    public SwordStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.stats = body.stats();
        this.random = random;
        this.approach = new MeleeApproach(body);
        this.loadout = new SwordLoadout(body);
        reconfigure(cfg, random);
    }

    @Override
    public void reconfigure(BotPvpConfig cfg)
    {
        reconfigure(cfg, null);
    }

    private void reconfigure(BotPvpConfig cfg, Random fresh)
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
        PlannerParams tuned = cfg.plannerParams();
        int wantHorizon = cfg.plannerHorizon;
        int wantPopulation = cfg.plannerPopulation;
        if (planner == null || wantHorizon != horizon || wantPopulation != population
                || tuned.preferredDistance() != preferredDistance || tuned.distanceWeight() != distanceWeight)
        {
            horizon = wantHorizon;
            population = wantPopulation;
            preferredDistance = tuned.preferredDistance();
            distanceWeight = tuned.distanceWeight();
            // The search size and the weights come from the tuned preset; the horizon and the population are
            // what /player ai and the two botPlanner rules override, so they are put back on top of it.
            PlannerParams params = new PlannerParams(horizon, population, tuned.budgetTicks(),
                    tuned.mutationRate(), tuned.dealtWeight(), tuned.takenWeight(), tuned.distanceWeight(),
                    tuned.preferredDistance());
            planner = new RollingHorizon(params,
                    new Random(fresh == null ? bot.getRandom().nextLong() : fresh.nextLong()))
                    .setActionFilter(techniques);
        }
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        int delay = cfg.reactionDelay + cfg.pingTicks;
        Perception.Snapshot seen = perception.target(delay);
        Perception.Snapshot me = perception.self();
        if (seen == null || !seen.seen)
        {
            body.hold(null, target);
            return;
        }
        currentTarget = target.getUUID();
        sprintLocked = body.sprintLocked();
        sprintLock = techniques.sprintLock();
        lead(perception, seen, delay);
        loadout.choose(cfg, seen, target);
        if (maybeMistake(cfg))
        {
            aimAside();
        }

        if (horizontal(me, seen) > cfg.plannerRange)
        {
            // The body keeps the view on the target and swings nothing; the walk is driven after it, by us
            // while the way is clear and by the navigation controller when it is not.
            body.hold(now, target);
            approach.run(cfg, target, now);
            techniques.tick();
            return;
        }
        approach.stop(pack);
        observeOpponent(perception, seen);
        int action = plan(cfg, me, seen, perception);
        body.tick(now, target, action, wantsBlock(body, me, seen, action));
        techniques.tick();
    }

    @Override
    public void disengage(BotBody body)
    {
        model.reset();
        approach.stop(body.pack());
        observed = null;
        mistake = 0;
    }

    /**
     * Reads where the target is now out of what it looked like {@code delay} ticks ago, which is what the view
     * is put on and what the reach and aim of a click are judged against. Without this the bot would aim at
     * where the target was, and a target that took a step in the meantime would be out of the swing.
     */
    private void lead(Perception perception, Perception.Snapshot seen, int delay)
    {
        now.copyFrom(seen);
        if (delay <= 0)
        {
            return;
        }
        Perception.Ring history = perception.targetHistory();
        Perception.Snapshot before = history.delayed(delay + LEAD_TICKS);
        if (!before.seen)
        {
            return;
        }
        double steps = LEAD_TICKS + 1;
        now.x = seen.x + (seen.x - before.x) * delay / steps;
        now.z = seen.z + (seen.z - before.z) * delay / steps;
        now.vx = (seen.x - before.x) / steps;
        now.vz = (seen.z - before.z) / steps;
    }

    /** Spends this bot's share of the server's simulation budget on the next action. */
    private int plan(BotPvpConfig cfg, Perception.Snapshot me, Perception.Snapshot seen, Perception perception)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < horizon)
        {
            stats.starvedTicks++;
            return DuelSim.action(DuelSim.forward(lastAction), DuelSim.strafe(lastAction),
                    false, DuelSim.sprint(lastAction), false);
        }
        fill(me, seen, perception);
        sim.a.sprintLocked = sprintLocked;
        int action = planner.plan(sim, 0, model, share);
        budget.spend(planner.ticksUsed());
        stats.plannerCalls++;
        stats.simulatedTicks += planner.ticksUsed();
        // What the planner plans with is a model where a swing cannot miss, so the click is taken back out of
        // its action and put back only when this style judges it a hit.
        action = withClick(cfg, DuelSim.action(DuelSim.forward(action), DuelSim.strafe(action),
                DuelSim.jump(action), DuelSim.sprint(action), false), me, seen);
        lastAction = action;
        if (DuelSim.attack(action) && me.sprinting)
        {
            techniques.lockSprint(techniques.wtap() ? 1 : Techniques.NO_TAP_LOCK_TICKS);
            sprintLock = techniques.sprintLock();
        }
        return action;
    }

    /**
     * Puts the click back into the action, or leaves it out.
     *
     * <p>A swing is only worth throwing when it is charged past the gate that guards crits and sprint hits,
     * the target is inside the reach with room to spare, and the view is on it. A bot that may not tap sprint
     * goes in with the sprint from the ground, which is what makes the hit a sprint hit; the game then clears
     * its sprint and the W-tap has to undo that. A bot that already has the jump keeps it, since sprinting in
     * the air would cost it the crit.</p>
     */
    private int withClick(BotPvpConfig cfg, int action, Perception.Snapshot me, Perception.Snapshot seen)
    {
        if (mistake > 0)
        {
            // A deliberate targeting error is a click thrown at nothing, which the body books as a miss.
            mistake--;
            return withAttack(action);
        }
        int gate = CombatMath.minTicksForGate(me.attackSpeed);
        if (me.ticksSinceSwing < gate)
        {
            swingDue = true;
            return action;
        }
        if (swingDue)
        {
            // The share of the swings a level of the ladder throws early, rolled once per swing rather than
            // once per tick: the click still reaches the target, but for a fraction of what it was worth, and
            // the swing timer starts again either way.
            swingDue = false;
            if (fumbles(cfg))
            {
                return withAttack(action);
            }
        }
        if (!inReach(seen) || !onTarget(seen))
        {
            return action;
        }
        boolean goIn = techniques.wtap() && sprintLock == 0 && sim.a.onGround && !DuelSim.jump(action);
        return DuelSim.action(goIn ? 1 : DuelSim.forward(action), DuelSim.strafe(action),
                DuelSim.jump(action), goIn, true);
    }

    private static int withAttack(int action)
    {
        return DuelSim.action(DuelSim.forward(action), DuelSim.strafe(action), DuelSim.jump(action),
                DuelSim.sprint(action), true);
    }

    /** True when this click is one of the share of early swings the difficulty preset makes. */
    private boolean fumbles(BotPvpConfig cfg)
    {
        return cfg.missChance > 0 && random.nextInt(100) < cfg.missChance;
    }

    /**
     * Whether a ray from the eyes along the view the bot has now would meet the target where it believes the
     * target is, which is the same test the game makes before it lets a swing land. Judging it on the view the
     * bot already has, rather than on the one this tick's aim step is about to give it, keeps the click from
     * being taken while the view is still swinging onto the target: a bot that jumps for a crit raises its eyes
     * a whole block, which is twenty degrees of aim at melee range and a click that hits the floor.
     */
    private boolean onTarget(Perception.Snapshot seen)
    {
        double yaw = Math.toRadians(bot.getYRot());
        double pitch = Math.toRadians(bot.getXRot());
        return BotBody.rayHitsBox(bot.getX(), bot.getEyeY(), bot.getZ(),
                -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch),
                seen.x - HITBOX_HALF_WIDTH, seen.y, seen.z - HITBOX_HALF_WIDTH,
                seen.x + HITBOX_HALF_WIDTH, seen.y + seen.height, seen.z + HITBOX_HALF_WIDTH);
    }

    /**
     * Whether the target the bot believes in is inside the attack range with room to spare, measured from the
     * eyes to the nearest corner of its box the way the game measures it.
     */
    private boolean inReach(Perception.Snapshot seen)
    {
        double eye = bot.getEyeY();
        double dx = Math.max(Math.max(seen.x - HITBOX_HALF_WIDTH - bot.getX(),
                bot.getX() - (seen.x + HITBOX_HALF_WIDTH)), 0.0);
        double dy = Math.max(Math.max(seen.y - eye, eye - (seen.y + seen.height)), 0.0);
        double dz = Math.max(Math.max(seen.z - HITBOX_HALF_WIDTH - bot.getZ(),
                bot.getZ() - (seen.z + HITBOX_HALF_WIDTH)), 0.0);
        return dx * dx + dy * dy + dz * dz <= (DuelSim.REACH - REACH_MARGIN) * (DuelSim.REACH - REACH_MARGIN);
    }

    /**
     * Rolls for the occasional deliberate error, which is what keeps a bot from being a machine.
     *
     * @return whether this tick is one of them, in which case the click goes at the wrong place
     */
    private boolean maybeMistake(BotPvpConfig cfg)
    {
        if (mistake > 0)
        {
            mistake--;
            return true;
        }
        if (--mistakeCooldown > 0)
        {
            return false;
        }
        mistakeCooldown = MISTAKE_SPACING + random.nextInt(MISTAKE_SPACING);
        mistake = cfg.mistakeChance > 0 && random.nextInt(100) < cfg.mistakeChance ? 1 : 0;
        return mistake > 0;
    }

    /**
     * Puts the aim point a whole body to one side of where the target is, which is what a player who
     * misjudged a swing ends up doing: the click that follows goes at nothing.
     */
    private void aimAside()
    {
        double dx = now.x - bot.getX();
        double dz = now.z - bot.getZ();
        double flat = Math.max(Math.sqrt(dx * dx + dz * dz), 0.1D);
        now.x += -dz / flat * MISTAKE_OFFSET;
        now.z += dx / flat * MISTAKE_OFFSET;
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

    /**
     * The shield goes up while the target is winding up a hit the bot can see coming, and comes down for its
     * own swing. A player holds the shield up for the whole wind-up rather than flicking it every tick, so a
     * shield that is already up stays up until there is a reason to take it down: the only reasons are the
     * target backing out of range and the bot having a click of its own to throw.
     */
    private boolean wantsBlock(BotBody body, Perception.Snapshot me, Perception.Snapshot seen, int action)
    {
        if (!techniques.shield() || seen.blocking || !body.hasShield())
        {
            shielding = false;
            return false;
        }
        if (DuelSim.attack(action))
        {
            shielding = false;
            return false;
        }
        double flat = horizontal(me, seen);
        int gate = CombatMath.minTicksForGate(seen.attackSpeed);
        if (seen.ticksSinceSwing >= gate - BLOCK_REACTION_TICKS && flat <= INCOMING_RANGE)
        {
            shielding = true;
        }
        // A shield that is up stays up while the target is anywhere near, so a target that backs off and comes
        // back does not cost the bot the whole wind-up again.
        return shielding && flat <= INCOMING_RANGE + 2.0;
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Writes both fighters of the simulation from what perception knows. */
    private void fill(Perception.Snapshot me, Perception.Snapshot seen, Perception perception)
    {
        fill(sim.a, me);
        fill(sim.b, seen);
        // How much the last hit was worth is not readable from outside, so it is recovered from the health the
        // target was seen to lose; that is after the target's defences, which the comparison in the simulation
        // is made before, so it is scaled back up.
        float seenDamage = perception.lastTargetDamage();
        if (seenDamage > 0.0F)
        {
            float perPoint = CombatMath.damageAfterDefences(1.0F, seen.armor, seen.armorToughness, 0, seen.epf);
            sim.b.lastHurt = perPoint > 0.0F ? seenDamage / perPoint : seenDamage;
        }
        else
        {
            sim.b.lastHurt = 0.0F;
        }
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