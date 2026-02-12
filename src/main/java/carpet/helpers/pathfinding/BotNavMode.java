package carpet.helpers.pathfinding;

/**
 * High-level navigation mode selector for fake players.
 *
 * Modes:
 *   AUTO  – automatically choose LAND, WATER, or AIR based on context
 *   LAND  – walk / sprint / jump / parkour / pillar / break-through
 *   WATER – swim through water
 *   AIR   – elytra glide
 *   FOLLOW – follow another player or entity at a set distance
 *   MINE   – locate and mine a specific block type
 *   PATROL – walk between a series of waypoints
 *   COME   – navigate to the command sender's position
 */
public enum BotNavMode
{
    AUTO,
    LAND,
    WATER,
    AIR,
    FOLLOW,
    MINE,
    PATROL,
    COME
}
