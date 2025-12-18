package carpet.script.utils;

import carpet.script.exception.InternalExpressionException;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for validating equipment-related parameters in Scarpet functions.
 * Provides comprehensive validation for slot numbers, slot names, and item names
 * with descriptive error messages.
 */
public class EquipmentValidator {
    
    // Mapping from slot numbers to EquipmentSlot (matches EquipmentInventory)
    private static final Map<Integer, EquipmentSlot> SLOT_NUMBER_MAP = Map.of(
        0, EquipmentSlot.MAINHAND,
        1, EquipmentSlot.FEET,
        2, EquipmentSlot.LEGS,
        3, EquipmentSlot.CHEST,
        4, EquipmentSlot.HEAD,
        5, EquipmentSlot.OFFHAND
    );
    
    // Mapping from string names to EquipmentSlot
    private static final Map<String, EquipmentSlot> SLOT_NAME_MAP = new HashMap<>();
    static {
        // Primary names
        SLOT_NAME_MAP.put("head", EquipmentSlot.HEAD);
        SLOT_NAME_MAP.put("chest", EquipmentSlot.CHEST);
        SLOT_NAME_MAP.put("legs", EquipmentSlot.LEGS);
        SLOT_NAME_MAP.put("feet", EquipmentSlot.FEET);
        SLOT_NAME_MAP.put("mainhand", EquipmentSlot.MAINHAND);
        SLOT_NAME_MAP.put("offhand", EquipmentSlot.OFFHAND);
        
        // Alternative names
        SLOT_NAME_MAP.put("helmet", EquipmentSlot.HEAD);
        SLOT_NAME_MAP.put("chestplate", EquipmentSlot.CHEST);
        SLOT_NAME_MAP.put("leggings", EquipmentSlot.LEGS);
        SLOT_NAME_MAP.put("boots", EquipmentSlot.FEET);
        SLOT_NAME_MAP.put("weapon", EquipmentSlot.MAINHAND);
        SLOT_NAME_MAP.put("shield", EquipmentSlot.OFFHAND);
    }
    
    /**
     * Validates and converts a numeric slot parameter to EquipmentSlot.
     * 
     * @param slotNumber The slot number to validate (should be 0-5)
     * @return The corresponding EquipmentSlot
     * @throws InternalExpressionException if slot number is invalid
     */
    public static EquipmentSlot validateSlotNumber(int slotNumber) {
        if (!SLOT_NUMBER_MAP.containsKey(slotNumber)) {
            throw new InternalExpressionException(
                String.format("Invalid equipment slot number: %d. Equipment slots must be between 0 and 5. " +
                    "Valid slots: 0=mainhand, 1=feet, 2=legs, 3=chest, 4=head, 5=offhand", slotNumber)
            );
        }
        return SLOT_NUMBER_MAP.get(slotNumber);
    }
    
    /**
     * Validates and converts a string slot name to EquipmentSlot.
     * 
     * @param slotName The slot name to validate
     * @return The corresponding EquipmentSlot
     * @throws InternalExpressionException if slot name is invalid
     */
    public static EquipmentSlot validateSlotName(String slotName) {
        if (slotName == null || slotName.trim().isEmpty()) {
            throw new InternalExpressionException("Equipment slot name cannot be empty");
        }
        
        String normalizedName = slotName.toLowerCase().trim();
        if (!SLOT_NAME_MAP.containsKey(normalizedName)) {
            throw new InternalExpressionException(
                String.format("Invalid equipment slot name: '%s'. Valid slot names are: %s", 
                    slotName, String.join(", ", SLOT_NAME_MAP.keySet()))
            );
        }
        return SLOT_NAME_MAP.get(normalizedName);
    }
    
    /**
     * Validates that an item name exists in the registry.
     * 
     * @param itemName The item name to validate
     * @param carpetContext The carpet context for registry access
     * @throws InternalExpressionException if item name is invalid
     */
    public static void validateItemName(String itemName, carpet.script.CarpetContext carpetContext) {
        if (itemName == null || itemName.trim().isEmpty()) {
            throw new InternalExpressionException(
                "Item name cannot be empty. Please provide a valid item name like 'diamond_helmet' or 'minecraft:diamond_helmet'."
            );
        }
        
        String trimmedName = itemName.trim();
        
        try {
            Identifier itemId = Identifier.parse(trimmedName);
            Registry<Item> itemRegistry = carpetContext.registry(Registries.ITEM);
            
            if (!itemRegistry.containsKey(itemId)) {
                // Provide helpful suggestions for common mistakes
                String suggestions = getItemNameSuggestions(trimmedName, itemRegistry);
                throw new InternalExpressionException(
                    String.format("Unknown item: '%s'. Please check the item name and ensure it exists in the game.%s", 
                        trimmedName, suggestions)
                );
            }
        } catch (IllegalArgumentException e) {
            // Handle ResourceLocation parsing errors
            throw new InternalExpressionException(
                String.format("Invalid item name format: '%s'. Item names should be in format 'namespace:item' or just 'item' for minecraft items. " +
                    "Examples: 'diamond_helmet', 'minecraft:diamond_helmet', 'iron_chestplate'", trimmedName)
            );
        } catch (InternalExpressionException e) {
            // Re-throw our own exceptions
            throw e;
        } catch (Exception e) {
            throw new InternalExpressionException(
                String.format("Error validating item name '%s': %s", trimmedName, e.getMessage())
            );
        }
    }
    
    /**
     * Provides helpful suggestions for invalid item names.
     * 
     * @param invalidName The invalid item name
     * @param itemRegistry The item registry to search for similar names
     * @return A string with suggestions, or empty string if no suggestions found
     */
    private static String getItemNameSuggestions(String invalidName, Registry<Item> itemRegistry) {
        // Common armor item suggestions
        if (invalidName.toLowerCase().contains("helmet") || invalidName.toLowerCase().contains("head")) {
            return " Did you mean: diamond_helmet, iron_helmet, leather_helmet, golden_helmet, netherite_helmet, chainmail_helmet?";
        }
        if (invalidName.toLowerCase().contains("chestplate") || invalidName.toLowerCase().contains("chest")) {
            return " Did you mean: diamond_chestplate, iron_chestplate, leather_chestplate, golden_chestplate, netherite_chestplate, chainmail_chestplate?";
        }
        if (invalidName.toLowerCase().contains("leggings") || invalidName.toLowerCase().contains("legs")) {
            return " Did you mean: diamond_leggings, iron_leggings, leather_leggings, golden_leggings, netherite_leggings, chainmail_leggings?";
        }
        if (invalidName.toLowerCase().contains("boots") || invalidName.toLowerCase().contains("feet")) {
            return " Did you mean: diamond_boots, iron_boots, leather_boots, golden_boots, netherite_boots, chainmail_boots?";
        }
        
        // Check for common namespace mistakes
        if (!invalidName.contains(":") && !invalidName.startsWith("minecraft:")) {
            return " Try adding 'minecraft:' prefix: 'minecraft:" + invalidName + "'";
        }
        
        return "";
    }
    
    /**
     * Gets the slot number for a given EquipmentSlot (for reverse lookup).
     * 
     * @param slot The EquipmentSlot
     * @return The corresponding slot number
     */
    public static int getSlotNumber(EquipmentSlot slot) {
        for (Map.Entry<Integer, EquipmentSlot> entry : SLOT_NUMBER_MAP.entrySet()) {
            if (entry.getValue() == slot) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("Unknown equipment slot: " + slot);
    }
    
    /**
     * Gets all valid slot names as a formatted string for error messages.
     * 
     * @return Formatted string of valid slot names
     */
    public static String getValidSlotNamesString() {
        return String.join(", ", SLOT_NAME_MAP.keySet());
    }
    
    /**
     * Gets all valid slot numbers as a formatted string for error messages.
     * 
     * @return Formatted string of valid slot numbers with descriptions
     */
    public static String getValidSlotNumbersString() {
        return "0=mainhand, 1=feet, 2=legs, 3=chest, 4=head, 5=offhand";
    }
    
    /**
     * Validates all parameters for equipment operations and provides comprehensive error messages.
     * 
     * @param slotParam The slot parameter (can be numeric or string)
     * @param itemName The item name (can be null for slot-only operations)
     * @param count The item count (can be null)
     * @param carpetContext The carpet context for registry access
     * @return A ValidationResult containing the validated slot and any warnings
     * @throws InternalExpressionException if any parameter is invalid
     */
    public static ValidationResult validateEquipmentParameters(Object slotParam, String itemName, Integer count, carpet.script.CarpetContext carpetContext) {
        ValidationResult result = new ValidationResult();
        
        // Validate slot parameter
        if (slotParam == null) {
            throw new InternalExpressionException(
                "Equipment slot cannot be null. Please provide a slot number (0-5) or slot name (head, chest, legs, feet, mainhand, offhand)."
            );
        }
        
        if (slotParam instanceof Number) {
            int slotNumber = ((Number) slotParam).intValue();
            result.slot = validateSlotNumber(slotNumber);
        } else {
            String slotName = slotParam.toString();
            result.slot = validateSlotName(slotName);
        }
        
        // Validate item name if provided
        if (itemName != null && !itemName.trim().isEmpty()) {
            validateItemName(itemName, carpetContext);
        }
        
        // Validate count if provided
        if (count != null) {
            if (count < 0) {
                throw new InternalExpressionException(
                    String.format("Item count cannot be negative: %d. Please provide a count of 0 or greater.", count)
                );
            }
            if (count > 64) {
                result.warnings.add(String.format("Item count %d exceeds normal stack size (64). This may cause unexpected behavior.", count));
            }
        }
        
        return result;
    }
    
    /**
     * Result class for comprehensive parameter validation.
     */
    public static class ValidationResult {
        public EquipmentSlot slot;
        public java.util.List<String> warnings = new java.util.ArrayList<>();
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public String getWarningsString() {
            return String.join("; ", warnings);
        }
    }
}