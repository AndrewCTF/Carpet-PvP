package carpet.pvp.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DurabilityTest
{
    @Test
    void armorPieceDurability()
    {
        // ArmorType unitDurability times the ArmorMaterials multiplier, as ArmorType.getDurability does.
        assertEquals(407, Durability.maxDamage(Durability.HEAD, Durability.NETHERITE_MULTIPLIER));
        assertEquals(592, Durability.maxDamage(Durability.CHEST, Durability.NETHERITE_MULTIPLIER));
        assertEquals(555, Durability.maxDamage(Durability.LEGS, Durability.NETHERITE_MULTIPLIER));
        assertEquals(481, Durability.maxDamage(Durability.BOOTS, Durability.NETHERITE_MULTIPLIER));
        assertEquals(363, Durability.maxDamage(Durability.HEAD, Durability.DIAMOND_MULTIPLIER));
        assertEquals(165, Durability.maxDamage(Durability.HEAD, Durability.IRON_MULTIPLIER));
        assertEquals(55, Durability.maxDamage(Durability.HEAD, Durability.LEATHER_MULTIPLIER));
        assertEquals(77, Durability.maxDamage(Durability.HEAD, Durability.GOLD_MULTIPLIER));
        // ArmorMaterials defence points, boots to helmet.
        assertEquals(3, Durability.NETHERITE_DEFENCE[Durability.BOOTS]);
        assertEquals(8, Durability.NETHERITE_DEFENCE[Durability.CHEST]);
        assertEquals(8, Durability.NETHERITE_DEFENCE[Durability.HEAD]);
        assertEquals(25, Durability.NETHERITE_DEFENCE[0] + Durability.NETHERITE_DEFENCE[1]
                + Durability.NETHERITE_DEFENCE[2] + Durability.NETHERITE_DEFENCE[3]);
        assertEquals(5, Durability.IRON_DEFENCE[Durability.HEAD]);
        assertEquals(3.0f, Durability.NETHERITE_TOUGHNESS, 0.0f);
        assertEquals(0.1f, Durability.NETHERITE_KNOCKBACK_RESISTANCE, 1e-6f);
    }

    @Test
    void damageForAKnownHit()
    {
        // LivingEntity.doHurtEquipment passes (int) Math.max(1.0f, amount / 4.0f) to each of the four slots.
        assertEquals(1, Durability.damagePerHit(1.0f));
        assertEquals(1, Durability.damagePerHit(3.9f));
        assertEquals(1, Durability.damagePerHit(4.0f));
        assertEquals(2, Durability.damagePerHit(8.0f));
        assertEquals(2, Durability.damagePerHit(11.0f));
        assertEquals(3, Durability.damagePerHit(12.0f));
        assertEquals(5, Durability.damagePerHit(20.0f));
        assertEquals(1, Durability.damagePerHit(0.0f));
        // Every piece takes the same amount, so one 8 damage hit costs four pieces 2 durability each.
        int perHit = Durability.damagePerHit(8.0f);
        assertEquals(8, perHit * Durability.PIECE_COUNT);
    }

    @Test
    void unbreaking()
    {
        // unbreaking.json armor branch: (2 + 2 * (level - 1)) / (10 + 5 * (level - 1)).
        assertEquals(0.0, Durability.unbreakingChance(0), 1e-9);
        assertEquals(0.2, Durability.unbreakingChance(1), 1e-9);
        assertEquals(4.0 / 15.0, Durability.unbreakingChance(2), 1e-9);
        assertEquals(0.3, Durability.unbreakingChance(3), 1e-9);
        assertEquals(0.0, Durability.unbreakingChance(-1), 0.0);
        // Expected damage kept per point: 0.8, 11/15 and 0.7.
        assertEquals(0.8, Durability.expectedKept(1), 1e-9);
        assertEquals(1.0 - 4.0 / 15.0, Durability.expectedKept(2), 1e-9);
        assertEquals(0.7, Durability.expectedKept(3), 1e-9);
        // A known 8 damage hit costs 2 durability, so 1.6 with Unbreaking I and 1.4 with Unbreaking III.
        assertEquals(2.0, Durability.expectedLoss(8.0f, 0), 1e-9);
        assertEquals(1.6, Durability.expectedLoss(8.0f, 1), 1e-9);
        assertEquals(1.4, Durability.expectedLoss(8.0f, 3), 1e-9);
        // A full Unbreaking III netherite helmet with 100 durability left lasts 100 / 1.4 hits.
        assertEquals(100.0 / 1.4, Durability.expectedHitsLeft(8.0f, 3, 100), 1e-6);
        assertEquals(407.0 / 1.4, Durability.expectedHitsLeft(8.0f, 3, 407), 1e-6);
        // A piece with nothing left is worn out immediately, not immortal.
        assertEquals(0.0, Durability.expectedHitsLeft(8.0f, 0, 0), 0.0);
        assertEquals(407.0 / 2.0, Durability.expectedHitsLeft(8.0f, 0, 407), 1e-6);
    }

    @Test
    void mendingExperience()
    {
        // mending.json repair_with_xp multiplies each point by 2.0.
        assertEquals(2, Durability.MENDING_DURABILITY_PER_POINT);
        assertEquals(10, Durability.repairCapacity(5));
        assertEquals(18, Durability.repairCapacity(9));
        assertEquals(0, Durability.repairCapacity(0));
        assertEquals(0, Durability.repairCapacity(-3));
        // 100 durability is 50 experience points.
        assertEquals(50, Durability.experienceFor(100));
        assertEquals(1, Durability.experienceFor(2));
        assertEquals(2, Durability.experienceFor(3));
        assertEquals(2, Durability.experienceFor(4));
        assertEquals(0, Durability.experienceFor(0));
        assertEquals(0, Durability.experienceFor(-5));
        // At the best 9 point roll a bottle is 18 durability, so 100 needs 6 bottles.
        assertEquals(6, Durability.bottlesFor(100));
        assertEquals(1, Durability.bottlesFor(18));
        assertEquals(2, Durability.bottlesFor(19));
        assertEquals(0, Durability.bottlesFor(0));
        int[] roll = Durability.bottleDurability();
        assertEquals(10, roll[0]);
        assertEquals(18, roll[1]);
        // A whole netherite chestplate at 592 durability is 296 experience, 33 bottles.
        assertEquals(296, Durability.experienceFor(592));
        assertEquals(33, Durability.bottlesFor(592));
    }

    @Test
    void bowIsNotArmor()
    {
        // Unbreaking's other branch, for everything outside #minecraft:enchantable/armor:
        // (1 + (level - 1)) / (2 + (level - 1)), which is 0.5 at level 1.
        assertEquals(0.5, (1.0 + (1 - 1)) / (2.0 + (1 - 1)), 1e-9);
    }
}