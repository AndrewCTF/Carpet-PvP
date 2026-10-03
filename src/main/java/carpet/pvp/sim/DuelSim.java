package carpet.pvp.sim;

/**
 * Two-fighter melee duel on flat ground at y = 0. Fighter 0 is A, fighter 1 is B.
 * Positions are feet positions; yaw is stored as sin/cos and each fighter faces the other.
 * Actions are ints in [0, ACTION_COUNT); see {@link #action}.
 */
public final class DuelSim
{
    public static final int ACTION_COUNT = 72;
    public static final int NOOP = action(0, 0, false, false, false);

    /** Attributes.GRAVITY default. */
    public static final double GRAVITY = 0.08;
    /** Attributes.JUMP_STRENGTH default (0.42f). */
    public static final double JUMP_POWER = 0.42f;
    /** Attributes.MOVEMENT_SPEED of a player, and with the sprinting ADD_MULTIPLIED_TOTAL 0.3 modifier. */
    public static final float WALK_SPEED = 0.1f;
    public static final float SPRINT_SPEED = (float) (0.10000000149011612 * (1.0 + 0.30000001192092896));
    /** Player.getFlyingSpeed when not in creative flight. */
    public static final float AIR_ACCEL = 0.02f;
    public static final float AIR_ACCEL_SPRINT = 0.025999999f;
    /** LivingEntity.applyInput scale on xxa and zza. */
    public static final float INPUT_SCALE = 0.98f;
    /** Block.getFriction of the ground and the friction-influenced speed factor 0.21600002 / friction^3. */
    public static final float BLOCK_FRICTION = 0.6f;
    public static final float GROUND_ACCEL_FACTOR = 0.21600002f / (BLOCK_FRICTION * BLOCK_FRICTION * BLOCK_FRICTION);
    /** Horizontal air drag, applied to ground friction as friction * 0.91f. */
    public static final float AIR_DRAG = 0.91f;
    public static final float VERTICAL_DRAG = 0.98f;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final int JUMP_DELAY = 10;
    /** Player.DEFAULT_ENTITY_INTERACTION_RANGE. */
    public static final double REACH = 3.0;
    public static final double EYE_HEIGHT = 1.62;
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    private static final double FRONT_COS = 0.5;

    public static final class Fighter
    {
        public double x;
        public double y;
        public double z;
        public double vx;
        public double vy;
        public double vz;
        public double sinYaw;
        public double cosYaw = 1.0;
        public boolean onGround = true;
        public boolean sprinting;
        /** True after a sprint hit until the fighter releases sprint. */
        public boolean sprintLocked;
        public float health = 20.0f;
        public int ticksSinceSwing = 100;
        public int invulTime;
        public float lastHurt;
        public int noJumpDelay;

        public double baseDamage = 8.0;
        public double attackSpeed = 1.6;
        public float enchantBonus;
        public float armor;
        public float toughness;
        public float epf;
        public double knockbackResistance;
        /** Smallest ticksSinceSwing whose charge passes the attack gate. */
        public int gateTicks = CombatMath.minTicksForGate(1.6);

        public void setLoadout(double baseDamage, double attackSpeed, float enchantBonus, float armor, float toughness,
                               float epf, double knockbackResistance)
        {
            this.baseDamage = baseDamage;
            this.attackSpeed = attackSpeed;
            this.enchantBonus = enchantBonus;
            this.armor = armor;
            this.toughness = toughness;
            this.epf = epf;
            this.knockbackResistance = knockbackResistance;
            this.gateTicks = CombatMath.minTicksForGate(attackSpeed);
        }

        public void copyFrom(Fighter o)
        {
            x = o.x;
            y = o.y;
            z = o.z;
            vx = o.vx;
            vy = o.vy;
            vz = o.vz;
            sinYaw = o.sinYaw;
            cosYaw = o.cosYaw;
            onGround = o.onGround;
            sprinting = o.sprinting;
            sprintLocked = o.sprintLocked;
            health = o.health;
            ticksSinceSwing = o.ticksSinceSwing;
            invulTime = o.invulTime;
            lastHurt = o.lastHurt;
            noJumpDelay = o.noJumpDelay;
            baseDamage = o.baseDamage;
            attackSpeed = o.attackSpeed;
            enchantBonus = o.enchantBonus;
            armor = o.armor;
            toughness = o.toughness;
            epf = o.epf;
            knockbackResistance = o.knockbackResistance;
            gateTicks = o.gateTicks;
        }

        /** Yaw in degrees, Minecraft convention (0 faces +z). */
        public double yawDegrees()
        {
            return Math.toDegrees(Math.atan2(sinYaw, cosYaw));
        }
    }

    public final Fighter a = new Fighter();
    public final Fighter b = new Fighter();
    /** Number of step calls on this instance, for budget accounting. */
    public long steps;
    private final double[] kb = new double[3];

    /** Encodes forward and strafe in {-1, 0, 1} (strafe +1 is left) and the three buttons into an action. */
    public static int action(int forward, int strafe, boolean jump, boolean sprint, boolean attack)
    {
        return ((forward + 1) * 3 + (strafe + 1)) * 8 + (jump ? 4 : 0) + (sprint ? 2 : 0) + (attack ? 1 : 0);
    }

    public static int forward(int action)
    {
        return (action >> 3) / 3 - 1;
    }

    public static int strafe(int action)
    {
        return (action >> 3) % 3 - 1;
    }

    public static boolean jump(int action)
    {
        return (action & 4) != 0;
    }

    public static boolean sprint(int action)
    {
        return (action & 2) != 0;
    }

    public static boolean attack(int action)
    {
        return (action & 1) != 0;
    }

    public Fighter fighter(int who)
    {
        return who == 0 ? a : b;
    }

    /** Places A at the origin and B the given distance along +z, both on the ground and facing each other. */
    public void placeFacing(double distance)
    {
        a.x = 0.0;
        a.y = 0.0;
        a.z = 0.0;
        b.x = 0.0;
        b.y = 0.0;
        b.z = distance;
        face(a, b);
        face(b, a);
    }

    public void copyFrom(DuelSim o)
    {
        a.copyFrom(o.a);
        b.copyFrom(o.b);
    }

    public boolean over()
    {
        return a.health <= 0.0f || b.health <= 0.0f;
    }

    public double horizontalDistance()
    {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Mirrors ServerPlayer.isWithinAttackRange with AttackRange.defaultFor: eye to hitbox distance at most the interaction range. */
    public static boolean inReach(Fighter att, Fighter tgt)
    {
        double ex = att.x;
        double ey = att.y + EYE_HEIGHT;
        double ez = att.z;
        double dx = Math.max(Math.max(tgt.x - HALF_WIDTH - ex, ex - (tgt.x + HALF_WIDTH)), 0.0);
        double dy = Math.max(Math.max(tgt.y - ey, ey - (tgt.y + HEIGHT)), 0.0);
        double dz = Math.max(Math.max(tgt.z - HALF_WIDTH - ez, ez - (tgt.z + HALF_WIDTH)), 0.0);
        return dx * dx + dy * dy + dz * dz <= REACH * REACH;
    }

    private static boolean inFront(Fighter att, Fighter tgt)
    {
        double dx = tgt.x - att.x;
        double dz = tgt.z - att.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 2.0 * HALF_WIDTH)
        {
            return true;
        }
        return (-att.sinYaw * dx + att.cosYaw * dz) / len >= FRONT_COS;
    }

    private static void face(Fighter f, Fighter other)
    {
        double dx = other.x - f.x;
        double dz = other.z - f.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0E-6)
        {
            f.sinYaw = -dx / len;
            f.cosYaw = dz / len;
        }
    }

    /** Advances one game tick: attack packets first, then each fighter's LivingEntity.aiStep, then entity pushing. */
    public void step(int actionA, int actionB)
    {
        steps++;
        if (a.health <= 0.0f || b.health <= 0.0f)
        {
            actionA = NOOP;
            actionB = NOOP;
        }
        attack(a, b, actionA);
        attack(b, a, actionB);
        aiStep(a, actionA);
        aiStep(b, actionB);
        push();
        face(a, b);
        face(b, a);
    }

    /** Mirrors Player.attack with the damage path of LivingEntity.hurtServer. */
    private void attack(Fighter att, Fighter tgt, int action)
    {
        if (!attack(action))
        {
            return;
        }
        float scale = CombatMath.chargeScale(att.ticksSinceSwing, att.attackSpeed);
        att.ticksSinceSwing = 0;
        if (!inReach(att, tgt) || !inFront(att, tgt))
        {
            return;
        }
        boolean gate = CombatMath.passesChargeGate(scale);
        boolean falling = !att.onGround && att.vy < 0.0;
        boolean crit = CombatMath.isCritical(falling, att.onGround, false, false, false, false, true, att.sprinting, gate);
        boolean sprintHit = att.sprinting && gate;
        float incoming = CombatMath.attackDamage((float) att.baseDamage, 0.0f, att.enchantBonus, scale, crit);
        float effective = CombatMath.invulnerabilityDamage(incoming, tgt.lastHurt, tgt.invulTime);
        if (effective <= 0.0f)
        {
            return;
        }
        boolean fresh = tgt.invulTime <= CombatMath.INVULNERABLE_WINDOW_THRESHOLD;
        tgt.health -= CombatMath.damageAfterDefences(effective, tgt.armor, tgt.toughness, 0, tgt.epf);
        tgt.lastHurt = incoming;
        if (!fresh)
        {
            return;
        }
        tgt.invulTime = CombatMath.INVULNERABLE_TICKS_AFTER_HIT;
        CombatMath.knockback(kb, CombatMath.DEFAULT_HIT_KNOCKBACK, att.x - tgt.x, att.z - tgt.z,
                tgt.knockbackResistance, tgt.onGround, tgt.vx, tgt.vy, tgt.vz);
        tgt.vx = kb[0];
        tgt.vy = kb[1];
        tgt.vz = kb[2];
        if (sprintHit)
        {
            float extra = CombatMath.extraKnockbackStrength(0.0f, true);
            CombatMath.knockback(kb, extra, att.sinYaw, -att.cosYaw, tgt.knockbackResistance, tgt.onGround,
                    tgt.vx, tgt.vy, tgt.vz);
            tgt.vx = kb[0];
            tgt.vy = kb[1];
            tgt.vz = kb[2];
            att.vx *= CombatMath.ATTACKER_HORIZONTAL_FACTOR;
            att.vz *= CombatMath.ATTACKER_HORIZONTAL_FACTOR;
            att.sprinting = CombatMath.attackerSprintingAfterKnockback(extra, att.sprinting);
            att.sprintLocked = true;
        }
    }

    /** Mirrors LivingEntity.aiStep (velocity cutoffs, applyInput, jump) followed by travelInAir and Player.tick counters. */
    private void aiStep(Fighter f, int action)
    {
        if (f.health <= 0.0f)
        {
            action = NOOP;
        }
        f.ticksSinceSwing++;
        if (f.invulTime > 0)
        {
            f.invulTime--;
        }
        int forward = forward(action);
        if (!sprint(action))
        {
            f.sprinting = false;
            f.sprintLocked = false;
        }
        else if (forward <= 0)
        {
            f.sprinting = false;
        }
        else if (!f.sprintLocked)
        {
            f.sprinting = true;
        }

        if (f.noJumpDelay > 0)
        {
            f.noJumpDelay--;
        }
        if (f.vx * f.vx + f.vz * f.vz < 9.0E-6)
        {
            f.vx = 0.0;
            f.vz = 0.0;
        }
        if (Math.abs(f.vy) < 0.003)
        {
            f.vy = 0.0;
        }
        float zza = forward * INPUT_SCALE;
        float xxa = strafe(action) * INPUT_SCALE;

        if (jump(action))
        {
            if (f.onGround && f.noJumpDelay == 0)
            {
                f.vy = Math.max(JUMP_POWER, f.vy);
                if (f.sprinting)
                {
                    f.vx += -f.sinYaw * SPRINT_JUMP_BOOST;
                    f.vz += f.cosYaw * SPRINT_JUMP_BOOST;
                }
                f.noJumpDelay = JUMP_DELAY;
            }
        }
        else
        {
            f.noJumpDelay = 0;
        }

        boolean wasOnGround = f.onGround;
        float friction = wasOnGround ? BLOCK_FRICTION : 1.0f;
        float accel;
        if (wasOnGround)
        {
            accel = (f.sprinting ? SPRINT_SPEED : WALK_SPEED) * GROUND_ACCEL_FACTOR;
        }
        else
        {
            accel = f.sprinting ? AIR_ACCEL_SPRINT : AIR_ACCEL;
        }
        double lenSq = (double) xxa * xxa + (double) zza * zza;
        if (lenSq >= 1.0E-7)
        {
            double scale = lenSq > 1.0 ? accel / Math.sqrt(lenSq) : accel;
            double ix = xxa * scale;
            double iz = zza * scale;
            f.vx += ix * f.cosYaw - iz * f.sinYaw;
            f.vz += iz * f.cosYaw + ix * f.sinYaw;
        }

        f.x += f.vx;
        f.z += f.vz;
        double ny = f.y + f.vy;
        if (ny <= 0.0)
        {
            f.y = 0.0;
            f.vy = 0.0;
            f.onGround = true;
        }
        else
        {
            f.y = ny;
            f.onGround = false;
        }

        float drag = friction * AIR_DRAG;
        f.vx *= drag;
        f.vz *= drag;
        f.vy = (f.vy - GRAVITY) * VERTICAL_DRAG;
    }

    /** Mirrors Entity.push(Entity) applied symmetrically to overlapping fighters (LivingEntity.pushEntities). */
    private void push()
    {
        if (Math.abs(a.x - b.x) >= 2.0 * HALF_WIDTH || Math.abs(a.z - b.z) >= 2.0 * HALF_WIDTH
                || Math.abs(a.y - b.y) >= HEIGHT)
        {
            return;
        }
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double m = Math.max(Math.abs(dx), Math.abs(dz));
        if (m < 0.01)
        {
            return;
        }
        m = Math.sqrt(m);
        dx /= m;
        dz /= m;
        double inv = Math.min(1.0 / m, 1.0);
        dx *= inv * 0.05;
        dz *= inv * 0.05;
        a.vx -= dx;
        a.vz -= dz;
        b.vx += dx;
        b.vz += dz;
    }
}
