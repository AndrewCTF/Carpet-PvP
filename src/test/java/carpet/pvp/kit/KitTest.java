package carpet.pvp.kit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitTest
{
    @Test
    void namesHoldLettersDigitsUnderscoreAndDash()
    {
        for (String name : List.of("sword", "netherite-pot", "my_kit2", "A", "9"))
        {
            assertTrue(Kit.isValidName(name), name);
        }
        for (String name : List.of("", " ", "my kit", "kit.json", "../escape", "é", "a/b", "a\\b", "kit!"))
        {
            assertFalse(Kit.isValidName(name), name);
        }
        assertFalse(Kit.isValidName(null));
        assertFalse(Kit.isValidName("a".repeat(65)));
    }

    @Test
    void aNameIsReadFromTheJsonAndDefaultsToTheFileName()
    {
        assertEquals("named", Kit.fromJson("{\"name\": \"named\", \"items\": []}", "fallback").name());
        assertEquals("fallback", Kit.fromJson("{\"items\": []}", "fallback").name());
    }

    @Test
    void entriesKeepTheirCountSlotEnchantmentsAndPotion()
    {
        Kit kit = Kit.fromJson("""
                {
                  "name": "smp",
                  "items": [
                    { "item": "minecraft:netherite_sword", "slot": 0,
                      "enchantments": [ { "id": "minecraft:sharpness", "level": 5 } ] },
                    { "item": "minecraft:golden_apple", "count": 5, "slot": 1 },
                    { "item": "minecraft:splash_potion", "slot": 4, "potion": "minecraft:strong_healing" },
                    { "item": "minecraft:shield", "slot": "offhand" },
                    { "item": "minecraft:cobweb" }
                  ]
                }
                """, "smp");

        assertEquals("smp", kit.name());
        assertEquals(5, kit.entries().size());

        KitEntry sword = kit.entries().get(0);
        assertEquals("minecraft:netherite_sword", sword.item());
        assertEquals(1, sword.count());
        assertEquals(0, sword.slot().index());
        assertFalse(sword.slot().isEquipment());
        assertEquals(List.of(new KitEnchantment("minecraft:sharpness", 5)), sword.enchantments());
        assertEquals(null, sword.potion());

        KitEntry apples = kit.entries().get(1);
        assertEquals(5, apples.count());
        assertEquals(1, apples.slot().index());

        assertEquals("minecraft:strong_healing", kit.entries().get(2).potion());

        KitEntry shield = kit.entries().get(3);
        assertTrue(shield.slot().isEquipment());
        assertEquals("offhand", shield.slot().equipment().getName());

        KitEntry last = kit.entries().get(4);
        assertTrue(last.slot().isAutomatic());
    }

    @Test
    void badJsonIsRejectedWithAReason()
    {
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("[]", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [1]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [{}]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [{\"item\": \"a\", \"count\": 0}]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [{\"item\": \"a\", \"slot\": \"belt\"}]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [{\"item\": \"a\", \"slot\": 36}]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"items\": [{\"item\": \"a\", \"enchantments\": [{\"id\": \"b\", \"level\": 0}]}]}", "x"));
        assertThrows(IllegalArgumentException.class, () -> Kit.fromJson("{\"name\": \"bad name\", \"items\": []}", "x"));
    }

    @Test
    void slotsAreIndicesOrArmourPositions()
    {
        assertEquals(8, KitSlot.ofIndex(8).index());
        assertEquals("head", KitSlot.of("head").equipment().getName());
        assertEquals("chest", KitSlot.of("chest").equipment().getName());
        assertEquals("legs", KitSlot.of("legs").equipment().getName());
        assertEquals("feet", KitSlot.of("feet").equipment().getName());
        assertEquals("offhand", KitSlot.of("offhand").equipment().getName());
        assertThrows(IllegalArgumentException.class, () -> KitSlot.of("mainhand"));
        assertThrows(IllegalArgumentException.class, () -> KitSlot.ofIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> KitSlot.ofIndex(36));
    }

    /**
     * A kit file in the world's folder written by hand describes its items, the same way the ones in the
     * mod do, rather than carrying finished stacks. Only the saved shape needs the registries.
     */
    @Test
    void aHandWrittenKitInTheWorldFolderIsReadAsItemDescriptions()
    {
        Kit kit = KitStore.readCustomKit(null, """
                {
                  "name": "mine",
                  "items": [
                    { "item": "minecraft:netherite_sword", "slot": 0,
                      "enchantments": [ { "id": "minecraft:sharpness", "level": 3 } ] },
                    { "item": "minecraft:cooked_beef", "count": 7, "slot": 1 },
                    { "item": "minecraft:shield", "slot": "offhand" }
                  ]
                }
                """, "mine");

        assertEquals("mine", kit.name());
        assertEquals(3, kit.entries().size());

        KitEntry sword = kit.entries().get(0);
        assertEquals("minecraft:netherite_sword", sword.item());
        assertEquals(0, sword.slot().index());
        assertEquals(List.of(new KitEnchantment("minecraft:sharpness", 3)), sword.enchantments());

        assertEquals(7, kit.entries().get(1).count());
        assertEquals("offhand", kit.entries().get(2).slot().equipment().getName());
    }

    @Test
    void aWorldFolderKitTakesItsNameFromTheFileWhenItHasNone()
    {
        Kit kit = KitStore.readCustomKit(null, "{\"items\": [{\"item\": \"minecraft:stone\"}]}", "from_file");
        assertEquals("from_file", kit.name());
    }
}