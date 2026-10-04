package carpet.pvp.sim;

/**
 * Armor wear, Unbreaking and Mending arithmetic. The durability numbers come from ArmorType,
 * ArmorMaterials and the two enchantment definitions in the 26.3 jar.
 */
public final class Durability
{
    /** Piece order, the array index Player.hurtArmor passes to doHurtEquipment. */
    public static final int BOOTS = 0;
    public static final int LEGS = 1;
    public static final int CHEST = 2;
    public static final int HEAD = 3;
    public static final int PIECE_COUNT = 4;

    /** ArmorType unitDurability, indexed by piece. */
    private static final int[] UNIT_DURABILITY = {13, 15, 16, 11};

    /** ArmorMaterials durability multipliers. */
    public static final int LEATHER_MULTIPLIER = 5;
    public static final int CHAINMAIL_MULTIPLIER = 15;
    public static final int IRON_MULTIPLIER = 15;
    public static final int GOLD_MULTIPLIER = 7;
    public static final int DIAMOND_MULTIPLIER = 33;
    public static final int NETHERITE_MULTIPLIER = 37;

    /** ArmorMaterials.NETHERITE defence points per piece, indexed like the pieces. */
    public static final int[] NETHERITE_DEFENCE = {3, 6, 8, 8};
    /** ArmorMaterials.DIAMOND defence points per piece. */
    public static final int[] DIAMOND_DEFENCE = {3, 6, 8, 8};
    /** ArmorMaterials.IRON defence points per piece. */
    public static final int[] IRON_DEFENCE = {2, 5, 6, 5};
    /** ArmorMaterials.CHAINMAIL defence points per piece. */
    public static final int[] CHAINMAIL_DEFENCE = {2, 5, 6, 4};
    /** ArmorMaterials.LEATHER defence points per piece. */
    public static final int[] LEATHER_DEFENCE = {1, 3, 5, 2};

    /** ArmorMaterials.NETHERITE toughness. */
    public static final float NETHERITE_TOUGHNESS = 3.0f;
    /** ArmorMaterials.NETHERITE knockback resistance. */
    public static final float NETHERITE_KNOCKBACK_RESISTANCE = 0.1f;

    /**
     * The durability mending.json's {@code repair_with_xp} multiply effect hands out per point;
     * EnchantmentHelper.modifyDurabilityToRepairFromXp returns max(0, (int) (xp * 2)).
     */
    public static final int MENDING_DURABILITY_PER_POINT = 2;

    private Durability()
    {
    }

    /** Max durability of a piece: ArmorType.getDurability is unitDurability * material multiplier. */
    public static int maxDamage(int piece, int materialMultiplier)
    {
        return UNIT_DURABILITY[piece] * materialMultiplier;
    }

    /**
     * Durability every damaged piece loses from one hit: LivingEntity.doHurtEquipment passes
     * {@code (int) Math.max(1.0f, amount / 4.0f)} to ItemStack.hurtAndBreak for each of the four slots.
     *
     * @param amount the damage entering getDamageAfterArmorAbsorb, before armour, Resistance and protection
     */
    public static int damagePerHit(float amount)
    {
        return Math.max(1, (int) (amount / 4.0f));
    }

    /**
     * Unbreaking's armor branch removes one durability point per hit with probability
     * {@code (2 + 2 * (level - 1)) / (10 + 5 * (level - 1))}, the remove_binomial chance in
     * unbreaking.json. RemoveBinomial.process rolls each point independently below 128 points, so this
     * is also the expected damage the chance applies.
     */
    public static double unbreakingChance(int level)
    {
        if (level <= 0)
        {
            return 0.0;
        }
        return (2.0 + 2.0 * (level - 1)) / (10.0 + 5.0 * (level - 1));
    }

    /** Expected durability kept per point after Unbreaking. */
    public static double expectedKept(int level)
    {
        return 1.0 - unbreakingChance(level);
    }

    /** Expected durability lost from one hit, after Unbreaking. */
    public static double expectedLoss(float amount, int unbreakingLevel)
    {
        return damagePerHit(amount) * expectedKept(unbreakingLevel);
    }

    /** Expected hits left before a piece breaks, or infinity when it never wears out. */
    public static double expectedHitsLeft(float amount, int unbreakingLevel, int remainingDurability)
    {
        double loss = expectedLoss(amount, unbreakingLevel);
        return loss <= 0.0 ? Double.POSITIVE_INFINITY : remainingDurability / loss;
    }

    /** Durability an experience orb can restore: EnchantmentHelper.modifyDurabilityToRepairFromXp. */
    public static int repairCapacity(int experiencePoints)
    {
        return Math.max(0, experiencePoints * MENDING_DURABILITY_PER_POINT);
    }

    /** Experience points needed to restore the given durability, rounding up. */
    public static int experienceFor(int durability)
    {
        if (durability <= 0)
        {
            return 0;
        }
        return (durability + MENDING_DURABILITY_PER_POINT - 1) / MENDING_DURABILITY_PER_POINT;
    }

    /**
     * Experience bottles needed to restore the given durability, using the best roll of
     * ThrownExperienceBottle.onHit so the bot does not throw a bottle it cannot afford to waste.
     */
    public static int bottlesFor(int durability)
    {
        return (experienceFor(durability) + Effects.EXPERIENCE_BOTTLE_MAX_XP - 1) / Effects.EXPERIENCE_BOTTLE_MAX_XP;
    }

    /** Durability a thrown bottle restores at its best and worst roll, before the mending cap. */
    public static int[] bottleDurability()
    {
        return new int[] {
            repairCapacity(Effects.EXPERIENCE_BOTTLE_MIN_XP),
            repairCapacity(Effects.EXPERIENCE_BOTTLE_MAX_XP)
        };
    }
}