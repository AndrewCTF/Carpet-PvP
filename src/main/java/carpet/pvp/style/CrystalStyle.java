package carpet.pvp.style;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotBudget;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotStats;
import carpet.pvp.CombatUtils;
import carpet.pvp.Perception;
import carpet.pvp.crystal.CrystalHand;
import carpet.pvp.crystal.CrystalTactics;
import carpet.pvp.crystal.CrystalTactics.Blast;
import carpet.pvp.crystal.CrystalTactics.Stage;
import carpet.pvp.crystal.CrystalTechniques;
import carpet.pvp.crystal.LevelExplosionView;
import carpet.pvp.sim.Box;
import carpet.pvp.sim.CombatMath;
import carpet.pvp.sim.CrystalPlacement;
import carpet.pvp.sim.CrystalSearch;
import carpet.pvp.sim.DuelSim;
import carpet.pvp.sim.SeenPercent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Random;

/**
 * The crystal style: put a blast where the model says it hurts, and get out of its own.
 *
 * <p>Each tick the fight is read out of perception, {@link CrystalSearch} is asked where an end crystal or
 * a respawn anchor would do the most damage to the target and the least to this bot, and
 * {@link CrystalHand} carries the answer out as the clicks a player would make: a crystal, an obsidian
 * block or a glowstone click on a face of a block, then a swing at it on a later tick. The search draws on
 * the shared {@link BotBudget}, so a server with more fighters than budget leaves the bot with the
 * placement it had and spends the tick it has on the clicks. With nothing to place, or a target out of the
 * reach of any blast, the sword plays, which is the {@link SwordStyle} this style keeps for that.</p>
 *
 * <p>Two rules are never traded away. A blast the model says would kill this bot, or pop its totem, is not
 * set off unless it finishes the target as well. And every click waits for the view to be on the block
 * first, at the bot's own look speed. What the bot may try at all comes from {@link CrystalTechniques}:
 * the difficulty preset teaches the bridge, the anchor, the pearls and the double tap, and each of those
 * has an option of its own.</p>
 */
public final class CrystalStyle implements BotStyle
{
    /**
     * What one scored placement of the search costs against the shared budget, in the simulated ticks the
     * sword planner spends. A placement casts two sets of exposure rays through the real level, which is a
     * good deal more work than a rollout step, so it is worth several of them.
     */
    private static final int COST_PER_PLACEMENT = 8;
    /** A search only starts when the bot's share of the budget could pay for this many placements. */
    private static final int MIN_PLACEMENTS = 4;
    /** Below this much damage to the target a placement is not worth the click; the sword does better. */
    private static final float WORTH_A_BLAST = 4.0F;
    /** How far beyond its placement reach a crystal bot still bothers, in blocks. */
    private static final double BLAST_SLACK = 1.5;
    /** How far off a fighter it counts as being in reach of a swing. */
    private static final double MELEE_REACH = 3.2;
    /** Ticks a bot keeps stepping out of a blast before it gives the placement up. */
    private static final int BACK_OFF_TICKS = 6;
    /** Ticks a bot keeps walking in towards a blast before it looks for another placement. */
    private static final int CLOSE_IN_TICKS = 12;

    private final EntityPlayerMPFake bot;
    private final BotBody body;
    private final BotStats stats;
    private final LevelExplosionView view;
    private final CrystalHand hand;
    private final CrystalSearch search = new CrystalSearch();
    private final CrystalSearch.Side self = new CrystalSearch.Side();
    private final CrystalSearch.Side enemy = new CrystalSearch.Side();
    private final SwordStyle sword;

    private CrystalTechniques techniques;
    private CrystalTactics tactics;
    private double reach;

    /** Where the bot believes its target is, which is what it must not put a block inside. */
    private Box targetBox;
    private CrystalSearch.Candidate plan;
    private BlockPos cell;
    /** Ticks the bot has spent getting out of, or into, the blast of the placement it is working on. */
    private int backOffTicks;
    private int closeInTicks;
    /** Totems the bot believes its target is carrying, from what it has watched it lose. */
    private int targetTotems = 1;
    private float lastHealth = 20.0F;
    /** Whether the bot was holding a totem last tick, and whether that has ever been looked at. */
    private boolean totemHeld = true;
    private boolean totemKnown;
    /** What the last melee hit on the target was worth, the first hit of a double tap. */
    private float lastMelee;
    private int lastAction = DuelSim.NOOP;
    private int serverDifficulty = CombatMath.NORMAL;

    public CrystalStyle(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        this.bot = bot;
        this.body = body;
        this.stats = body.stats();
        this.view = new LevelExplosionView(bot.level());
        this.hand = new CrystalHand(body);
        this.sword = new SwordStyle(bot, body, cfg, random);
        this.serverDifficulty = bot.level().getServer().getWorldData().getDifficulty().getId();
        reconfigure(cfg);
    }

    @Override
    public void reconfigure(BotPvpConfig cfg)
    {
        CrystalTechniques wanted = new CrystalTechniques(cfg);
        if (techniques == null || !techniques.sameAs(wanted))
        {
            techniques = wanted;
            tactics = new CrystalTactics(techniques.planInterval);
            drop();
        }
        reach = Math.max(1.5, Math.min(4.5, cfg.number("crystal.reach")));
        serverDifficulty = bot.level().getServer().getWorldData().getDifficulty().getId();
        sword.reconfigure(cfg);
    }

    @Override
    public void engage(BotBody body, Perception perception, BotPvpConfig cfg, LivingEntity target,
            EntityPlayerActionPack pack)
    {
        Perception.Snapshot seen = perception.target(cfg.reactionDelay + cfg.pingTicks);
        Perception.Snapshot me = perception.self();
        if (seen == null || !seen.seen || me == null || !me.seen)
        {
            drop();
            this.body.hold(null, target);
            return;
        }
        tactics.tick();
        targetBox = Box.player(seen.x, seen.y, seen.z);
        watchTotems(seen);
        watchOwnTotem();
        keepTotems();

        // Nothing to place, or a target out of the reach of any blast: the sword plays this one.
        if (!techniques.crystals || !armed() || horizontal(me, seen) > reach + BLAST_SLACK)
        {
            sword.engage(this.body, perception, cfg, target, pack);
            return;
        }
        // Pearling out of a hole takes the whole tick, as it does a player.
        if (techniques.pearls && boxedIn())
        {
            if (!pearl(seen))
            {
                body.hold(seen, target);
            }
            return;
        }
        // A placement that is already under way is followed through to its blast: looking for a better one
        // every tick would only ever move the goalposts.
        if (tactics.stage() == Stage.NONE && tactics.planDue())
        {
            lookForPlacement(me, seen);
            tactics.planned();
        }
        if (tactics.stage() == Stage.NONE)
        {
            if (plan == null || plan.targetDamage < WORTH_A_BLAST)
            {
                poke(me, seen, target);
            }
            else
            {
                tactics.begin(CrystalTactics.opening(plan.bridge), 0);
                backOffTicks = 0;
                closeInTicks = 0;
            }
            return;
        }
        if (cell == null || !hand.near(cell))
        {
            // It walked out of its own placement, so start over rather than click at nothing.
            drop();
            poke(me, seen, target);
            return;
        }
        tactics.onTick();
        if (tactics.stale())
        {
            // It has been a long time coming along on this one spot, so try another rather than stand there.
            drop();
            poke(me, seen, target);
            return;
        }
        switch (tactics.stage())
        {
            case BRIDGE -> bridge(target);
            case PLACE -> place(target);
            case CHARGE -> charge(target);
            case READY -> setOff(seen, target);
            default -> drop();
        }
    }

    @Override
    public void disengage(BotBody body)
    {
        drop();
        tactics.reset();
        sword.disengage(body);
    }

    // ===== planning =====

    /**
     * Asks the model where a blast would do the most damage, and books the search against the shared
     * budget. Without a share to spend, the bot keeps whatever placement it had and spends the tick on the
     * clicks instead.
     */
    private void lookForPlacement(Perception.Snapshot me, Perception.Snapshot seen)
    {
        BotBudget budget = BotBudget.instance();
        int share = budget.join();
        if (share < COST_PER_PLACEMENT * MIN_PLACEMENTS)
        {
            stats.starvedTicks++;
            return;
        }
        fill(me, seen);
        boolean obsidian = techniques.bridges && hand.carried(Items.OBSIDIAN) > 0;
        boolean anchors = techniques.anchors && hand.carried(Items.RESPAWN_ANCHOR) > 0
                && hand.carried(Items.GLOWSTONE) >= CrystalTactics.ANCHOR_CHARGES;
        plan = placeable(obsidian, anchors);
        if (plan == null && anchors)
        {
            // Every spot the model liked was an anchor in a cell with no solid side to click, which is not a
            // placement the bot can make, so ask again for the ones it can.
            plan = placeable(obsidian, false);
        }
        cell = plan == null ? null : new BlockPos(plan.x, plan.y, plan.z);
    }

    /** Runs one search, books it against the shared budget and reports what is worth putting down. */
    private CrystalSearch.Candidate placeable(boolean obsidian, boolean anchors)
    {
        CrystalSearch.Result result = search.search(view, self, enemy, reach, obsidian, anchors,
                techniques.halfX, techniques.halfY, techniques.halfZ, serverDifficulty);
        int cost = COST_PER_PLACEMENT * Math.max(1, result.evaluated());
        BotBudget.instance().spend(cost);
        stats.plannerCalls++;
        stats.simulatedTicks += cost;
        return choosePlan(result);
    }

    /**
     * The best of the candidates the bot could actually put down with a click. The model knows which cells a
     * crystal will stand on and an anchor will fit in, but not whether a cell has a solid side to click, which
     * is what placing a block needs; a candidate without one is no use however well it would score, so the next
     * best one is taken and the sword plays when none of them is any use.
     */
    private CrystalSearch.Candidate choosePlan(CrystalSearch.Result result)
    {
        for (CrystalSearch.Candidate candidate : result.candidates())
        {
            if (!candidate.anchor && !candidate.bridge)
            {
                return candidate;
            }
            boolean carry = candidate.anchor ? hand.slot(Items.RESPAWN_ANCHOR) >= 0
                    : hand.slot(Items.OBSIDIAN) >= 0;
            if (carry && solidNeighbour(new BlockPos(candidate.x, candidate.y, candidate.z)) != null)
            {
                return candidate;
            }
        }
        return null;
    }

    /** Writes both fighters of the model: the bot from what it knows of itself, the target from the snapshot. */
    private void fill(Perception.Snapshot me, Perception.Snapshot seen)
    {
        self.x = bot.getX();
        self.y = bot.getY();
        self.z = bot.getZ();
        self.vx = bot.getDeltaMovement().x;
        self.vy = bot.getDeltaMovement().y;
        self.vz = bot.getDeltaMovement().z;
        self.armor = me.armor;
        self.toughness = me.armorToughness;
        // The bot may read its own armour; what it wears against a blast is worth reading from the game
        // rather than guessing from a list of enchantments.
        self.epf = hand.ownExplosionProtection();
        self.health = me.healthTotal();
        self.totem = hand.offhand(Items.TOTEM_OF_UNDYING);
        enemy.x = seen.x;
        enemy.y = seen.y;
        enemy.z = seen.z;
        enemy.vx = seen.vx;
        enemy.vy = seen.vy;
        enemy.vz = seen.vz;
        enemy.armor = seen.armor;
        enemy.toughness = seen.armorToughness;
        // Enchantment protection is not something a player can see on another fighter, so the model is
        // given the target as wearing none of it, which is what the perception snapshot holds.
        enemy.epf = seen.epf;
        enemy.health = seen.healthTotal();
        enemy.totem = targetTotems > 0;
    }

    // ===== carrying out a placement =====

    /**
     * Fills an empty base cell with obsidian. The click goes on the face of whichever neighbour is solid,
     * the way a player bridges a hole: the block lands in the empty cell the plan asked for.
     */
    private void bridge(LivingEntity target)
    {
        if (!hand.hold(hand.slot(Items.OBSIDIAN)))
        {
            stand(target);
            return;
        }
        Direction neighbour = solidNeighbour(cell);
        if (neighbour == null || !roomFor(cell))
        {
            drop();
            return;
        }
        BlockPos against = cell.relative(neighbour);
        hand.aimFace(against, neighbour.getOpposite());
        if (hand.onCell(against) && body.click(true))
        {
            hand.clickFace(against, neighbour.getOpposite());
            stand(target);
            if (hand.isBlock(cell, Blocks.OBSIDIAN))
            {
                stats.blocksPlaced++;
                advanced();
            }
            return;
        }
        stand(target);
    }

    /** Moves the placement on to whatever the click that just went through made possible. */
    private void advanced()
    {
        tactics.begin(CrystalTactics.after(tactics.stage(), plan.anchor, tactics.charges()), tactics.charges());
    }

    /**
     * Puts the blast down: an end crystal onto the top face of its base, a respawn anchor onto the face of
     * whichever neighbour of its cell is solid.
     */
    private void place(LivingEntity target)
    {
        if (plan.anchor)
        {
            if (hand.isBlock(cell, Blocks.RESPAWN_ANCHOR))
            {
                tactics.begin(Stage.CHARGE, hand.anchorCharge(cell));
                return;
            }
            if (!hand.hold(hand.slot(Items.RESPAWN_ANCHOR)))
            {
                stand(target);
                return;
            }
            Direction neighbour = solidNeighbour(cell);
            if (neighbour == null || !roomFor(cell))
            {
                drop();
                return;
            }
            BlockPos against = cell.relative(neighbour);
            hand.aimFace(against, neighbour.getOpposite());
            if (hand.onCell(against) && body.click(true))
            {
                hand.clickFace(against, neighbour.getOpposite());
                stand(target);
                if (hand.isBlock(cell, Blocks.RESPAWN_ANCHOR))
                {
                    stats.anchorsPlaced++;
                    advanced();
                }
                return;
            }
            stand(target);
            return;
        }
        if (hand.crystalAt(cell.getX(), cell.getY(), cell.getZ()) != null)
        {
            tactics.begin(Stage.READY, 0);
            return;
        }
        if (!hand.hold(hand.slot(Items.END_CRYSTAL)))
        {
            stand(target);
            return;
        }
        hand.aimFace(cell, Direction.UP);
        if (hand.onCell(cell) && body.click(true))
        {
            hand.clickFace(cell, Direction.UP);
            stand(target);
            if (hand.crystalAt(cell.getX(), cell.getY(), cell.getZ()) != null)
            {
                stats.crystalsPlaced++;
                advanced();
            }
            return;
        }
        stand(target);
    }

    /** Charges a respawn anchor with glowstone, one click at a time, until it holds four. */
    private void charge(LivingEntity target)
    {
        int charge = hand.anchorCharge(cell);
        if (charge >= CrystalTactics.ANCHOR_CHARGES)
        {
            tactics.begin(Stage.READY, charge);
            return;
        }
        tactics.begin(Stage.CHARGE, charge);
        if (!hand.hold(hand.slot(Items.GLOWSTONE)))
        {
            stand(target);
            return;
        }
        hand.aim(cell);
        if (hand.onCell(cell) && body.click(true))
        {
            hand.clickFace(cell, Direction.UP);
            stand(target);
            if (hand.anchorCharge(cell) > charge)
            {
                advanced();
            }
            return;
        }
        stand(target);
    }

    /**
     * Sets the blast off, if the model still says the bot can live through it. A crystal is hit with the
     * sword, a charged anchor is clicked once more, which is what detonates it. Either way the view has to
     * be on it first and the click is a tick of its own.
     */
    private void setOff(Perception.Snapshot seen, LivingEntity target)
    {
        Blast verdict = judge(seen);
        if (verdict == Blast.GO || verdict == Blast.TRADE)
        {
            // The bot can place a crystal further out than it can swing at it, so it walks in first, and
            // the blast is judged again from where it ends up on every one of those ticks.
            if (!inHitReach())
            {
                closeIn(target);
                return;
            }
            if (hitNow(target))
            {
                // An anchor only goes off if the click reaches it: a charged anchor that is still standing
                // after the click has not been set off, so nothing is booked and the bot tries again rather
                // than count a blast that never happened.
                if (plan.anchor && hand.isBlock(cell, Blocks.RESPAWN_ANCHOR))
                {
                    stand(target);
                    return;
                }
                stats.blasts++;
                if (plan.anchor)
                {
                    stats.anchorsBlown++;
                }
                drop();
            }
            return;
        }
        stats.refusedBlasts++;
        switch (verdict)
        {
            case BLOCK_OFF -> blockOff(target);
            case BACK_OFF -> backOff(target);
            default -> drop();
        }
    }

    /** Whether the bot is close enough to swing at the blast it has set down. */
    private boolean inHitReach()
    {
        if (plan.anchor)
        {
            return bot.isWithinBlockInteractionRange(cell, 1.0);
        }
        EndCrystal crystal = hand.crystalAt(cell.getX(), cell.getY(), cell.getZ());
//? if <26.1 {
/*        return crystal != null && bot.isWithinAttackRange(crystal.getBoundingBox(), 0.0);
*///?} else {
        return crystal != null && bot.isWithinAttackRange(bot.getMainHandItem(), crystal.getBoundingBox(), 0.0);
//?}
    }

    /** Walks towards the blast the view is on, which is a step forward, and judges it again next tick. */
    private void closeIn(LivingEntity target)
    {
        if (plan.anchor)
        {
            hand.aim(cell);
        }
        else
        {
            EndCrystal crystal = hand.crystalAt(cell.getX(), cell.getY(), cell.getZ());
            if (crystal == null)
            {
                drop();
                return;
            }
            hand.aim(crystal.getX(), crystal.getY() + 0.5, crystal.getZ());
        }
        if (++closeInTicks > CLOSE_IN_TICKS)
        {
            // Whatever is in the way, walking at it for longer is not going to bring it into reach.
            drop();
            return;
        }
        lastAction = DuelSim.action(1, 0, false, false, false);
        body.tickAimed(target, lastAction, false);
    }

    /**
     * The final aim and click. A crystal is only hit once the view is on it and the click limiter allows
     * one. A blast worth less than the sword hit that went in first waits for the damage window to close,
     * which is the double tap the model gives.
     */
    private boolean hitNow(LivingEntity target)
    {
        if (CrystalTactics.doubleTapDelay(plan.targetDamage, lastMelee, tactics.ticksSinceMeleeHit()) > 0)
        {
            stand(target);
            return false;
        }
        if (plan.anchor)
        {
            hand.aim(cell);
            if (!hand.onCell(cell) || !body.click(true))
            {
                stand(target);
                return false;
            }
            hand.clickFace(cell, Direction.UP);
            return true;
        }
        EndCrystal crystal = hand.crystalAt(cell.getX(), cell.getY(), cell.getZ());
        if (crystal == null)
        {
            drop();
            return false;
        }
        hand.aim(crystal.getX(), crystal.getY() + 0.5, crystal.getZ());
        if (!hand.on(hand.crystalBox(crystal)) || !body.click(true))
        {
            stand(target);
            return false;
        }
        return hand.strike(crystal);
    }

    // ===== staying out of the blast =====

    /**
     * Whether the blast may go off, worked out on where the two fighters are now: the bot's own box, which
     * it always knows, and the target's perceived box, seen from the blast's centre through the blocks
     * that are really there.
     */
    private Blast judge(Perception.Snapshot seen)
    {
        // Standing still while aiming is most of what a bot does here, and the blast cannot have moved, so the
        // answer is remembered rather than worked out again from the level every tick.
        if (judged && judgedCell == cell && judgedSelf == bot.getX() && judgedSelfY == bot.getY()
                && judgedSelfZ == bot.getZ() && judgedThem == seen.x && judgedThemY == seen.y
                && judgedThemZ == seen.z)
        {
            return verdict;
        }
        Blast fresh = measure(seen);
        judged = true;
        judgedCell = cell;
        judgedSelf = bot.getX();
        judgedSelfY = bot.getY();
        judgedSelfZ = bot.getZ();
        judgedThem = seen.x;
        judgedThemY = seen.y;
        judgedThemZ = seen.z;
        verdict = fresh;
        return fresh;
    }

    private Blast measure(Perception.Snapshot seen)
    {
        double[] centre = blastCentre();
        Box own = Box.player(bot.getX(), bot.getY(), bot.getZ());
        Box theirs = Box.player(seen.x, seen.y, seen.z);
        float selfExposure = SeenPercent.of(view, centre[0], centre[1], centre[2], own);
        float theirExposure = SeenPercent.of(view, centre[0], centre[1], centre[2], theirs);
        boolean blockable = hand.carried(Items.OBSIDIAN) > 0 && gapCell() != null;
        boolean steppable = stepCell() != null;
        float selfDamage = damage(self, own, centre, power(), selfExposure);
        float theirDamage = damage(enemy, theirs, centre, power(), theirExposure);
        return CrystalTactics.judge(selfDamage, self.health, theirDamage, enemy.health, enemy.totem,
                blockable, steppable);
    }

    /** Health a fighter loses to the blast, as the crystal model works it out. */
    private float damage(CrystalSearch.Side side, Box box, double[] centre, float power, float exposure)
    {
        double dx = (box.minX + box.maxX) / 2.0 - centre[0];
        double dy = box.minY - centre[1];
        double dz = (box.minZ + box.maxZ) / 2.0 - centre[2];
        float raw = CombatMath.explosionDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), power, exposure);
        float scaled = CombatMath.playerDifficultyScale(raw, serverDifficulty);
        return Math.min(side.health, CombatMath.damageAfterDefences(scaled, side.armor, side.toughness, 0,
                side.epf));
    }

    /** The last verdict, and the positions it was worked out for. */
    private boolean judged;
    private BlockPos judgedCell;
    private double judgedSelf;
    private double judgedSelfY;
    private double judgedSelfZ;
    private double judgedThem;
    private double judgedThemY;
    private double judgedThemZ;
    private Blast verdict = Blast.GO;

    private float power()
    {
        return plan.anchor ? CrystalPlacement.ANCHOR_POWER : CrystalPlacement.CRYSTAL_POWER;
    }

    private double[] blastCentre()
    {
        return plan.anchor ? CrystalPlacement.anchorCentre(cell.getX(), cell.getY(), cell.getZ())
                : CrystalPlacement.crystalCentre(cell.getX(), cell.getY(), cell.getZ());
    }

    /**
     * The empty cell an obsidian block would stand in between the bot and the blast, or null when there is
     * none: the block the line from the bot to the blast goes through, halfway.
     */
    private BlockPos gapCell()
    {
        double[] centre = blastCentre();
        BlockPos gap = BlockPos.containing((bot.getX() + centre[0]) / 2.0,
                (bot.getY() + 1.62 + centre[1]) / 2.0, (bot.getZ() + centre[2]) / 2.0);
        if (gap.equals(cell) || gap.equals(cell.above()) || !hand.isFree(gap)
                // A block inside either fighter would be a suffocation, which is not what blocking a blast off is.
                || Box.player(bot.getX(), bot.getY(), bot.getZ()).intersects(Box.block(gap.getX(), gap.getY(), gap.getZ())))
        {
            return null;
        }
        return gap;
    }

    /** Puts an obsidian block between the bot and the blast it is standing next to. */
    private void blockOff(LivingEntity target)
    {
        BlockPos gap = gapCell();
        if (gap == null)
        {
            backOff(target);
            return;
        }
        if (!hand.hold(hand.slot(Items.OBSIDIAN)))
        {
            stand(target);
            return;
        }
        Direction neighbour = solidNeighbour(gap);
        if (neighbour == null)
        {
            drop();
            return;
        }
        BlockPos against = gap.relative(neighbour);
        hand.aimFace(against, neighbour.getOpposite());
        if (hand.onCell(against) && body.click(true))
        {
            hand.clickFace(against, neighbour.getOpposite());
            stand(target);
            if (hand.isBlock(gap, Blocks.OBSIDIAN))
            {
                // The block is up and the bot is still where it was; judge the blast again next tick.
                stats.blocksPlaced++;
                tactics.begin(Stage.READY, 0);
            }
            return;
        }
        stand(target);
    }

    /** A cell the bot could step into, or null when it is walled in on every side. */
    private BlockPos stepCell()
    {
        BlockPos feet = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            BlockPos candidate = feet.relative(direction);
            if (hand.isFree(candidate) && hand.isFree(candidate.above()))
            {
                return candidate;
            }
        }
        return null;
    }

    /** Walks out of the blast: one step towards the cell it can see least of, in the bot's own frame. */
    private void backOff(LivingEntity target)
    {
        BlockPos step = stepCell();
        if (step == null)
        {
            drop();
            return;
        }
        double dx = bot.getX() - (step.getX() + 0.5);
        double dz = bot.getZ() - (step.getZ() + 0.5);
        double yaw = Math.toRadians(bot.getYRot());
        int forward = along(dx, dz, -Math.sin(yaw), Math.cos(yaw));
        int strafe = along(dx, dz, -Math.cos(yaw), -Math.sin(yaw));
        stats.backedOff++;
        if (++backOffTicks > BACK_OFF_TICKS)
        {
            // Walking out of a blast the bot cannot get out of is not going to get better, so it looks for
            // another placement instead of drifting off the edge of the world.
            drop();
            return;
        }
        lastAction = DuelSim.action(forward, strafe, false, false, false);
        body.tickAimed(target, lastAction, false);
    }

    private static int along(double dx, double dz, double axisX, double axisZ)
    {
        double value = dx * axisX + dz * axisZ;
        return value > 0.4 ? 1 : value < -0.4 ? -1 : 0;
    }

    // ===== melee =====

    /**
     * One charged sword hit when the target is in reach: the knockback half of the double tap, and the
     * fallback while a blast is on its way down. Nothing is simulated, so it costs the shared budget
     * nothing and still costs the click rate a swing costs.
     */
    private void poke(Perception.Snapshot me, Perception.Snapshot seen, LivingEntity target)
    {
        hand.hold(hand.slot(Items.NETHERITE_SWORD));
        float before = seen.healthTotal();
        boolean charged = me.ticksSinceSwing >= CombatMath.minTicksForGate(me.attackSpeed);
        int attack = charged && body.canHit(target) ? 1 : 0;
        // Out of reach it walks at the target, which is what a player does while it has no crystal down.
        int forward = attack == 1 || horizontal(me, seen) > MELEE_REACH ? 1 : 0;
        lastAction = DuelSim.action(forward, 0, false, attack == 1, attack == 1);
        body.tick(seen, target, lastAction, false);
        float after = target.getHealth() + target.getAbsorptionAmount();
        if (after < before)
        {
            tactics.onMeleeHit();
            lastMelee = before - after;
        }
    }

    // ===== totems =====

    /**
     * How many totems the bot believes its target carries. It starts at one, because a fighter it cannot
     * read is assumed to have one, and only a pop the bot actually watched takes the count down. The bot
     * never looks in the target's inventory.
     */
    private void watchTotems(Perception.Snapshot seen)
    {
        float health = seen.healthTotal();
        if (health > lastHealth + 0.5F && lastHealth <= 1.0F)
        {
            targetTotems = Math.max(0, targetTotems - 1);
        }
        lastHealth = health;
    }

    /**
     * Nothing takes a totem out of the offhand except the game taking it to save the bot's life, so that
     * is where the wait before another one may be moved in starts. A player cannot see its own hand either;
     * it knows the totem is gone because it is still alive.
     */
    private void watchOwnTotem()
    {
        boolean holding = hand.offhand(Items.TOTEM_OF_UNDYING);
        if (totemKnown && totemHeld && !holding)
        {
            tactics.onPop();
        }
        totemKnown = true;
        totemHeld = holding;
    }

    /** Keeps a totem in the offhand, and a second one in the main hand when a blast would finish it. */
    private void keepTotems()
    {
        if (!techniques.reTotem)
        {
            return;
        }
        if (!hand.offhand(Items.TOTEM_OF_UNDYING))
        {
            if (tactics.ticksToReTotem(techniques.reTotemDelay) == 0
                    && CombatUtils.ensureTotemInOffhand(bot))
            {
                stats.reTotems++;
            }
            return;
        }
        // The blast it is standing in would finish it, and a second one is close behind: with a totem in each
        // hand the first pop does not leave the bot empty for the second.
        if (techniques.handTotem && plan != null && plan.selfLethal && hand.twoTotems())
        {
            hand.hold(hand.slot(Items.TOTEM_OF_UNDYING));
        }
    }

    // ===== the loadout =====

    /** True when the bot has something it could put down. */
    private boolean armed()
    {
        return hand.carried(Items.END_CRYSTAL) > 0 || hand.carried(Items.RESPAWN_ANCHOR) > 0;
    }

    /** Whether the bot has nowhere to step to, which is the hole a pearl gets it out of. */
    private boolean boxedIn()
    {
        BlockPos feet = bot.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            BlockPos candidate = feet.relative(direction);
            if (hand.isFree(candidate) && hand.isFree(candidate.above()))
            {
                return false;
            }
        }
        return true;
    }

    /** Throws an ender pearl over the wall at the target, which is how a player gets out of a hole. */
    private boolean pearl(Perception.Snapshot seen)
    {
        if (hand.carried(Items.ENDER_PEARL) <= 0 || !hand.hold(hand.slot(Items.ENDER_PEARL)))
        {
            return false;
        }
        if (bot.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL)) || !body.click(true))
        {
            return false;
        }
        hand.aim(seen.x, seen.y + seen.height + 0.5, seen.z);
        if (hand.useHeld())
        {
            stats.pearlsThrown++;
            return true;
        }
        return false;
    }

    /**
     * A tick the bot spends standing where it is with the view on the cell it is working in, which is what a
     * player does while placing: it stops, or the block it is aiming at walks out from under its crosshair.
     */
    private void stand(LivingEntity target)
    {
        lastAction = DuelSim.NOOP;
        body.tickAimed(target, lastAction, false);
    }

    /**
     * The direction from a cell to a solid neighbour of it, which is the side a block has to be put in from.
     * The block goes in by clicking the face of that neighbour which looks back at the cell, which is the
     * click a player makes to fill a hole.
     */
    private Direction solidNeighbour(BlockPos cell)
    {
        for (Direction direction : Direction.values())
        {
            if (!hand.isFree(cell.relative(direction)) && hand.isFree(cell))
            {
                return direction;
            }
        }
        return null;
    }

    /**
     * Whether a cell is still one a block may be put in. The search checked it against where the two fighters
     * were when it ran, and either of them may have walked in since: a block inside a fighter suffocates it,
     * which is a slow death no player would sit through.
     */
    private boolean roomFor(BlockPos cell)
    {
        Box block = Box.block(cell.getX(), cell.getY(), cell.getZ());
        return !Box.player(bot.getX(), bot.getY(), bot.getZ()).intersects(block)
                && (targetBox == null || !targetBox.intersects(block));
    }

    /** Forgets the placement the bot was working on, so the next search starts from scratch. */
    private void drop()
    {
        plan = null;
        cell = null;
        judged = false;
        tactics.reset();
    }

    private static double horizontal(Perception.Snapshot a, Perception.Snapshot b)
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
