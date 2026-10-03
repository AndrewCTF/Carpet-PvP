package carpet.pvp.sim;

/**
 * Picks where a bot should lay a rail and a tnt minecart to blow a target up, and how much of the blast reaches
 * either fighter.
 *
 * <p>The blast of a tnt minecart is not a fixed power: {@code MinecartTNT.explode} scales its base of four by
 * one plus a random share of one and a half, so the same cart goes off anywhere between four and ten. A player
 * lays the cart down next to the target and then runs; the bot does the same, so the placement is scored for the
 * damage the target is likely to take and the bot's own survival is a question asked at the moment it lights the
 * cart, which is {@link #survivable} rather than something baked into the choice.</p>
 *
 * <p>The two fighters are described with {@link CrystalSearch.Side}, which is nothing but a place, a velocity
 * and what a fighter is wearing, so both styles can share it.</p>
 */
public final class TntCartPlan
{
    /** {@code MinecartTNT.explosionPowerBase}: the power a stationary cart starts from. */
    public static final float POWER_LOW = 4.0f;
    /** The mean of that base times one and a random share of one and a half, which is what a cart usually does. */
    public static final float POWER_MEAN = 7.0f;
    /** The same cart with the largest random factor the game can draw. */
    public static final float POWER_HIGH = 10.0f;
    /** How far the bot's eyes reach a block, which is what decides where a rail can be laid. */
    public static final double REACH = 4.5;
    /** What a blast has to take off the bot before it is not worth lighting the cart at all. */
    public static final float SELF_WEIGHT = 0.75f;
    private static final double EYE_HEIGHT = 1.62;

    private final ExplosionView view;

    public TntCartPlan(ExplosionView view)
    {
        this.view = view;
    }

    /** One place to put the rail and the cart on, and what the blast there would do to the target. */
    public static final class Placement
    {
        /** The block the rail goes on; the cart then rides in the cell above it. */
        public final int x;
        public final int y;
        public final int z;
        /** Where the cart sits, which is where the blast goes off. */
        public final double cx;
        public final double cy;
        public final double cz;
        public final float targetDamage;
        public final float score;

        private Placement(int x, int y, int z, double cx, double cy, double cz, float targetDamage, float score)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.targetDamage = targetDamage;
            this.score = score;
        }

        /** The cell the rail goes into, for a caller that wants to place it. */
        public Box cell()
        {
            return Box.block(x, y, z);
        }
    }

    /**
     * The best block within reach of the bot's eyes to lay the rail on, or null when there is none worth laying.
     * The rail needs something solid under it and an empty cell for the cart above, that cell has to be clear of
     * both fighters, and the blast has to be worth more than running away from it costs.
     *
     * @param halfX half width of the searched volume around the bot, in blocks
     * @param halfY half height of the searched volume around the bot, in blocks
     * @param halfZ half depth of the searched volume around the bot, in blocks
     */
    public Placement choose(CrystalSearch.Side self, CrystalSearch.Side target,
            int halfX, int halfY, int halfZ, int difficulty)
    {
        Box selfBox = self.box();
        Box targetBox = target.box();
        int baseX = (int) Math.floor(self.x);
        int baseY = (int) Math.floor(self.y);
        int baseZ = (int) Math.floor(self.z);
        Placement best = null;
        for (int x = baseX - halfX; x <= baseX + halfX; x++)
        {
            for (int z = baseZ - halfZ; z <= baseZ + halfZ; z++)
            {
                for (int y = baseY - halfY; y <= baseY + halfY; y++)
                {
                    if (!view.blocksExplosion(x, y - 1, z) || !view.isAir(x, y, z))
                    {
                        continue;
                    }
                    Box cell = Box.block(x, y, z);
                    if (cell.intersects(selfBox) || cell.intersects(targetBox))
                    {
                        continue;
                    }
                    if (!withinReach(self.x, self.y + EYE_HEIGHT, self.z, REACH, cell))
                    {
                        continue;
                    }
                    double cx = x + 0.5;
                    double cy = y + 0.5;
                    double cz = z + 0.5;
                    float damage = damage(target, targetBox, cx, cy, cz, POWER_MEAN, difficulty);
                    if (damage <= 0.0F)
                    {
                        continue;
                    }
                    // Laying a rail and a cart is the bot's time, so it only does it when the target is worth
                    // more than the share of its own health the bot expects to pay for standing there.
                    float selfDamage = damage(self, selfBox, cx, cy, cz, POWER_MEAN, difficulty);
                    float score = Math.min(damage, target.health) - SELF_WEIGHT * selfDamage;
                    if (best != null && score <= best.score)
                    {
                        continue;
                    }
                    best = new Placement(x, y, z, cx, cy, cz, damage, score);
                }
            }
        }
        return best;
    }

    /**
     * Whether the bot can light a cart sitting at (cx, cy, cz) where it stands now: the blast has to leave it
     * alive, which is the rule the crystal style keeps for a crystal it cannot walk away from in time.
     */
    public boolean survivable(CrystalSearch.Side self, double cx, double cy, double cz, int difficulty)
    {
        return damage(self, self.box(), cx, cy, cz, POWER_MEAN, difficulty) < self.health;
    }

    /** The health a blast of the given power takes off one fighter, with what it is wearing against it. */
    public float damage(CrystalSearch.Side side, double cx, double cy, double cz, float power, int difficulty)
    {
        return damage(side, side.box(), cx, cy, cz, power, difficulty);
    }

    private float damage(CrystalSearch.Side side, Box box, double cx, double cy, double cz, float power,
            int difficulty)
    {
        double dx = side.x - cx;
        double dy = side.y - cy;
        double dz = side.z - cz;
        float raw = CombatMath.explosionDamage(Math.sqrt(dx * dx + dy * dy + dz * dz), power,
                SeenPercent.of(view, cx, cy, cz, box));
        return Math.max(0.0f, CombatMath.damageAfterDefences(
                CombatMath.playerDifficultyScale(raw, difficulty), side.armor, side.toughness, 0, side.epf));
    }

    /** Mirrors the box distance AttackRange.isInRange measures, for the bot's own reach. */
    private static boolean withinReach(double ex, double ey, double ez, double reach, Box cell)
    {
        double dx = Math.max(Math.max(cell.minX - ex, ex - cell.maxX), 0.0);
        double dy = Math.max(Math.max(cell.minY - ey, ey - cell.maxY), 0.0);
        double dz = Math.max(Math.max(cell.minZ - ez, ez - cell.maxZ), 0.0);
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }
}
