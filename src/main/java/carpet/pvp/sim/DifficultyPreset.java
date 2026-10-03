package carpet.pvp.sim;

/**
 * One difficulty level: the planner settings the fighter searches with, plus the two human limits every real
 * player has, how many ticks late they act on what they see and how often they fail to press attack in time.
 */
public record DifficultyPreset(String name, PlannerParams params, int reactionDelay, double missChance)
{
}