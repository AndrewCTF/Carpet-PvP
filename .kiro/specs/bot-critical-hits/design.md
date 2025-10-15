# Bot Critical Hits - Design Document

## Overview

This design document outlines the technical implementation for enabling critical hit functionality in fake players (bots). The solution involves extending the existing `EntityPlayerMPFake` class with proper fall distance tracking, combat state management, and critical hit detection logic that mirrors vanilla Minecraft's critical hit mechanics.

## Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                    Bot Critical Hit System                   │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │   Combat State  │  │  Critical Hit   │  │   Command    │ │
│  │    Manager      │  │    Detector     │  │  Extensions  │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐ │
│  │  Fall Distance  │  │    Scarpet      │  │   Settings   │ │
│  │    Tracker      │  │  Integration    │  │   Manager    │ │
│  └─────────────────┘  └─────────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### Integration Points

1. **EntityPlayerMPFake Enhancement**: Extend the fake player class with critical hit capabilities
2. **PlayerCommand Extension**: Add critical hit commands to the existing player command system
3. **Scarpet API Extension**: Add new functions for programmatic critical hit control
4. **Mixin Integration**: Hook into vanilla attack mechanics for seamless critical hit detection

## Components and Interfaces

### 1. CriticalHitManager

**Purpose**: Manages critical hit state and logic for fake players

```java
public class CriticalHitManager {
    // Core critical hit detection
    public boolean canPerformCriticalHit(EntityPlayerMPFake player);
    public void performCriticalHit(EntityPlayerMPFake player, Entity target);
    
    // State management
    public void enableCriticalHits(EntityPlayerMPFake player, boolean enabled);
    public void setAlwaysCrit(EntityPlayerMPFake player, boolean alwaysCrit);
    public void setNextAttackCritical(EntityPlayerMPFake player);
    
    // Fall distance simulation
    public void simulateJumpAttack(EntityPlayerMPFake player);
    public void updateFallDistance(EntityPlayerMPFake player, double fallDistance);
}
```

### 2. FallDistanceTracker

**Purpose**: Tracks and manages fall distance for fake players to enable natural critical hits

```java
public class FallDistanceTracker {
    // Fall distance management
    public void startFalling(EntityPlayerMPFake player);
    public void updateFallDistance(EntityPlayerMPFake player, double distance);
    public void resetFallDistance(EntityPlayerMPFake player);
    
    // State queries
    public double getFallDistance(EntityPlayerMPFake player);
    public boolean isPlayerFalling(EntityPlayerMPFake player);
    public boolean canCritFromFallDistance(EntityPlayerMPFake player);
}
```

### 3. CriticalHitDetector

**Purpose**: Determines when critical hit conditions are met

```java
public class CriticalHitDetector {
    // Critical hit condition checking
    public boolean checkCriticalHitConditions(EntityPlayerMPFake player);
    public boolean isValidCriticalHitTarget(Entity target);
    
    // Vanilla mechanics compliance
    public boolean isPlayerInValidState(EntityPlayerMPFake player);
    public boolean hasRequiredFallDistance(EntityPlayerMPFake player);
}
```

### 4. Enhanced EntityPlayerMPFake

**Purpose**: Extended fake player class with critical hit capabilities

```java
public class EntityPlayerMPFake extends ServerPlayer {
    // Critical hit state
    private boolean criticalHitsEnabled = true;
    private boolean alwaysCrit = false;
    private boolean nextAttackCritical = false;
    private double simulatedFallDistance = 0.0;
    
    // Critical hit methods
    public void performCriticalAttack(Entity target);
    public boolean canCriticalHit();
    public void simulateJumpForCrit();
    
    // State management
    public void setCriticalHitsEnabled(boolean enabled);
    public void setAlwaysCrit(boolean alwaysCrit);
    public void setNextAttackCritical();
}
```

## Data Models

### CriticalHitState

```java
public class CriticalHitState {
    private boolean enabled;              // Whether critical hits are enabled
    private boolean alwaysCrit;          // Force all attacks to be critical
    private boolean nextAttackCritical;  // Next attack should be critical
    private double fallDistance;         // Simulated fall distance
    private long lastJumpTime;          // Timestamp of last jump
    private boolean isJumping;           // Currently in jump state
    
    // Configuration
    private float damageMultiplier;      // Critical hit damage multiplier
    private boolean showParticles;       // Show critical hit particles
    private boolean playSound;          // Play critical hit sound
}
```

### CriticalHitConfig

```java
public class CriticalHitConfig {
    public static boolean fakePlayers_allowCriticalHits = true;
    public static float fakePlayers_criticalHitMultiplier = 1.5f;
    public static boolean fakePlayers_alwaysCrit = false;
    public static boolean fakePlayers_showCritParticles = true;
    public static boolean fakePlayers_playCritSounds = true;
}
```

## Error Handling

### Critical Hit Validation

1. **Player State Validation**: Ensure the fake player is in a valid state for critical hits
2. **Target Validation**: Verify the target entity can receive critical hits
3. **Environment Validation**: Check for water, lava, or other conditions that prevent critical hits
4. **Configuration Validation**: Ensure critical hit settings are valid

### Error Recovery

1. **State Corruption**: Reset critical hit state if corruption is detected
2. **Invalid Targets**: Gracefully handle attacks on invalid targets
3. **Performance Issues**: Implement rate limiting for critical hit calculations
4. **Memory Leaks**: Proper cleanup of critical hit state when players are removed

## Testing Strategy

### Unit Tests

1. **CriticalHitManager Tests**
   - Test critical hit condition detection
   - Test state management functions
   - Test damage calculation with multipliers

2. **FallDistanceTracker Tests**
   - Test fall distance tracking accuracy
   - Test reset conditions
   - Test edge cases (teleportation, dimension changes)

3. **CriticalHitDetector Tests**
   - Test vanilla mechanics compliance
   - Test environmental condition checking
   - Test target validation

### Integration Tests

1. **Command Integration**
   - Test `/player <bot> attack critical` command
   - Test `/player <bot> crit <on|off>` command
   - Test `/player <bot> jump attack` command

2. **Scarpet Integration**
   - Test `player_crit()` function
   - Test `can_crit()` function
   - Test `simulate_jump_attack()` function

3. **Combat Integration**
   - Test critical hits in actual combat scenarios
   - Test interaction with armor and enchantments
   - Test multiplayer scenarios with multiple bots

### Performance Tests

1. **Load Testing**: Test with multiple bots performing critical hits simultaneously
2. **Memory Testing**: Verify no memory leaks with long-running bots
3. **Latency Testing**: Ensure critical hit calculations don't impact server performance

## Implementation Phases

### Phase 1: Core Critical Hit Logic
- Implement `CriticalHitManager` class
- Add basic critical hit detection to `EntityPlayerMPFake`
- Create unit tests for core functionality

### Phase 2: Fall Distance Simulation
- Implement `FallDistanceTracker` class
- Add fall distance tracking to fake players
- Implement jump simulation for critical hits

### Phase 3: Command Integration
- Extend `PlayerCommand` with critical hit commands
- Add configuration options for critical hit behavior
- Implement command validation and error handling

### Phase 4: Scarpet Integration
- Add critical hit functions to Scarpet API
- Implement proper error handling for script calls
- Create documentation for new functions

### Phase 5: Visual and Audio Effects
- Implement critical hit particle effects
- Add critical hit sound effects
- Ensure effects are synchronized across clients

### Phase 6: Testing and Optimization
- Comprehensive testing of all features
- Performance optimization
- Bug fixes and edge case handling

## Security Considerations

1. **Command Permissions**: Ensure only authorized users can control bot critical hits
2. **Rate Limiting**: Prevent abuse of critical hit commands
3. **Validation**: Validate all inputs to prevent exploits
4. **Resource Management**: Prevent excessive resource usage from critical hit calculations

## Performance Considerations

1. **Lazy Evaluation**: Only calculate critical hit conditions when needed
2. **Caching**: Cache critical hit state to avoid repeated calculations
3. **Batch Processing**: Process multiple critical hit checks efficiently
4. **Memory Management**: Proper cleanup of critical hit state data

## Compatibility

1. **Vanilla Compatibility**: Ensure critical hit mechanics match vanilla behavior
2. **Mod Compatibility**: Work with other combat-related mods
3. **Version Compatibility**: Support current and future Minecraft versions
4. **API Stability**: Maintain stable API for Scarpet integration