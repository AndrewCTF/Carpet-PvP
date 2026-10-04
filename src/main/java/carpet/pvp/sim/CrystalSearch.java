package carpet.pvp.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Picks where a bot should put an end crystal or a respawn anchor next. Every block of the searched volume
 * around the bot is asked for the damage it would do to both fighters, and the best few are returned with
 * the numbers behind them so a caller can say why it chose one.
 *
 * <p>The search is bounded. A placement is scored as the damage it would do to the target, less the damage it
 * would do the bot, less what the blocks it needs cost, plus a bonus for a lethal hit and for hitting where
 * the target is heading. A cheap upper bound of that score is computed before any ray is cast, the blocks
 * are visited in order of that bound, and a block whose bound cannot reach the current fourth best is
 * dropped without a single sample ray.
 */
public final class CrystalSearch
{
    /** How many candidates a search hands back. */
    public static final int RESULTS = 4;
    /** Placing the obsidian a crystal needs costs the bot a block and the charge time to bridge with it. */
    public static final float OBSIDIAN_COST = 6.0f;
    /** A respawn anchor costs an anchor and four glowstone. */
    public static final float ANCHOR_COST = 3.0f;
    public static final float KILL_BONUS = 20.0f;
    public static final float SELF_WEIGHT = 0.5f;
    /** Large enough to lose against any surviving placement, so a bot never picks a lethal one for free. */
    public static final float DEATH_PENALTY = 1000.0f;
    public static final float TOTEM_PENALTY = 120.0f;
    /** Weight of doing more damage to where the target is heading than to where it stands. */
    public static final float LEAD_WEIGHT = 0.5f;
    /** Ticks ahead the target is predicted over. */
    public static final int LEAD_TICKS = 2;

    private static final double EYE_HEIGHT = 1.62;
    private static final int CRYSTAL = 1;
    private static final int BRIDGE = 2;
    private static final int ANCHOR = 4;
    private static final int KEY_SCALE = 16;

    private int[] slotX = new int[0];
    private int[] slotY = new int[0];
    private int[] slotZ = new int[0];
    private int[] slotFlags = new int[0];
    private long[] order = new long[0];
    private ExplosionView view;
    private Box targetBox;
    private Box leadBox;
    private Box selfBox;
    private boolean moving;
    private int difficulty;

    /** One fighter: where it stands, where it is going and what it is wearing. */
    public static final class Side
    {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public float armor;
        public float toughness;
        /** Enchantment protection factor of the armour, CombatMath.epf against an explosion. */
        public float epf;
        public float health = 20.0f;
        public boolean totem;

        public Box box()
        {
            return Box.player(x, y, z);
        }

        /** The same box two ticks of the current velocity ahead, which is where the target is heading. */
        public Box lead()
        {
            return box().moved(vx * LEAD_TICKS, vy * LEAD_TICKS, vz * LEAD_TICKS);
        }
    }

    /** One placement and everything the search worked out about it. */
    public static final class Candidate
    {
        /** The block the crystal or anchor goes on, or the cell the bridging obsidian goes into. */
        public final int x;
        public final int y;
        public final int z;
        /** True for a respawn anchor, false for an end crystal. */
        public final boolean anchor;
        /** True when the base has to be filled with obsidian first. */
        public final boolean bridge;
        public final double cx;
        public final double cy;
        public final double cz;
        public final float power;
        public final float selfExposure;
        public final float targetExposure;
        public final float leadExposure;
        public final float selfDamage;
        public final float targetDamage;
        public final float leadDamage;
        /** True when the blast would kill the bot, or pop its totem when it is carrying one. */
        public final boolean selfLethal;
        public final float score;

        private Candidate(int x, int y, int z, boolean anchor, boolean bridge, double cx, double cy, double cz,
                          float power, float selfExposure, float targetExposure, float leadExposure,
                          float selfDamage, float targetDamage, float leadDamage, boolean selfLethal, float score)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.anchor = anchor;
            this.bridge = bridge;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.power = power;
            this.selfExposure = selfExposure;
            this.targetExposure = targetExposure;
            this.leadExposure = leadExposure;
            this.selfDamage = selfDamage;
            this.targetDamage = targetDamage;
            this.leadDamage = leadDamage;
            this.selfLethal = selfLethal;
            this.score = score;
        }
    }

    /** The best candidates, best first. */
    public static final class Result
    {
        private final List<Candidate> candidates;
        private final int evaluated;

        private Result(List<Candidate> candidates, int evaluated)
        {
            this.candidates = candidates;
            this.evaluated = evaluated;
        }

        public List<Candidate> candidates()
        {
            return candidates;
        }

        public Candidate best()
        {
            return candidates.isEmpty() ? null : candidates.get(0);
        }

        /** Placements that were scored all the way; the rest were dropped by the upper bound. */
        public int evaluated()
        {
            return evaluated;
        }
    }

    /**
     * Searches the block of (2 * halfX + 1) by (2 * halfY + 1) by (2 * halfZ + 1) volume around the bot,
     * keeping the placements the bot's eye can reach within {@code reach}. Obsidian bridges are only offered
     * when the bot has obsidian to spend and anchors only when it has one to charge.
     */
    public Result search(ExplosionView view, Side self, Side target, double reach, boolean obsidian,
                         boolean anchors, int halfX, int halfY, int halfZ, int difficulty)
    {
        this.view = view;
        this.difficulty = difficulty;
        selfBox = self.box();
        targetBox = target.box();
        moving = target.vx != 0.0 || target.vy != 0.0 || target.vz != 0.0;
        leadBox = target.lead();
        int baseX = (int) Math.floor(self.x);
        int baseY = (int) Math.floor(self.y);
        int baseZ = (int) Math.floor(self.z);
        int slots = (2 * halfX + 1) * (2 * halfY + 1) * (2 * halfZ + 1);
        if (order.length < slots)
        {
            order = new long[slots];
            slotX = new int[slots];
            slotY = new int[slots];
            slotZ = new int[slots];
            slotFlags = new int[slots];
        }
        int found = 0;
        for (int x = baseX - halfX; x <= baseX + halfX; x++)
        {
            for (int z = baseZ - halfZ; z <= baseZ + halfZ; z++)
            {
                for (int y = baseY - halfY; y <= baseY + halfY; y++)
                {
                    int flags = 0;
                    if (CrystalPlacement.canPlaceCrystal(view, x, y, z, selfBox, targetBox))
                    {
                        flags |= CRYSTAL;
                    }
                    if (obsidian && CrystalPlacement.canBridgeCrystal(view, x, y, z, selfBox, targetBox))
                    {
                        flags |= BRIDGE;
                    }
                    if (anchors && CrystalPlacement.canPlaceAnchor(view, x, y, z, selfBox, targetBox))
                    {
                        flags |= ANCHOR;
                    }
                    if (flags == 0)
                    {
                        continue;
                    }
                    slotX[found] = x;
                    slotY[found] = y;
                    slotZ[found] = z;
                    slotFlags[found] = flags;
                    order[found] = (long) (-Math.round(bestBound(self, target, x, y, z, flags) * KEY_SCALE)
                            << 32 | found);
                    found++;
                }
            }
        }
        Arrays.sort(order, 0, found);
        List<Candidate> best = new ArrayList<>(RESULTS);
        int evaluated = 0;
        for (int i = 0; i < found; i++)
        {
            int slot = (int) order[i];
            int x = slotX[slot];
            int y = slotY[slot];
            int z = slotZ[slot];
            int flags = slotFlags[slot];
            if ((flags & CRYSTAL) != 0)
            {
                evaluated += consider(best, self, target, reach, x, y, z, false, false);
            }
            if ((flags & BRIDGE) != 0)
            {
                evaluated += consider(best, self, target, reach, x, y, z, false, true);
            }
            if ((flags & ANCHOR) != 0)
            {
                evaluated += consider(best, self, target, reach, x, y, z, true, false);
            }
        }
        return new Result(best, evaluated);
    }

    /** The largest score any of the placements at (x, y, z) could reach, with the exposures taken at 1. */
    private double bestBound(Side self, Side target, int x, int y, int z, int flags)
    {
        double best = -Double.MAX_VALUE;
        if ((flags & CRYSTAL) != 0)
        {
            best = bound(self, target, x, y, z, false, false, 1.0f);
        }
        if ((flags & BRIDGE) != 0)
        {
            best = Math.max(best, bound(self, target, x, y, z, false, true, 1.0f));
        }
        if ((flags & ANCHOR) != 0)
        {
            best = Math.max(best, bound(self, target, x, y, z, true, false, 1.0f));
        }
        return best;
    }

    private double bound(Side self, Side target, int x, int y, int z, boolean anchor, boolean bridge,
                         float targetExposure)
    {
        double[] centre = anchor ? CrystalPlacement.anchorCentre(x, y, z) : CrystalPlacement.crystalCentre(x, y, z);
        double cx = centre[0];
        double cy = centre[1];
        double cz = centre[2];
        float power = anchor ? CrystalPlacement.ANCHOR_POWER : CrystalPlacement.CRYSTAL_POWER;
        float cost = bridge ? OBSIDIAN_COST : anchor ? ANCHOR_COST : 0.0f;
        float hit = capped(target, target.x, target.y, target.z, cx, cy, cz, power, targetExposure);
        float lead = moving ? capped(target, leadBox.minX + Box.PLAYER_WIDTH / 2.0, leadBox.minY,
                leadBox.minZ + Box.PLAYER_WIDTH / 2.0, cx, cy, cz, power, 1.0f) : hit;
        float taken = capped(self, self.x, self.y, self.z, cx, cy, cz, power, 1.0f);
        return hit + LEAD_WEIGHT * lead + KILL_BONUS - SELF_WEIGHT * taken - cost
                - (taken >= self.health ? self.totem ? TOTEM_PENALTY : DEATH_PENALTY : 0.0f);
    }

    /**
     * Scores one placement and keeps it if it makes the cut. Returns 1 when it was scored, 0 when the upper
     * bound already ruled it out.
     */
    private int consider(List<Candidate> found, Side self, Side target, double reach, int x, int y, int z,
                         boolean anchor, boolean bridge)
    {
        Box cell = anchor ? CrystalPlacement.anchorCell(x, y, z) : CrystalPlacement.crystalCell(x, y, z);
        if (!withinReach(self.x, self.y + EYE_HEIGHT, self.z, reach, cell))
        {
            return 0;
        }
        if (cuts(found, (float) bound(self, target, x, y, z, anchor, bridge, 1.0f)))
        {
            return 0;
        }
        double[] centre = anchor ? CrystalPlacement.anchorCentre(x, y, z) : CrystalPlacement.crystalCentre(x, y, z);
        double cx = centre[0];
        double cy = centre[1];
        double cz = centre[2];
        float power = anchor ? CrystalPlacement.ANCHOR_POWER : CrystalPlacement.CRYSTAL_POWER;
        float cost = bridge ? OBSIDIAN_COST : anchor ? ANCHOR_COST : 0.0f;
        float targetExposure = SeenPercent.of(view, cx, cy, cz, targetBox);
        float targetDamage = damage(target, target.x, target.y, target.z, cx, cy, cz, power, targetExposure);
        if (cuts(found, (float) bound(self, target, x, y, z, anchor, bridge, targetExposure)))
        {
            return 0;
        }
        float leadExposure = moving ? SeenPercent.of(view, cx, cy, cz, leadBox) : targetExposure;
        float leadDamage = moving
                ? damage(target, leadBox.minX + Box.PLAYER_WIDTH / 2.0, leadBox.minY,
                        leadBox.minZ + Box.PLAYER_WIDTH / 2.0, cx, cy, cz, power, leadExposure)
                : targetDamage;
        float selfExposure = SeenPercent.of(view, cx, cy, cz, selfBox);
        float selfDamage = damage(self, self.x, self.y, self.z, cx, cy, cz, power, selfExposure);
        boolean selfLethal = selfDamage >= self.health;
        boolean kills = targetDamage >= target.health && !target.totem;
        float score = Math.min(targetDamage, target.health) + (kills ? KILL_BONUS : 0.0f)
                + LEAD_WEIGHT * Math.max(0.0f, leadDamage - targetDamage)
                - SELF_WEIGHT * selfDamage - cost;
        if (selfLethal)
        {
            score -= self.totem ? TOTEM_PENALTY : DEATH_PENALTY;
        }
        keep(found, new Candidate(x, y, z, anchor, bridge, cx, cy, cz, power, selfExposure, targetExposure,
                leadExposure, selfDamage, targetDamage, leadDamage, selfLethal, score));
        return 1;
    }

    /** Mirrors the box distance DuelSim.inReach measures. */
    private static boolean withinReach(double ex, double ey, double ez, double reach, Box cell)
    {
        double dx = Math.max(Math.max(cell.minX - ex, ex - cell.maxX), 0.0);
        double dy = Math.max(Math.max(cell.minY - ey, ey - cell.maxY), 0.0);
        double dz = Math.max(Math.max(cell.minZ - ez, ez - cell.maxZ), 0.0);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** Final health loss of one fighter, in the order of Player.hurtServer then LivingEntity.actuallyHurt. */
    private float damage(Side side, double px, double py, double pz, double cx, double cy, double cz,
                         float power, float exposure)
    {
        double dx = px - cx;
        double dy = py - cy;
        double dz = pz - cz;
        float raw = CombatMath.explosionDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), power, exposure);
        float scaled = CombatMath.playerDifficultyScale(raw, difficulty);
        if (scaled <= 0.0f)
        {
            return 0.0f;
        }
        return Math.max(0.0f, CombatMath.damageAfterDefences(scaled, side.armor, side.toughness, 0, side.epf));
    }

    /** {@link #damage} with the result capped by the health the fighter has left. */
    private float capped(Side side, double px, double py, double pz, double cx, double cy, double cz,
                         float power, float exposure)
    {
        return Math.min(side.health, damage(side, px, py, pz, cx, cy, cz, power, exposure));
    }

    /** True when the list is full and cannot be beaten any more by a placement scoring at most bound. */
    private static boolean cuts(List<Candidate> found, float bound)
    {
        return found.size() == RESULTS && bound < found.get(RESULTS - 1).score;
    }

    private static void keep(List<Candidate> found, Candidate candidate)
    {
        int at = 0;
        while (at < found.size() && found.get(at).score >= candidate.score)
        {
            at++;
        }
        found.add(at, candidate);
        if (found.size() > RESULTS)
        {
            found.remove(found.size() - 1);
        }
    }
}
