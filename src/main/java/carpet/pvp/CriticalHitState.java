package carpet.pvp;

/**
 * Data model for tracking critical hit state of fake players
 * Manages all state related to critical hit mechanics including fall distance simulation,
 * configuration overrides, and timing information.
 */
public class CriticalHitState {
    private boolean enabled;              // Whether critical hits are enabled for this player
    private boolean alwaysCrit;          // Force all attacks to be critical hits
    private boolean nextAttackCritical;  // Next attack should be guaranteed critical
    private double fallDistance;         // Simulated fall distance for critical hit detection
    private long lastJumpTime;          // Timestamp of last jump for timing calculations
    private boolean isJumping;           // Currently in jump state
    
    // Configuration overrides
    private float damageMultiplier;      // Critical hit damage multiplier (default from settings)
    private boolean showParticles;       // Show critical hit particles
    private boolean playSound;          // Play critical hit sound
    
    /**
     * Creates a new critical hit state with default values
     */
    public CriticalHitState() {
        this.enabled = true;
        this.alwaysCrit = false;
        this.nextAttackCritical = false;
        this.fallDistance = 0.0;
        this.lastJumpTime = 0;
        this.isJumping = false;
        this.damageMultiplier = 1.5f; // Default vanilla critical hit multiplier
        this.showParticles = true;
        this.playSound = true;
    }
    
    // Getters
    public boolean isEnabled() { return enabled; }
    public boolean isAlwaysCrit() { return alwaysCrit; }
    public boolean isNextAttackCritical() { return nextAttackCritical; }
    public double getFallDistance() { return fallDistance; }
    public long getLastJumpTime() { return lastJumpTime; }
    public boolean isJumping() { return isJumping; }
    public float getDamageMultiplier() { return damageMultiplier; }
    public boolean shouldShowParticles() { return showParticles; }
    public boolean shouldPlaySound() { return playSound; }
    
    // Setters
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setAlwaysCrit(boolean alwaysCrit) { this.alwaysCrit = alwaysCrit; }
    public void setNextAttackCritical(boolean nextAttackCritical) { this.nextAttackCritical = nextAttackCritical; }
    public void setFallDistance(double fallDistance) { this.fallDistance = fallDistance; }
    public void setLastJumpTime(long lastJumpTime) { this.lastJumpTime = lastJumpTime; }
    public void setJumping(boolean jumping) { this.isJumping = jumping; }
    public void setDamageMultiplier(float damageMultiplier) { this.damageMultiplier = damageMultiplier; }
    public void setShowParticles(boolean showParticles) { this.showParticles = showParticles; }
    public void setPlaySound(boolean playSound) { this.playSound = playSound; }
    
    /**
     * Resets the critical hit state to default values
     * Used when respawning or resetting player state
     */
    public void reset() {
        this.alwaysCrit = false;
        this.nextAttackCritical = false;
        this.fallDistance = 0.0;
        this.lastJumpTime = 0;
        this.isJumping = false;
        // Keep enabled, damageMultiplier, showParticles, and playSound as they are configuration-based
    }
    
    /**
     * Resets fall distance and jump state
     * Called when player lands or teleports
     */
    public void resetFallState() {
        this.fallDistance = 0.0;
        this.isJumping = false;
    }
    
    /**
     * Consumes the next attack critical flag
     * Returns true if the next attack should be critical and resets the flag
     */
    public boolean consumeNextAttackCritical() {
        boolean result = this.nextAttackCritical;
        this.nextAttackCritical = false;
        return result;
    }
    
    /**
     * Updates fall distance with validation
     * Ensures fall distance doesn't go negative
     */
    public void updateFallDistance(double newFallDistance) {
        this.fallDistance = Math.max(0.0, newFallDistance);
    }
    
    /**
     * Checks if enough time has passed since last jump for natural critical hits
     * Prevents rapid-fire critical hits from jump spamming
     */
    public boolean canNaturalCrit(long currentTime) {
        return currentTime - lastJumpTime > 100; // 100ms cooldown
    }
    
    @Override
    public String toString() {
        return String.format("CriticalHitState{enabled=%s, alwaysCrit=%s, nextCrit=%s, fallDistance=%.2f, jumping=%s}", 
                enabled, alwaysCrit, nextAttackCritical, fallDistance, isJumping);
    }
}