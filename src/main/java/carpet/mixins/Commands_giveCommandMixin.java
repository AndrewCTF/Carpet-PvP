package carpet.mixins;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.ArmorEquipmentHelper;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(Commands.class)
public class Commands_giveCommandMixin {
    
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void interceptGiveCommand(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        try {
            // Check if this is a give command
            if (!command.trim().toLowerCase().startsWith("give ")) {
                return;
            }
            
            CommandContext<CommandSourceStack> context = parseResults.getContext().build(command);
            
            // Try to extract the target players and item from the command
            Collection<ServerPlayer> targets = null;
            ItemInput itemInput = null;
            
            try {
                targets = EntityArgument.getPlayers(context, "targets");
                itemInput = ItemArgument.getItem(context, "item");
            } catch (Exception e) {
                // Command parsing failed, let it proceed normally
                return;
            }
            
            if (targets == null || itemInput == null) {
                return;
            }
            
            // Check if any target is a fake player and the item is armor
            for (ServerPlayer target : targets) {
                if (target instanceof EntityPlayerMPFake fakePlayer) {
                    ItemStack itemStack = itemInput.createItemStack(1);
                    
                    if (ArmorEquipmentHelper.isArmorItem(itemStack)) {
                        // This is armor being given to a fake player - handle auto-equipment
                        handleArmorAutoEquipment(fakePlayer, itemStack, parseResults.getContext().getSource());
                    }
                }
            }
            
        } catch (Exception e) {
            // If anything goes wrong, let the command proceed normally
            // This ensures we don't break existing functionality
        }
    }
    
    /**
     * Handles automatic equipment of armor items for fake players
     */
    private static void handleArmorAutoEquipment(EntityPlayerMPFake fakePlayer, ItemStack armorItem, CommandSourceStack source) {
        EquipmentSlot slot = ArmorEquipmentHelper.getArmorSlot(armorItem);
        if (slot == null) {
            return;
        }
        
        ItemStack currentArmor = fakePlayer.getItemBySlot(slot);
        
        // Check if we should equip this armor
        boolean shouldEquip = false;
        String equipmentAction = "";
        
        if (currentArmor.isEmpty()) {
            // Slot is empty, equip the armor
            shouldEquip = true;
            equipmentAction = "equipped";
        } else if (ArmorEquipmentHelper.isBetterArmor(currentArmor, armorItem)) {
            // New armor is better, replace the current armor
            shouldEquip = true;
            equipmentAction = "upgraded";
            
            // Drop the old armor
            fakePlayer.drop(currentArmor, false);
        }
        
        if (shouldEquip) {
            // Equip the armor
            fakePlayer.setItemSlot(slot, armorItem.copy());
            
            // Send feedback to the command sender
            String slotName = getSlotDisplayName(slot);
            String material = ArmorEquipmentHelper.getArmorMaterial(armorItem);
            
            Component feedback = Component.literal(String.format(
                "Auto-%s %s %s on fake player %s", 
                equipmentAction, 
                material, 
                slotName, 
                fakePlayer.getName().getString()
            ));
            
            source.sendSuccess(() -> feedback, true);
        }
    }
    
    /**
     * Gets a user-friendly display name for an equipment slot
     */
    private static String getSlotDisplayName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case FEET -> "boots";
            case MAINHAND -> "weapon";
            case OFFHAND -> "shield";
            default -> "equipment";
        };
    }
}