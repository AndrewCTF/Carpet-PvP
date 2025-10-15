package carpet.pvp;

/**
 * Validation test for critical hit infrastructure
 * Tests the core data models and utility classes without Minecraft dependencies
 */
public class CriticalHitInfrastructureValidation {
    
    public static void main(String[] args) {
        System.out.println("=== CRITICAL HIT INFRASTRUCTURE VALIDATION ===");
        System.out.println("Testing core critical hit functionality...");
        System.out.println();
        
        boolean allTestsPass = true;
        
        try {
            allTestsPass &= testCriticalHitStateDefaults();
            allTestsPass &= testCriticalHitStateSetters();
            allTestsPass &= testConsumeNextAttackCritical();
            allTestsPass &= testUpdateFallDistance();
            allTestsPass &= testReset();
            allTestsPass &= testResetFallState();
            allTestsPass &= testCanNaturalCrit();
            
            System.out.println();
            if (allTestsPass) {
                System.out.println("✓ ALL CRITICAL HIT INFRASTRUCTURE TESTS PASSED");
            } else {
                System.out.println("✗ SOME CRITICAL HIT INFRASTRUCTURE TESTS FAILED");
            }
            
        } catch (Exception e) {
            System.out.println("✗ CRITICAL HIT INFRASTRUCTURE VALIDATION FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static boolean testCriticalHitStateDefaults() {
        System.out.println("Testing CriticalHitState defaults...");
        
        CriticalHitState state = new CriticalHitState();
        
        boolean pass = true;
        pass &= assertEqual("enabled default", true, state.isEnabled());
        pass &= assertEqual("alwaysCrit default", false, state.isAlwaysCrit());
        pass &= assertEqual("nextAttackCritical default", false, state.isNextAttackCritical());
        pass &= assertEqual("fallDistance default", 0.0, state.getFallDistance());
        pass &= assertEqual("jumping default", false, state.isJumping());
        pass &= assertEqual("damageMultiplier default", 1.5f, state.getDamageMultiplier());
        pass &= assertEqual("showParticles default", true, state.shouldShowParticles());
        pass &= assertEqual("playSound default", true, state.shouldPlaySound());
        
        System.out.println(pass ? "✓ CriticalHitState defaults test passed" : "✗ CriticalHitState defaults test failed");
        return pass;
    }
    
    private static boolean testCriticalHitStateSetters() {
        System.out.println("Testing CriticalHitState setters...");
        
        CriticalHitState state = new CriticalHitState();
        
        state.setEnabled(false);
        state.setAlwaysCrit(true);
        state.setNextAttackCritical(true);
        state.setFallDistance(2.5);
        state.setJumping(true);
        state.setDamageMultiplier(2.0f);
        state.setShowParticles(false);
        state.setPlaySound(false);
        
        boolean pass = true;
        pass &= assertEqual("enabled setter", false, state.isEnabled());
        pass &= assertEqual("alwaysCrit setter", true, state.isAlwaysCrit());
        pass &= assertEqual("nextAttackCritical setter", true, state.isNextAttackCritical());
        pass &= assertEqual("fallDistance setter", 2.5, state.getFallDistance());
        pass &= assertEqual("jumping setter", true, state.isJumping());
        pass &= assertEqual("damageMultiplier setter", 2.0f, state.getDamageMultiplier());
        pass &= assertEqual("showParticles setter", false, state.shouldShowParticles());
        pass &= assertEqual("playSound setter", false, state.shouldPlaySound());
        
        System.out.println(pass ? "✓ CriticalHitState setters test passed" : "✗ CriticalHitState setters test failed");
        return pass;
    }
    
    private static boolean testConsumeNextAttackCritical() {
        System.out.println("Testing consumeNextAttackCritical...");
        
        CriticalHitState state = new CriticalHitState();
        
        boolean pass = true;
        
        // Initially false
        pass &= assertEqual("initial consume", false, state.consumeNextAttackCritical());
        
        // Set to true and consume
        state.setNextAttackCritical(true);
        pass &= assertEqual("consume when true", true, state.consumeNextAttackCritical());
        
        // Should be false after consuming
        pass &= assertEqual("after consume", false, state.isNextAttackCritical());
        pass &= assertEqual("consume again", false, state.consumeNextAttackCritical());
        
        System.out.println(pass ? "✓ consumeNextAttackCritical test passed" : "✗ consumeNextAttackCritical test failed");
        return pass;
    }
    
    private static boolean testUpdateFallDistance() {
        System.out.println("Testing updateFallDistance...");
        
        CriticalHitState state = new CriticalHitState();
        
        boolean pass = true;
        
        // Normal positive value
        state.updateFallDistance(1.5);
        pass &= assertEqual("positive fall distance", 1.5, state.getFallDistance());
        
        // Negative value should be clamped to 0
        state.updateFallDistance(-1.0);
        pass &= assertEqual("negative fall distance clamped", 0.0, state.getFallDistance());
        
        // Zero should work
        state.updateFallDistance(0.0);
        pass &= assertEqual("zero fall distance", 0.0, state.getFallDistance());
        
        System.out.println(pass ? "✓ updateFallDistance test passed" : "✗ updateFallDistance test failed");
        return pass;
    }
    
    private static boolean testReset() {
        System.out.println("Testing reset...");
        
        CriticalHitState state = new CriticalHitState();
        
        // Set some values
        state.setAlwaysCrit(true);
        state.setNextAttackCritical(true);
        state.setFallDistance(2.0);
        state.setJumping(true);
        state.setLastJumpTime(12345L);
        
        // Reset
        state.reset();
        
        boolean pass = true;
        pass &= assertEqual("reset alwaysCrit", false, state.isAlwaysCrit());
        pass &= assertEqual("reset nextAttackCritical", false, state.isNextAttackCritical());
        pass &= assertEqual("reset fallDistance", 0.0, state.getFallDistance());
        pass &= assertEqual("reset jumping", false, state.isJumping());
        pass &= assertEqual("reset lastJumpTime", 0L, state.getLastJumpTime());
        
        // These should remain unchanged
        pass &= assertEqual("preserve enabled", true, state.isEnabled());
        pass &= assertEqual("preserve damageMultiplier", 1.5f, state.getDamageMultiplier());
        pass &= assertEqual("preserve showParticles", true, state.shouldShowParticles());
        pass &= assertEqual("preserve playSound", true, state.shouldPlaySound());
        
        System.out.println(pass ? "✓ reset test passed" : "✗ reset test failed");
        return pass;
    }
    
    private static boolean testResetFallState() {
        System.out.println("Testing resetFallState...");
        
        CriticalHitState state = new CriticalHitState();
        
        state.setFallDistance(2.0);
        state.setJumping(true);
        
        state.resetFallState();
        
        boolean pass = true;
        pass &= assertEqual("resetFallState fallDistance", 0.0, state.getFallDistance());
        pass &= assertEqual("resetFallState jumping", false, state.isJumping());
        
        System.out.println(pass ? "✓ resetFallState test passed" : "✗ resetFallState test failed");
        return pass;
    }
    
    private static boolean testCanNaturalCrit() {
        System.out.println("Testing canNaturalCrit...");
        
        CriticalHitState state = new CriticalHitState();
        long currentTime = System.currentTimeMillis();
        
        boolean pass = true;
        
        // No previous jump time should allow crit
        pass &= assertEqual("no previous jump", true, state.canNaturalCrit(currentTime));
        
        // Recent jump should prevent crit
        state.setLastJumpTime(currentTime - 50); // 50ms ago
        pass &= assertEqual("recent jump", false, state.canNaturalCrit(currentTime));
        
        // Old jump should allow crit
        state.setLastJumpTime(currentTime - 200); // 200ms ago
        pass &= assertEqual("old jump", true, state.canNaturalCrit(currentTime));
        
        System.out.println(pass ? "✓ canNaturalCrit test passed" : "✗ canNaturalCrit test failed");
        return pass;
    }
    
    private static boolean assertEqual(String testName, Object expected, Object actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            System.out.println("  ✗ " + testName + ": expected " + expected + ", got " + actual);
            return false;
        }
        
        boolean equal;
        if (expected instanceof Double && actual instanceof Double) {
            equal = Math.abs((Double)expected - (Double)actual) < 0.001;
        } else if (expected instanceof Float && actual instanceof Float) {
            equal = Math.abs((Float)expected - (Float)actual) < 0.001f;
        } else {
            equal = expected.equals(actual);
        }
        
        if (!equal) {
            System.out.println("  ✗ " + testName + ": expected " + expected + ", got " + actual);
        }
        return equal;
    }
}