package carpet.pvp.sim;

/**
 * When a falling fighter can land a mace smash, how much it hurts, and how to get the hit past a shield.
 * All damage goes through {@link CombatMath}, which mirrors the vanilla damage assembly.
 */
public final class SmashTiming
{
    /** MaceItem.DEFAULT_ATTACK_DAMAGE modifier of 5 plus the 1 of an empty hand, and its -3.4 attack speed. */
    public static final double MACE_BASE_DAMAGE = 6.0;
    public static final double MACE_ATTACK_SPEED = 4.0 - 3.4;
    /** Axe ATTACK_DAMAGE modifier of 7 plus the 1 of an empty hand, and its attack speed of 1.0. */
    public static final double AXE_BASE_DAMAGE = 8.0;
    /**
     * BlocksAttacks.blockDelaySeconds of the shield is 0.25f, so LivingEntity.getItemBlockingWith only hands
     * back the shield once it has been held for this many ticks.
     */
    public static final int SHIELD_RAISE_TICKS = 5;
    /**
     * An axe carries Weapon.AXE_DISABLES_BLOCKING_FOR_SECONDS and the shield scales it by
     * BlocksAttacks.disableCooldownScale of 1.0, so Player.blockUsingItem puts the shield on cooldown for this
     * long when it blocks an axe hit.
     */
    public static final int AXE_DISABLE_TICKS = 100;

    private SmashTiming()
    {
    }

    /** Mirrors MaceItem.canSmashAttack: more than 1.5 blocks of fall and not gliding. */
    public static boolean canSmash(DuelSim.Fighter f)
    {
        return CombatMath.canSmash(f.fallDistance, f.gliding);
    }

    /**
     * The raw damage Player.attack deals with a fully charged mace at this fall distance: the base damage is
     * charge scaled, MaceItem.getAttackDamageBonus adds the smash on top, a crit then multiplies the sum, and
     * the enchantment bonus is added last and scaled separately.
     */
    public static float smashDamage(double fallDistance, boolean critical, int densityLevel, float enchantBonus, float scale)
    {
        return CombatMath.attackDamage((float) MACE_BASE_DAMAGE,
                CombatMath.maceSmashBonus(fallDistance, false, densityLevel), enchantBonus, scale, critical);
    }

    /** Mirrors Player.canCriticalAttack: a falling fighter above the ground crits once the charge gate is passed. */
    public static boolean canCrit(DuelSim.Fighter f, boolean chargeGatePassed)
    {
        return CombatMath.isCritical(!f.onGround && f.vy < 0.0, f.onGround, false, false, false, false, true,
                f.sprinting, chargeGatePassed);
    }

    /** True when the fighter may swing at the target this tick: in reach and with a charge past the attack gate. */
    public static boolean maySwing(DuelSim.Fighter att, DuelSim.Fighter tgt)
    {
        return DuelSim.inReach(att, tgt) && att.ticksSinceSwing >= att.gateTicks;
    }

    /**
     * The damage the swing puts into the target's health, after the invulnerability window, its armour with
     * Breach and its protection.
     */
    public static float damageDealt(DuelSim.Fighter att, DuelSim.Fighter tgt, int breachLevel, double fallDistance)
    {
        float scale = CombatMath.chargeScale(att.ticksSinceSwing, att.attackSpeed);
        boolean smash = fallDistance > CombatMath.SMASH_FALL_THRESHOLD;
        float bonus = smash ? CombatMath.maceSmashBonus(fallDistance, att.gliding, att.densityLevel) : 0.0f;
        float raw = CombatMath.attackDamage((float) att.baseDamage, bonus, att.enchantBonus, scale,
                smash && canCrit(att, CombatMath.passesChargeGate(scale)));
        float effective = CombatMath.invulnerabilityDamage(raw, tgt.lastHurt, tgt.invulTime);
        return effective <= 0.0f ? 0.0f
                : CombatMath.damageAfterDefences(effective, tgt.armor, tgt.toughness, breachLevel, tgt.epf);
    }

    /** The two swings of a stun slam and whether the mace lands before the shield comes back. */
    public static final class StunSlam
    {
        public int axeHitTick = -1;
        public int maceHitTick = -1;
        public double maceFallDistance;
        public float maceDamage;
        public float axeDamage;
        /** True when the raised shield took the axe hit instead of letting it through. */
        public boolean shieldAbsorbedAxe;

        public boolean lands()
        {
            return stunSlamFits(axeHitTick, maceHitTick) && maceDamage > 0.0f;
        }
    }

    /**
     * True when the blocking item still stops the hit at hitTick. LivingEntity.getItemBlockingWith only hands
     * it back once it has been held for {@link #SHIELD_RAISE_TICKS} ticks, so a shield raised at raisedSince
     * does not cover a swing in the same few ticks. A negative raisedSince means it went up before the plan
     * started.
     */
    public static boolean shieldBlocks(boolean blocking, int raisedSince, int hitTick)
    {
        return blocking && hitTick >= raisedSince + SHIELD_RAISE_TICKS;
    }

    /**
     * Both hits of the stun slam fit when the mace lands inside the window the axe opened, that is strictly
     * between the axe hit and the tick the shield's cooldown runs out on. If the axe hit was not absorbed there
     * is no window at all, so only the ticks up to the axe hit count.
     */
    public static boolean stunSlamFits(int axeHitTick, int maceHitTick)
    {
        return axeHitTick >= 0 && maceHitTick > axeHitTick && maceHitTick < axeHitTick + AXE_DISABLE_TICKS;
    }

    /**
     * Walks the attacker at the target and swings the axe on the first charged tick in reach and the mace on the
     * first charged tick after that, then records the tick of each hit and the damage of the pair. When the
     * target's shield is already up, which shieldBlocks reports from the tick it came up on, it eats the axe hit:
     * no damage, but Player.blockUsingItem puts the shield on cooldown for {@link #AXE_DISABLE_TICKS} ticks, and
     * the mace lands inside that window. The mace carries Weapon(1), whose disableBlockingForSeconds is 0, so on
     * its own it never opens one.
     */
    public static StunSlam planStunSlam(DuelSim sim, int attacker, boolean blocking, int shieldRaisedTick,
                                        int maxTicks)
    {
        StunSlam plan = new StunSlam();
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        int axeTicksSinceSwing = att.ticksSinceSwing;
        int axeGateTicks = CombatMath.minTicksForGate(1.0);
        int maceTicksSinceSwing = att.ticksSinceSwing;
        int maceGateTicks = att.gateTicks;
        int walk = DuelSim.action(1, 0, false, true, false);
        for (int t = 0; t < maxTicks && plan.maceHitTick < 0; t++)
        {
            axeTicksSinceSwing++;
            maceTicksSinceSwing++;
            boolean swingAxe = plan.axeHitTick < 0 && axeTicksSinceSwing >= axeGateTicks && DuelSim.inReach(att, tgt);
            boolean swingMace = plan.axeHitTick >= 0 && maceTicksSinceSwing >= maceGateTicks && DuelSim.inReach(att, tgt);
            sim.step(walk, DuelSim.NOOP);
            if (swingAxe)
            {
                plan.axeHitTick = t;
                plan.shieldAbsorbedAxe = shieldBlocks(blocking, shieldRaisedTick, t);
                plan.axeDamage = plan.shieldAbsorbedAxe ? 0.0f
                        : CombatMath.damageAfterDefences(
                                CombatMath.attackDamage((float) AXE_BASE_DAMAGE, 0.0f, 0.0f, 1.0f, false),
                                tgt.armor, tgt.toughness, 0, tgt.epf);
                axeTicksSinceSwing = 0;
            }
            if (swingMace)
            {
                plan.maceHitTick = t;
                plan.maceFallDistance = att.fallDistance;
                plan.maceDamage = damageDealt(att, tgt, 0, att.fallDistance);
                break;
            }
        }
        return plan;
    }

    /** The two hits of a stun slam taken inside one fall: an axe on the way down, then the mace a tick or two later. */
    public static final class FallStunSlam
    {
        public int axeHitTick = -1;
        public int maceHitTick = -1;
        public double axeFallDistance;
        public double maceFallDistance;
        public float maceDamage;
        public float axeDamage;
        /** True when the raised shield took the axe hit instead of letting it through. */
        public boolean shieldAbsorbedAxe;

        /**
         * True when both hits came out of one fall. The counter only grows while a fighter is falling and is
         * zeroed by the landing tick, and a smash needs it past the threshold on both hits, so a mace hit that
         * carries at least as much fall as the axe hit could not have touched down in between.
         */
        public boolean oneFall()
        {
            return maceHitTick > axeHitTick && maceFallDistance > CombatMath.SMASH_FALL_THRESHOLD
                    && maceFallDistance >= axeFallDistance;
        }

        public boolean lands()
        {
            return stunSlamFits(axeHitTick, maceHitTick) && maceDamage > 0.0f;
        }
    }

    /**
     * The stun slam taken in a single fall: the axe opens the window on the way down and the mace lands a tick
     * or two later, which is as long as a player can wait for the hotbar change to reach its hand.
     *
     * <p>The mace hit is not charged, because the axe hit spent the cooldown, so it is not worth a crit. It is
     * still worth a great deal: {@code Player.attack} scales the base damage by the charge and adds the item's
     * fall bonus afterwards, so the bonus survives the spent cooldown in full and only the base damage is
     * scaled down. That is what this prices, and it is what the fall is for: the same two hits on the ground
     * would have nothing to add.</p>
     *
     * <p>The attacker walks towards the target and only swings inside its reach and while it can still smash,
     * so the plan starts from a state the caller has put into the air. {@code chargeDamage} and
     * {@code chargeSpeed} are the loadout of the item the cooldown was collected under, which is what the
     * swapped mace hit is charged at.</p>
     */
    public static FallStunSlam planFallStunSlam(DuelSim sim, int attacker, boolean blocking, int shieldRaisedTick,
            int densityLevel, double chargeDamage, double chargeSpeed, int chargeTicks, int gapTicks, int maxTicks)
    {
        FallStunSlam plan = new FallStunSlam();
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        int sinceSwing = chargeTicks;
        int axeGateTicks = CombatMath.minTicksForGate(chargeSpeed);
        int walk = DuelSim.action(1, 0, false, att.onGround, false);
        for (int t = 0; t < maxTicks && plan.maceHitTick < 0; t++)
        {
            sinceSwing++;
            boolean inFall = DuelSim.inReach(att, tgt) && canSmash(att);
            boolean swingAxe = plan.axeHitTick < 0 && inFall && sinceSwing >= axeGateTicks;
            boolean swingMace = plan.axeHitTick >= 0 && t - plan.axeHitTick >= gapTicks && inFall;
            // The attack is resolved at the head of the tick, before this tick's movement, so the fall the
            // mace is priced at is the one the fighter was carrying when it decided to swing.
            double fall = att.fallDistance;
            sim.step(walk, DuelSim.NOOP);
            if (swingAxe)
            {
                plan.axeHitTick = t;
                plan.axeFallDistance = fall;
                plan.shieldAbsorbedAxe = shieldBlocks(blocking, shieldRaisedTick, t);
                plan.axeDamage = plan.shieldAbsorbedAxe ? 0.0f
                        : CombatMath.damageAfterDefences(
                                CombatMath.attackDamage((float) chargeDamage, 0.0F, 0.0F,
                                        CombatMath.chargeScale(sinceSwing, chargeSpeed), false),
                                tgt.armor, tgt.toughness, 0, tgt.epf);
                sinceSwing = 0;
            }
            if (swingMace)
            {
                plan.maceHitTick = t;
                plan.maceFallDistance = fall;
                float scale = CombatMath.chargeScale(sinceSwing, chargeSpeed);
                boolean crit = CombatMath.isCritical(true, false, false, false, false, false, true, att.sprinting,
                        CombatMath.passesChargeGate(scale));
                plan.maceDamage = AttributeSwap.damage(chargeDamage, chargeSpeed, sinceSwing, crit,
                        fall, densityLevel, 0.0F, tgt.armor, tgt.toughness, 0, tgt.epf);
                break;
            }
        }
        return plan;
    }

    /**
     * The tick the first hit of a mace swing on a fighter that is falling onto the target has to land on, or
     * -1 when it cannot get there. Charges the mace fully, so the caller only has to walk the attacker's
     * flight forward until this returns a tick.
     */
    public static int smashTick(DuelSim sim, int attacker, int maxTicks)
    {
        DuelSim.Fighter att = sim.fighter(attacker);
        DuelSim.Fighter tgt = sim.fighter(1 - attacker);
        for (int t = 0; t < maxTicks; t++)
        {
            if (DuelSim.inReach(att, tgt) && canSmash(att))
            {
                return t;
            }
            sim.step(DuelSim.action(1, 0, false, att.onGround, false), DuelSim.NOOP);
        }
        return -1;
    }
}
