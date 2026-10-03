package carpet.pvp.sim;

/**
 * Picks how to engage a target: walk in, wind charge jump, ender pearl or elytra dive. A direct enumeration
 * beats a rolling horizon here: the four options are fixed scripts rather than a policy over movement inputs,
 * and each is scored on the same rollout, so they are directly comparable. The score is the damage actually
 * dealt, capped at what the target has left, minus what the target's own swings take off the attacker while it
 * is exposed, over the number of ticks it takes.
 */
public final class EngagePlanner
{
    public static final int APPROACHES = 4;
    public static final int WALK_IN = 0;
    public static final int WIND_CHARGE = 1;
    public static final int PEARL = 2;
    public static final int ELYTRA = 3;
    /**
     * How far behind the fighter a wind charge may burst, in blocks. A burst under the fighter's own feet is the
     * straightest jump but carries nothing forward, and each block moved back trades some of the upward impulse
     * for a forward one, so the best offset depends on how far away the target is.
     */
    public static final double[] WIND_CHARGE_BURSTS = {0.0, 0.5, 1.0};
    /** A quarter of a block above the ground face the charge lands on, from AbstractWindCharge.onHitBlock. */
    private static final double BURST_HEIGHT = 0.25;
    /** How close a glider gets before it closes the elytra and drops into the smash. */
    private static final double DIVE_CLOSE = 1.2;
    /** Pitch of the dive, in degrees looking down. */
    private static final double DIVE_PITCH = 60.0;
    /** Height above the target an elytra dive has to start from for the glide to reach it at all. */
    private static final double ELYTRA_MIN_HEIGHT = 6.0;

    /** What one approach is worth and what it costs. */
    public static final class Option
    {
        public int approach;
        public int ticks;
        public float dealt;
        public float taken;
        public double fallDistance;
        public boolean feasible;

        /** Net damage per tick; the planner maximises this. A swing on the first tick counts as one tick. */
        public float score()
        {
            return feasible ? (dealt - taken) / Math.max(ticks, 1) : Float.NEGATIVE_INFINITY;
        }
    }

    /** The target's ability to hit back, which is what makes staying in its reach while airborne costly. */
    public static final class Threat
    {
        public float baseDamage = 8.0f;
        public double attackSpeed = 1.6;
        public float enchantBonus;
        public int breachLevel;
        /** Ticks between the target's swings; it needs a full charge, like LivingEntity.attack requires. */
        public int swingPeriod = Math.round(CombatMath.fullChargeTicks(1.6f));
        public int ticksSinceSwing = 100;

        /** One of the target's swings landing on the attacker, through the invulnerability window and its defences. */
        public float damage(DuelSim.Fighter att)
        {
            float scale = CombatMath.chargeScale(ticksSinceSwing, attackSpeed);
            boolean gate = CombatMath.passesChargeGate(scale);
            boolean crit = gate && !att.onGround && att.vy < 0.0 && !att.sprinting;
            float raw = CombatMath.attackDamage(baseDamage, 0.0f, enchantBonus, scale, crit);
            float effective = CombatMath.invulnerabilityDamage(raw, att.lastHurt, att.invulTime);
            if (effective <= 0.0f)
            {
                return 0.0f;
            }
            if (att.invulTime <= CombatMath.INVULNERABLE_WINDOW_THRESHOLD)
            {
                att.invulTime = CombatMath.INVULNERABLE_TICKS_AFTER_HIT;
            }
            att.lastHurt = raw;
            return CombatMath.damageAfterDefences(effective, att.armor, att.toughness, breachLevel, att.epf);
        }
    }

    private EngagePlanner()
    {
    }

    /** Scores every approach against the state the given sim is in, leaving the sim itself untouched. */
    public static Option[] choose(DuelSim sim, int attacker, int breachLevel, Threat threat, int maxTicks)
    {
        DuelSim scratch = new DuelSim();
        Option[] options = new Option[APPROACHES];
        options[WALK_IN] = run(scratch, sim, attacker, breachLevel, threat, maxTicks, WALK_IN, 0.0);
        options[PEARL] = run(scratch, sim, attacker, breachLevel, threat, maxTicks, PEARL, 0.0);
        options[ELYTRA] = run(scratch, sim, attacker, breachLevel, threat, maxTicks, ELYTRA, 0.0);
        options[WIND_CHARGE] = run(scratch, sim, attacker, breachLevel, threat, maxTicks, WIND_CHARGE,
                WIND_CHARGE_BURSTS[0]);
        for (double back : WIND_CHARGE_BURSTS)
        {
            Option option = run(scratch, sim, attacker, breachLevel, threat, maxTicks, WIND_CHARGE, back);
            if (option.score() > options[WIND_CHARGE].score())
            {
                options[WIND_CHARGE] = option;
            }
        }
        return options;
    }

    /** One rollout from a fresh copy of the state, so the approaches cannot see each other's damage. */
    private static Option run(DuelSim scratch, DuelSim state, int attacker, int breachLevel, Threat threat,
                              int maxTicks, int approach, double burstBack)
    {
        scratch.copyFrom(state);
        int sinceSwing = threat.ticksSinceSwing;
        Option option = rollout(scratch, attacker, breachLevel, threat, maxTicks, approach, burstBack);
        threat.ticksSinceSwing = sinceSwing;
        return option;
    }

    /** The approach with the highest score, or null when none of them can reach the target. */
    public static Option best(Option[] options)
    {
        Option best = null;
        for (Option option : options)
        {
            if (option.feasible && (best == null || option.score() > best.score()))
            {
                best = option;
            }
        }
        return best;
    }

    /**
     * Runs one approach tick by tick and stops at the attacker's first hit. The attacker holds forward and
     * sprints on the ground only, since Player.canCriticalAttack refuses a crit while sprinting and the extra
     * ground speed is worth more than the air speed. The target never moves but swings on its own cadence
     * whenever the attacker is inside its reach.
     */
    private static Option rollout(DuelSim sim, int attacker, int breachLevel, Threat threat, int maxTicks,
                                  int approach, double burstBack)
    {
        Option option = new Option();
        option.approach = approach;
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        if (!enter(sim, attacker, approach, burstBack, option))
        {
            return option;
        }
        for (int t = 0; t < maxTicks; t++)
        {
            sim.face(attacker, 1 - attacker);
            if (approach == ELYTRA && att.gliding && horizontalGap(att, tgt) <= DIVE_CLOSE && att.vy < 0.0)
            {
                sim.stopGlide(attacker);
            }
            if (land(sim, attacker, breachLevel, option, approach == WIND_CHARGE || approach == ELYTRA))
            {
                option.ticks = t;
                option.feasible = true;
                return option;
            }
            if (threat.swingPeriod > 0 && DuelSim.inReach(tgt, att)
                    && t - threat.ticksSinceSwing >= threat.swingPeriod)
            {
                threat.ticksSinceSwing = t;
                option.taken += threat.damage(att);
            }
            sim.step(DuelSim.action(1, 0, false, att.onGround, false), DuelSim.NOOP);
        }
        option.fallDistance = att.fallDistance;
        return option;
    }

    /** The opening move of an approach. False when the attacker lacks the item or the height for it. */
    private static boolean enter(DuelSim sim, int attacker, int approach, double burstBack, Option option)
    {
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        switch (approach)
        {
            case WALK_IN -> {
                return true;
            }
            case WIND_CHARGE -> {
                return sim.windCharge(attacker, att.sinYaw * burstBack, BURST_HEIGHT, -att.cosYaw * burstBack);
            }
            case PEARL -> {
                if (!sim.pearl(attacker, tgt.x, tgt.y, tgt.z))
                {
                    return false;
                }
                option.taken = DuelSim.PEARL_RETURN_DAMAGE;
                return true;
            }
            case ELYTRA -> {
                if (att.y - tgt.y < ELYTRA_MIN_HEIGHT)
                {
                    return false;
                }
                sim.startGlide(attacker, DIVE_PITCH);
                return true;
            }
            default -> throw new IllegalArgumentException("approach " + approach);
        }
    }

    /** The attacker's swing for this tick, if it lands, and what it does to the target. */
    private static boolean land(DuelSim sim, int attacker, int breachLevel, Option option, boolean needsSmash)
    {
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        boolean smash = SmashTiming.canSmash(att);
        if (needsSmash && !smash)
        {
            return false;
        }
        if (!SmashTiming.maySwing(att, tgt))
        {
            return false;
        }
        float damage = SmashTiming.damageDealt(att, tgt, breachLevel, smash ? att.fallDistance : 0.0);
        if (damage <= 0.0f)
        {
            return false;
        }
        option.dealt = Math.min(damage, tgt.health);
        option.fallDistance = att.fallDistance;
        att.ticksSinceSwing = 0;
        if (smash)
        {
            sim.smashHit(attacker);
        }
        return true;
    }

    private static double horizontalGap(DuelSim.Fighter att, DuelSim.Fighter tgt)
    {
        return Math.hypot(tgt.x - att.x, tgt.z - att.z);
    }
}
