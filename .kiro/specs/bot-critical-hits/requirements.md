# Bot Critical Hits - Requirements Document

## Introduction

This specification defines the requirements for enabling critical hit functionality for fake players (bots) in Carpet PvP. Currently, fake players cannot perform critical hits because they lack the necessary movement states and fall distance tracking that real players have. This feature will implement proper critical hit mechanics for bots to make them more realistic in combat scenarios.

## Requirements

### Requirement 1: Basic Critical Hit Mechanics

**User Story:** As a server administrator, I want fake players to be able to perform critical hits, so that they behave more like real players in combat scenarios.

#### Acceptance Criteria

1. WHEN a fake player attacks an entity THEN the system SHALL check if critical hit conditions are met
2. WHEN critical hit conditions are satisfied THEN the attack SHALL deal increased damage (1.5x base damage)
3. WHEN a critical hit occurs THEN the system SHALL display critical hit particles
4. WHEN a critical hit occurs THEN the system SHALL play the critical hit sound effect
5. IF the fake player is not falling or jumping THEN critical hits SHALL NOT occur naturally

### Requirement 2: Manual Critical Hit Control

**User Story:** As a server administrator, I want to manually control when fake players perform critical hits, so that I can test combat mechanics and create specific scenarios.

#### Acceptance Criteria

1. WHEN using `/player <bot_name> attack critical` THEN the next attack SHALL be a guaranteed critical hit
2. WHEN using `/player <bot_name> jump attack` THEN the bot SHALL jump and perform a critical hit attack
3. WHEN using `/player <bot_name> crit <on|off>` THEN critical hits SHALL be enabled/disabled for that bot
4. IF critical hits are disabled for a bot THEN it SHALL never perform critical hits regardless of conditions
5. WHEN critical hit mode is enabled THEN all attacks from that bot SHALL be critical hits

### Requirement 3: Realistic Fall Distance Simulation

**User Story:** As a server administrator, I want fake players to simulate realistic fall distance for critical hits, so that their combat behavior matches real players.

#### Acceptance Criteria

1. WHEN a fake player jumps THEN its fall distance SHALL be tracked accurately
2. WHEN fall distance is greater than 0 AND the player is not on ground THEN critical hit conditions SHALL be met
3. WHEN a fake player lands THEN fall distance SHALL be reset to 0
4. WHEN a fake player is in water or lava THEN critical hits SHALL NOT occur
5. WHEN a fake player is riding an entity THEN critical hits SHALL NOT occur

### Requirement 4: Scarpet Integration

**User Story:** As a script developer, I want to control fake player critical hits through Scarpet functions, so that I can create advanced combat scenarios programmatically.

#### Acceptance Criteria

1. WHEN calling `player_crit(player, true)` THEN the next attack SHALL be a critical hit
2. WHEN calling `player_crit(player, false)` THEN critical hits SHALL be disabled for that player
3. WHEN calling `can_crit(player)` THEN it SHALL return true if the player can currently perform critical hits
4. WHEN calling `simulate_jump_attack(player, target)` THEN the player SHALL perform a jumping critical hit attack
5. IF the player parameter is not a fake player THEN the functions SHALL return appropriate error messages

### Requirement 5: Combat State Management

**User Story:** As a server administrator, I want fake players to maintain proper combat states for critical hits, so that the mechanics work consistently across different scenarios.

#### Acceptance Criteria

1. WHEN a fake player is created THEN it SHALL have proper fall distance tracking initialized
2. WHEN a fake player respawns THEN its combat state SHALL be reset appropriately
3. WHEN a fake player changes dimensions THEN its combat state SHALL be preserved
4. WHEN multiple fake players attack simultaneously THEN each SHALL have independent critical hit states
5. IF a fake player is in creative mode THEN critical hit mechanics SHALL still function normally

### Requirement 6: Configuration and Settings

**User Story:** As a server administrator, I want to configure critical hit behavior for fake players, so that I can customize the feature for different use cases.

#### Acceptance Criteria

1. WHEN `fakePlayers_allowCriticalHits` is true THEN fake players SHALL be able to perform critical hits
2. WHEN `fakePlayers_allowCriticalHits` is false THEN fake players SHALL never perform critical hits
3. WHEN `fakePlayers_criticalHitMultiplier` is set THEN critical hits SHALL use that damage multiplier
4. WHEN `fakePlayers_alwaysCrit` is true THEN all fake player attacks SHALL be critical hits
5. IF configuration values are invalid THEN the system SHALL use default values and log warnings

### Requirement 7: Visual and Audio Feedback

**User Story:** As a player, I want to see and hear when fake players perform critical hits, so that I can understand what's happening in combat.

#### Acceptance Criteria

1. WHEN a fake player performs a critical hit THEN critical hit particles SHALL be displayed at the target location
2. WHEN a critical hit occurs THEN the critical hit sound SHALL be played for nearby players
3. WHEN using debug mode THEN critical hit information SHALL be logged to the console
4. WHEN a fake player jumps for a critical hit THEN appropriate movement animations SHALL be displayed
5. IF particle effects are disabled THEN only sound effects SHALL be played

### Requirement 8: Compatibility and Performance

**User Story:** As a server administrator, I want the critical hit system to be performant and compatible, so that it doesn't impact server performance or conflict with other features.

#### Acceptance Criteria

1. WHEN multiple fake players perform critical hits THEN server performance SHALL not be significantly impacted
2. WHEN other mods modify combat mechanics THEN the fake player critical hit system SHALL remain functional
3. WHEN the system is disabled THEN there SHALL be no performance overhead
4. WHEN fake players are removed THEN their critical hit state SHALL be properly cleaned up
5. IF memory usage becomes excessive THEN the system SHALL implement appropriate cleanup mechanisms