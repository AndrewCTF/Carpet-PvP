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

    /** WindCharge.RADIUS, the radius the wind charge burst explodes with. */
    public static final double WIND_CHARGE_RADIUS = 1.2;
    /** SimpleExplosionDamageCalculator knockback multiplier of the wind charge burst, for a player that is not flying. */
    public static final double WIND_CHARGE_KNOCKBACK = 1.22;
    /** Items registers the wind charge with useCooldown(0.5f); UseCooldown.ticks() is (int)(0.5f * 20). */
    public static final int WIND_CHARGE_COOLDOWN = 10;
    /** Radius of the Wind Burst enchantment effect, from data/minecraft/enchantment/wind_burst.json. */
    public static final double WIND_BURST_RADIUS = 3.5;
    /** Knockback multiplier of the Wind Burst effect per enchantment level, from wind_burst.json. */
    public static final double[] WIND_BURST_KNOCKBACK = {1.2, 1.75, 2.2};
    /** Items registers the ender pearl with useCooldown(1.0f), so 20 ticks. */
    public static final int PEARL_COOLDOWN = 20;
    /** Damage ThrownEnderpearl deals to the thrower on the way back; the ender_pearl type bypasses armour. */
    public static final float PEARL_RETURN_DAMAGE = 5.0f;
    /** MaceItem.hurtEnemy sets the attacker's vertical velocity to this after a smash hit. */
    public static final double SMASH_HIT_VERTICAL_VELOCITY = 0.01;

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
        /** Entity.fallDistance: only downward movement adds to it, and it is zeroed on the landing tick. */
        public double fallDistance;
        /** LivingEntity.isFallFlying. While true the mace cannot smash and the fall distance is pinned to 1. */
        public boolean gliding;
        /** View pitch in degrees while gliding, positive looking down, as Minecraft's x rotation. */
        public double glidePitch;
        public int windChargeCooldown;
        public int pearlCooldown;
        /** Density enchantment level of the held mace, 0 without it. */
        public int densityLevel;
        /** Wind Burst enchantment level of the held mace, 0 without it. */
        public int windBurstLevel;
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
            fallDistance = o.fallDistance;
            gliding = o.gliding;
            glidePitch = o.glidePitch;
            windChargeCooldown = o.windChargeCooldown;
            pearlCooldown = o.pearlCooldown;
            densityLevel = o.densityLevel;
            windBurstLevel = o.windBurstLevel;
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

    /** Points fighter who at the other one, the way step() does at the end of every tick. */
    public void face(int who, int other)
    {
        face(fighter(who), fighter(other));
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
        return inReach(att.x, att.y, att.z, tgt.x, tgt.y, tgt.z);
    }

    /** Feet position of the attacker and of the hitbox the attack has to reach. */
    public static boolean inReach(double ax, double ay, double az, double tx, double ty, double tz)
    {
        double ex = ax;
        double ey = ay + EYE_HEIGHT;
        double ez = az;
        double dx = Math.max(Math.max(tx - HALF_WIDTH - ex, ex - (tx + HALF_WIDTH)), 0.0);
        double dy = Math.max(Math.max(ty - ey, ey - (ty + HEIGHT)), 0.0);
        double dz = Math.max(Math.max(tz - HALF_WIDTH - ez, ez - (tz + HALF_WIDTH)), 0.0);
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
        if (f.windChargeCooldown > 0)
        {
            f.windChargeCooldown--;
        }
        if (f.pearlCooldown > 0)
        {
            f.pearlCooldown--;
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
        if (f.gliding)
        {
            glide(f);
            return;
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

        move(f);

        float drag = friction * AIR_DRAG;
        f.vx *= drag;
        f.vz *= drag;
        f.vy = (f.vy - GRAVITY) * VERTICAL_DRAG;
    }

    /**
     * Mirrors LivingEntity.travelFallFlying: checkFallDistanceAccumulation, then updateFallFlyingMovement,
     * then Entity.move.
     */
    private void glide(Fighter f)
    {
        checkFallDistanceAccumulation(f);
        double pitch = f.glidePitch * 0.017453292;
        double cos = Math.cos(pitch);
        double lookX = -f.sinYaw * cos;
        double lookZ = f.cosYaw * cos;
        double lookFlat = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double horiz = Math.sqrt(f.vx * f.vx + f.vz * f.vz);
        double cosSq = cos * cos;
        double vx = f.vx;
        double vy = f.vy + GRAVITY * (0.75 * cosSq - 1.0);
        double vz = f.vz;
        if (vy < 0.0 && lookFlat > 0.0)
        {
            double g = vy * -0.1 * cosSq;
            vx += lookX * g / lookFlat;
            vy += g;
            vz += lookZ * g / lookFlat;
        }
        if (pitch < 0.0 && lookFlat > 0.0)
        {
            double g = horiz * Math.sin(pitch) * -0.04;
            vx += -lookX * g / lookFlat;
            vy += g * 3.2;
            vz += -lookZ * g / lookFlat;
        }
        if (lookFlat > 0.0)
        {
            vx += (lookX / lookFlat * horiz - vx) * 0.1;
            vz += (lookZ / lookFlat * horiz - vz) * 0.1;
        }
        f.vx = vx * 0.99;
        f.vy = vy * 0.98;
        f.vz = vz * 0.99;
        move(f);
    }

    /** Mirrors Entity.checkFallDistanceAccumulation: the counter is pinned to 1 while the sink is shallow. */
    private static void checkFallDistanceAccumulation(Fighter f)
    {
        if (f.vy > -0.5 && f.fallDistance > 1.0)
        {
            f.fallDistance = 1.0;
        }
    }

    /**
     * Mirrors Entity.move on flat ground followed by Entity.checkFallDamage: only the downward part of the
     * tick's movement adds to the fall distance, and the counter is zeroed on the landing tick.
     */
    private static void move(Fighter f)
    {
        double ny = f.y + f.vy;
        double moved = ny - f.y;
        f.x += f.vx;
        f.z += f.vz;
        if (ny <= 0.0)
        {
            moved = -f.y;
            f.y = 0.0;
            f.vy = 0.0;
            f.onGround = true;
        }
        else
        {
            f.y = ny;
            f.onGround = false;
        }
        if (moved < 0.0)
        {
            f.fallDistance -= moved;
        }
        if (f.onGround)
        {
            f.fallDistance = 0.0;
        }
    }

    /**
     * Mirrors ServerExplosion.hurtEntities: the velocity an explosion of the given radius and knockback
     * multiplier adds to a fighter whose feet are at (fx, fy, fz) when it bursts at (bx, by, bz). Entities
     * farther away than twice the radius are left alone, the direction points from the burst to the eye, and
     * the magnitude is (1 - ratio) * exposure * multiplier. A player has no explosion knockback resistance.
     * Writes {vx, vy, vz} into out.
     */
    public static void explosionImpulse(double[] out, double fx, double fy, double fz, double bx, double by, double bz,
                                       double radius, double knockbackMultiplier, double exposure)
    {
        double dx = fx - bx;
        double dy = fy - by;
        double dz = fz - bz;
        double ratio = Math.sqrt(dx * dx + dy * dy + dz * dz) / (radius * 2.0);
        if (ratio > 1.0)
        {
            out[0] = out[1] = out[2] = 0.0;
            return;
        }
        double ey = dy + EYE_HEIGHT;
        double len = Math.sqrt(dx * dx + ey * ey);
        if (len < 1.0E-5)
        {
            out[0] = out[1] = out[2] = 0.0;
            return;
        }
        double factor = (1.0 - ratio) * exposure * knockbackMultiplier;
        out[0] = dx / len * factor;
        out[1] = ey / len * factor;
        out[2] = dz / len * factor;
    }

    /** Starts gliding, which needs an elytra and an off ground fighter (LivingEntity.canGlide). */
    public void startGlide(int who, double pitchDegrees)
    {
        Fighter f = fighter(who);
        if (f.onGround)
        {
            return;
        }
        f.gliding = true;
        f.glidePitch = pitchDegrees;
    }

    /** Mirrors LivingEntity.stopFallFlying, after which the fall distance accumulates again. */
    public void stopGlide(int who)
    {
        fighter(who).gliding = false;
    }

    /**
     * Bursts a wind charge at the given offset from the fighter's feet and adds the impulse to its velocity.
     * A charge fired straight down lands on the fighter's own ground block, so a burst at its feet sits a
     * quarter of a block above them. False while the item is still on cooldown.
     */
    public boolean windCharge(int who, double dx, double dy, double dz)
    {
        Fighter f = fighter(who);
        if (f.windChargeCooldown > 0)
        {
            return false;
        }
        explosionImpulse(kb, f.x, f.y, f.z, f.x + dx, f.y + dy, f.z + dz,
                WIND_CHARGE_RADIUS, WIND_CHARGE_KNOCKBACK, 1.0);
        f.vx += kb[0];
        f.vy += kb[1];
        f.vz += kb[2];
        f.windChargeCooldown = WIND_CHARGE_COOLDOWN;
        return true;
    }

    /** The Wind Burst post attack effect of wind_burst.json: a burst on the attacker with a level dependent multiplier. */
    public void windBurst(int who)
    {
        Fighter f = fighter(who);
        if (f.windBurstLevel <= 0)
        {
            return;
        }
        explosionImpulse(kb, f.x, f.y, f.z, f.x, f.y, f.z, WIND_BURST_RADIUS,
                WIND_BURST_KNOCKBACK[Math.min(f.windBurstLevel, WIND_BURST_KNOCKBACK.length) - 1], 1.0);
        f.vx += kb[0];
        f.vy += kb[1];
        f.vz += kb[2];
    }

    /**
     * Mirrors ThrownEnderpearl.onHit: the thrower teleports with zero velocity and its fall distance is reset.
     * False while the pearl is still on cooldown.
     */
    public boolean pearl(int who, double x, double y, double z)
    {
        Fighter f = fighter(who);
        if (f.pearlCooldown > 0)
        {
            return false;
        }
        f.x = x;
        f.y = y;
        f.z = z;
        f.vx = 0.0;
        f.vy = 0.0;
        f.vz = 0.0;
        f.fallDistance = 0.0;
        f.gliding = false;
        f.pearlCooldown = PEARL_COOLDOWN;
        return true;
    }

    /**
     * The attacker's side of a landed smash hit: MaceItem.hurtEnemy pins the vertical velocity to 0.01 and
     * MaceItem.postHurtEnemy clears the fall distance, then the Wind Burst effect launches the attacker again.
     */
    public void smashHit(int who)
    {
        Fighter f = fighter(who);
        f.vy = SMASH_HIT_VERTICAL_VELOCITY;
        f.gliding = false;
        f.fallDistance = 0.0;
        windBurst(who);
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
