package carpet.helpers.pathfinding;

/**
 * High-level navigation mode selector for fake players.
 *
 * This is intentionally simple: it’s a public API surface we can extend later
 * (Baritone-like features: replanning policies, costs, avoiding danger, etc.).
 */
public enum BotNavMode
{
    AUTO,
    LAND,
    WATER,
    AIR
}
