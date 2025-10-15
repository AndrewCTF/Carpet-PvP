package carpet.pvp;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Utility class for determining when critical hit conditions are met
 * Implements vanilla Minecraft critical hit mechanics for fake players
 */
public class CriticalHitDetector {
    
    /**
     * Checks if a fake player can perform a critical hit based on vanilla mechanics
     * @param player The fake player attempting the critical hit
     * @return true if critical hit conditions are met
     */
    public static boolean checkCriticalHitConditions(EntityPlayerMPFake player) {
        // Check if critical hits are globally disabled
        if (!CarpetSettings.fakePlayers_allowCriticalHits) {
            return false;
        }
        
        CriticalHitState critState = CriticalHitManager.getCriticalHitState(player);
        if (critState == null || !critState.isEnabled()) {
            return false;
        }
        
        // Always crit mode overrides all other conditions
        if (critState.isAlwaysCrit()) {
            return true;
        }
        
        // Check for guaranteed next attack critical
        if (critState.isNextAttackCritical()) {
            return true;
        }
        
        // Check vanilla critical hit conditions
        return isPlayerInValidState(player) && hasRequiredFallDistance(player);
    }
    
    /**
     * Validates that the target entity can receive critical hits
     * @param target The entity being attacked
     * @return true if the target is valid for critical hits
     */
    public static boolean isValidCriticalHitTarget(Entity target) {
        if (target == null) {
            return false;
        }
        
        // Critical hits only work on living entities
        if (!(target instanceof LivingEntity)) {
            return false;
        }
        
        // Don't allow critical hits on other players in creative mode
        if (target instanceof Player player && player.isCreative()) {
            return false;
        }
        
        // Target must be alive
        return target.isAlive();
    }
    
    /**
     * Checks if the fake player is in a valid state for critical hits
     * Based on vanilla Minecraft critical hit mechanics
     * @param player The fake player to check
     * @return true if player state allows critical hits
     */
    public static boolean isPlayerInValidState(EntityPlayerMPFake player) {
        // Player must not be on ground (falling or jumping)
        if (player.onGround()) {
            return false;
        }
        
        // Player must not be in water
        if (player.isInWater()) {
            return false;
        }
        
        // Player must not be in lava
        if (player.isInLava()) {
            return false;
        }
        
        // Player must not be riding an entity
        if (player.isPassenger()) {
            return false;
        }
        
        // Player must not be climbing (on ladder/vine)
        if (player.onClimbable()) {
            return false;
        }
        
        // Note: Blindness effect check omitted for simplicity
        // In vanilla, blindness prevents critical hits but this is rarely relevant for fake players
        
        // Player must not be in creative mode (though fake players shouldn't be)
        if (player.isCreative()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Checks if the player has sufficient fall distance for a critical hit
     * @param player The fake player to check
     * @return true if fall distance requirements are met
     */
    public static boolean hasRequiredFallDistance(EntityPlayerMPFake player) {
        CriticalHitState critState = CriticalHitManager.getCriticalHitState(player);
        if (critState == null) {
            return false;
        }
        
        // In vanilla, any fall distance > 0 while not on ground allows critical hits
        // We use a small threshold to account for floating point precision
        return critState.getFallDistance() > 0.01;
    }
    
    /**
     * Checks if the player is currently falling (has downward velocity)
     * @param player The fake player to check
     * @return true if player is falling
     */
    public static boolean isPlayerFalling(EntityPlayerMPFake player) {
        return player.getDeltaMovement().y < 0.0;
    }
    
    /**
     * Comprehensive check for all critical hit conditions
     * Combines state validation, target validation, and fall distance checks
     * @param player The fake player attempting the critical hit
     * @param target The target entity
     * @return true if all conditions are met for a critical hit
     */
    public static boolean canPerformCriticalHit(EntityPlayerMPFake player, Entity target) {
        return checkCriticalHitConditions(player) && isValidCriticalHitTarget(target);
    }
    
    /**
     * Gets the effective critical hit damage multiplier for a player
     * @param player The fake player
     * @return The damage multiplier to apply for critical hits
     */
    public static float getCriticalHitMultiplier(EntityPlayerMPFake player) {
        CriticalHitState critState = CriticalHitManager.getCriticalHitState(player);
        if (critState != null) {
            return critState.getDamageMultiplier();
        }
        return CarpetSettings.fakePlayers_criticalHitMultiplier;
    }
}