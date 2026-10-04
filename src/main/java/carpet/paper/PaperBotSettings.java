package carpet.paper;

import carpet.pvp.BotSettings;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Reads the plugin's {@code config.yml} into {@link BotSettings}, the holder the shared bot code
 * reads. On Fabric the same holder is filled from Carpet's rules, so the two platforms read the same
 * values from different places.
 */
public final class PaperBotSettings
{
    private PaperBotSettings() {}

    public static void load(FileConfiguration config)
    {
        BotSettings.allowSpawningOfflinePlayers = config.getBoolean("fakePlayer.allowSpawningOfflinePlayers", true);
        BotSettings.fakePlayerDropInventoryOnDeath = config.getBoolean("fakePlayer.dropInventoryOnDeath", false);
        BotSettings.allowListingFakePlayers = config.getBoolean("fakePlayer.listInPlayerList", false);
        BotSettings.fakePlayerFallDamage = config.getBoolean("fakePlayer.fallDamage", true);
        BotSettings.shieldStunning = config.getBoolean("fakePlayer.shieldStunning", false);

        BotSettings.fakePlayerNavigation = config.getBoolean("navigation.enabled", true);
        BotSettings.fakePlayerElytraGlide = config.getBoolean("navigation.elytraGlide", false);
        BotSettings.fakePlayerNavBreakBlocks = config.getBoolean("navigation.breakBlocks", false);
        BotSettings.fakePlayerNavPlaceBlocks = config.getBoolean("navigation.placeBlocks", false);
        BotSettings.fakePlayerNavAutoTool = config.getBoolean("navigation.autoTool", true);
        BotSettings.fakePlayerNavAutoEat = config.getBoolean("navigation.autoEat", true);
        BotSettings.fakePlayerNavAutoEatBelow = config.getInt("navigation.autoEatBelow", 10);
        BotSettings.fakePlayerNavAvoidLava = config.getBoolean("navigation.avoidLava", true);
        BotSettings.fakePlayerNavAvoidFire = config.getBoolean("navigation.avoidFire", true);
        BotSettings.fakePlayerNavAvoidCobwebs = config.getBoolean("navigation.avoidCobwebs", true);
        BotSettings.fakePlayerNavBreakCobwebs = config.getBoolean("navigation.breakCobwebs", true);
        BotSettings.fakePlayerNavAvoidPowderSnow = config.getBoolean("navigation.avoidPowderSnow", true);
        BotSettings.fakePlayerNavAllowParkour = config.getBoolean("navigation.allowParkour", true);
        BotSettings.fakePlayerNavAllowPillar = config.getBoolean("navigation.allowPillar", false);
        BotSettings.fakePlayerNavAllowBreakThrough = config.getBoolean("navigation.allowBreakThrough", false);
        BotSettings.fakePlayerNavAllowDescendMine = config.getBoolean("navigation.allowDescendMine", false);
        BotSettings.fakePlayerNavAllowSprint = config.getBoolean("navigation.allowSprint", true);
        BotSettings.fakePlayerNavMobAvoidance = config.getBoolean("navigation.mobAvoidance", false);
        BotSettings.fakePlayerNavMobAvoidanceRadius = config.getInt("navigation.mobAvoidanceRadius", 8);
        BotSettings.fakePlayerNavMaxFallHeight = config.getInt("navigation.maxFallHeight", 4);
        BotSettings.fakePlayerNavAvoidSoulSand = config.getBoolean("navigation.avoidSoulSand", false);
        BotSettings.fakePlayerNavAllowOpenDoors = config.getBoolean("navigation.allowOpenDoors", true);
        BotSettings.fakePlayerNavAllowOpenFenceGates = config.getBoolean("navigation.allowOpenFenceGates", true);
        BotSettings.fakePlayerNavAllowSwimming = config.getBoolean("navigation.allowSwimming", false);
        BotSettings.fakePlayerNavSearchBudget = config.getInt("navigation.searchBudget", 1500);
        BotSettings.fakePlayerNavSearchBudgetTotal = config.getInt("navigation.searchBudgetTotal", 12000);

        BotSettings.spamClickCombat = config.getBoolean("combat.spamClickCombat", false);
        BotSettings.botCombat = config.getBoolean("combat.botCombat", false);
        BotSettings.botAutoTarget = config.getBoolean("combat.autoTarget", true);
        BotSettings.botTargetPlayers = config.getBoolean("combat.targetPlayers", true);
        BotSettings.botTargetMobs = config.getBoolean("combat.targetMobs", false);
        BotSettings.botTargetBots = config.getBoolean("combat.targetBots", true);
        BotSettings.botRevenge = config.getBoolean("combat.revenge", true);
        BotSettings.botTargetRange = config.getDouble("combat.targetRange", 16.0D);
        BotSettings.botRetreatHealth = config.getInt("combat.retreatHealth", 0);
        BotSettings.botAutoTotem = config.getBoolean("combat.autoTotem", true);
        BotSettings.botAutoShield = config.getBoolean("combat.autoShield", false);
        BotSettings.botAutoFood = config.getBoolean("combat.autoFood", true);
        BotSettings.botAutoPotion = config.getBoolean("combat.autoPotion", false);
        BotSettings.botAutoArmor = config.getBoolean("combat.autoArmor", false);
        BotSettings.botAutoWeapon = config.getBoolean("combat.autoWeapon", false);
        BotSettings.botAutoRepair = config.getBoolean("combat.autoRepair", false);
        BotSettings.botCombatStyle = config.getString("combat.style", "MELEE");
        BotSettings.botDifficulty = config.getString("combat.difficulty", "AVERAGE");
        BotSettings.botPreferSword = config.getBoolean("combat.preferSword", true);
        BotSettings.botShieldBreak = config.getBoolean("combat.shieldBreak", false);
        BotSettings.botCritical = config.getBoolean("combat.critical", true);
        BotSettings.botStrafe = config.getBoolean("combat.strafe", true);
        BotSettings.botBhop = config.getBoolean("combat.bhop", false);
        BotSettings.botWTap = config.getBoolean("combat.wtap", true);
        BotSettings.botShieldPlay = config.getBoolean("combat.shieldPlay", true);
        BotSettings.botMeleeRange = config.getDouble("combat.meleeRange", 3.0D);
        BotSettings.botAttackCooldown = config.getInt("combat.attackCooldown", 0);
        BotSettings.botMissChance = config.getInt("combat.missChance", 0);
        BotSettings.botMistakeChance = config.getInt("combat.mistakeChance", 0);
        BotSettings.botReactionDelay = config.getInt("combat.reactionDelay", 0);
        BotSettings.botSkill = config.getDouble("combat.skill", 0.6D);
        BotSettings.botPingTicks = config.getInt("combat.pingTicks", 1);
        BotSettings.botClicksPerSecond = config.getDouble("combat.clicksPerSecond", 10.0D);
        BotSettings.botPlannerRange = config.getDouble("combat.plannerRange", 8.0D);
        BotSettings.botPlannerHorizon = config.getInt("combat.plannerHorizon", 12);
        BotSettings.botPlannerPopulation = config.getInt("combat.plannerPopulation", 10);
        BotSettings.botSimBudget = config.getInt("combat.simBudget", 20000);
    }
}
