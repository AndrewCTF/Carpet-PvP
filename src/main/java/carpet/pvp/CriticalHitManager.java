package carpet.pvp;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for critical hit functionality in fake players
 * Handles state management, critical hit detection, and execution
 */
public class CriticalHitManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(CriticalHitManager.class);
    
    // Thread-safe storage for critical hit states
    private static final Map<String, CriticalHitState> criticalHitStates = new ConcurrentHashMap<>();
    
    /**
     * Gets or creates the critical hit state for a fake player
     * @param player The fake player
     * @return The critical hit state for the player
     */
    public static CriticalHitState getCriticalHitState(EntityPlayerMPFake player) {
        if (player == null) {
            return null;
        }
        
        String playerName = player.getName().getString();
        return criticalHitStates.computeIfAbsent(playerName, k -> {
            CriticalHitState state = new CriticalHitState();
            LOGGER.debug("Created new critical hit state for fake player: {}", playerName);
            return state;
        });
    }
    
    /**
     * Removes critical hit state for a player (cleanup when player is removed)
     * @param playerName The name of the player to clean up
     */
    public static void removeCriticalHitState(String playerName) {
        CriticalHitState removed = criticalHitStates.remove(playerName);
        if (removed != null) {
            LOGGER.debug("Removed critical hit state for fake player: {}", playerName);
        }
    }
    
    /**
     * Checks if a fake player can perform a critical hit
     * @param player The fake player
     * @return true if critical hit conditions are met
     */
    public static boolean canPerformCriticalHit(EntityPlayerMPFake player) {
        return CriticalHitDetector.checkCriticalHitConditions(player);
    }
    
    /**
     * Performs a critical hit attack with damage calculation and effects
     * @param player The fake player performing the attack
     * @param target The target entity
     * @param baseDamage The base damage before critical hit multiplier
     * @return The final damage after critical hit multiplier
     */
    public static float performCriticalHit(EntityPlayerMPFake player, Entity target, float baseDamage) {
        if (!CriticalHitDetector.canPerformCriticalHit(player, target)) {
            return baseDamage; // Not a critical hit
        }
        
        CriticalHitState critState = getCriticalHitState(player);
        if (critState == null) {
            return baseDamage;
        }
        
        // Calculate critical hit damage
        float multiplier = CriticalHitDetector.getCriticalHitMultiplier(player);
        float criticalDamage = baseDamage * multiplier;
        
        // Consume next attack critical flag if it was set
        critState.consumeNextAttackCritical();
        
        // Play effects
        playCriticalHitEffects(player, target, critState);
        
        LOGGER.debug("Critical hit performed by {} on {} - damage: {:.2f} -> {:.2f} ({}x multiplier)", 
                player.getName().getString(), 
                target.getName().getString(), 
                baseDamage, 
                criticalDamage, 
                multiplier);
        
        return criticalDamage;
    }
    
    /**
     * Enables or disables critical hits for a fake player
     * @param player The fake player
     * @param enabled Whether critical hits should be enabled
     */
    public static void enableCriticalHits(EntityPlayerMPFake player, boolean enabled) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.setEnabled(enabled);
            LOGGER.debug("Critical hits {} for fake player: {}", 
                    enabled ? "enabled" : "disabled", 
                    player.getName().getString());
        }
    }
    
    /**
     * Sets always crit mode for a fake player
     * @param player The fake player
     * @param alwaysCrit Whether all attacks should be critical hits
     */
    public static void setAlwaysCrit(EntityPlayerMPFake player, boolean alwaysCrit) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.setAlwaysCrit(alwaysCrit);
            LOGGER.debug("Always crit mode {} for fake player: {}", 
                    alwaysCrit ? "enabled" : "disabled", 
                    player.getName().getString());
        }
    }
    
    /**
     * Sets the next attack to be a guaranteed critical hit
     * @param player The fake player
     */
    public static void setNextAttackCritical(EntityPlayerMPFake player) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.setNextAttackCritical(true);
            LOGGER.debug("Next attack set to critical for fake player: {}", player.getName().getString());
        }
    }
    
    /**
     * Simulates a jump attack for critical hits
     * @param player The fake player
     */
    public static void simulateJumpAttack(EntityPlayerMPFake player) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            long currentTime = System.currentTimeMillis();
            
            // Set jump state
            critState.setJumping(true);
            critState.setLastJumpTime(currentTime);
            
            // Simulate fall distance for critical hit
            critState.setFallDistance(0.5); // Enough for critical hit detection
            
            // Apply upward velocity to simulate jump
            Vec3 currentVelocity = player.getDeltaMovement();
            player.setDeltaMovement(currentVelocity.x, 0.42, currentVelocity.z); // Standard jump velocity
            
            LOGGER.debug("Simulated jump attack for fake player: {}", player.getName().getString());
        }
    }
    
    /**
     * Updates fall distance for a fake player
     * @param player The fake player
     * @param fallDistance The new fall distance
     */
    public static void updateFallDistance(EntityPlayerMPFake player, double fallDistance) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.updateFallDistance(fallDistance);
        }
    }
    
    /**
     * Resets critical hit state for a player (used on respawn/teleport)
     * @param player The fake player
     */
    public static void resetCriticalHitState(EntityPlayerMPFake player) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.reset();
            LOGGER.debug("Reset critical hit state for fake player: {}", player.getName().getString());
        }
    }
    
    /**
     * Resets fall state when player lands or teleports
     * @param player The fake player
     */
    public static void resetFallState(EntityPlayerMPFake player) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState != null) {
            critState.resetFallState();
        }
    }
    
    /**
     * Plays critical hit visual and audio effects
     * @param player The fake player performing the critical hit
     * @param target The target entity
     * @param critState The critical hit state
     */
    private static void playCriticalHitEffects(EntityPlayerMPFake player, Entity target, CriticalHitState critState) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        
        try {
            // Play critical hit particles
            if (critState.shouldShowParticles()) {
                Vec3 targetPos = target.position();
                serverLevel.sendParticles(
                    ParticleTypes.CRIT,
                    targetPos.x, targetPos.y + target.getBbHeight() * 0.5, targetPos.z,
                    5, // particle count
                    0.1, 0.1, 0.1, // spread
                    0.0 // speed
                );
            }
            
            // Play critical hit sound
            if (critState.shouldPlaySound()) {
                Vec3 targetPos = target.position();
                serverLevel.playSound(
                    null, // no specific player
                    targetPos.x, targetPos.y, targetPos.z,
                    SoundEvents.PLAYER_ATTACK_CRIT,
                    SoundSource.PLAYERS,
                    1.0f, // volume
                    1.0f  // pitch
                );
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to play critical hit effects for fake player {}: {}", 
                    player.getName().getString(), e.getMessage(), e);
        }
    }
    
    /**
     * Gets debug information about a player's critical hit state
     * @param player The fake player
     * @return Debug string with critical hit state information
     */
    public static String getDebugInfo(EntityPlayerMPFake player) {
        CriticalHitState critState = getCriticalHitState(player);
        if (critState == null) {
            return "No critical hit state";
        }
        
        return String.format("Critical Hit State: enabled=%s, alwaysCrit=%s, nextCrit=%s, fallDistance=%.2f, canCrit=%s",
                critState.isEnabled(),
                critState.isAlwaysCrit(),
                critState.isNextAttackCritical(),
                critState.getFallDistance(),
                CriticalHitDetector.checkCriticalHitConditions(player));
    }
}