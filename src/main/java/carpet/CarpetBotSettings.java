package carpet;

import carpet.api.settings.SettingsManager;
import carpet.pvp.BotSettings;

/**
 * Keeps {@link BotSettings}, the holder the shared bot code reads, in step with the rules.
 *
 * <p>Carpet's rules live in annotated static fields on {@link CarpetSettings}, which the bot code
 * cannot read on a Paper server, where the values come from the plugin's {@code config.yml}
 * instead. This is the Fabric side of that bridge: once after the settings manager knows every
 * rule, and again on every rule change.</p>
 */
public final class CarpetBotSettings
{
    private static boolean hooked;

    private CarpetBotSettings() {}

    /** Called once the settings manager knows every rule. */
    public static void hook()
    {
        if (hooked) return;
        hooked = true;
        update();
        SettingsManager.registerGlobalRuleObserver((source, rule, userInput) -> update());
    }

    /** Copies every rule the shared bot code reads out of {@link CarpetSettings}. */
    public static void update()
    {
        BotSettings.allowSpawningOfflinePlayers = CarpetSettings.allowSpawningOfflinePlayers;
        BotSettings.fakePlayerDropInventoryOnDeath = CarpetSettings.fakePlayerDropInventoryOnDeath;
        BotSettings.allowListingFakePlayers = CarpetSettings.allowListingFakePlayers;
        BotSettings.fakePlayerFallDamage = CarpetSettings.fakePlayerFallDamage;
        BotSettings.shieldStunning = CarpetSettings.shieldStunning;
        BotSettings.swordBlockDamageMultiplier = CarpetSettings.swordBlockDamageMultiplier;

        BotSettings.fakePlayerNavigation = CarpetSettings.fakePlayerNavigation;
        BotSettings.fakePlayerElytraGlide = CarpetSettings.fakePlayerElytraGlide;
        BotSettings.fakePlayerNavBreakBlocks = CarpetSettings.fakePlayerNavBreakBlocks;
        BotSettings.fakePlayerNavPlaceBlocks = CarpetSettings.fakePlayerNavPlaceBlocks;
        BotSettings.fakePlayerNavAutoTool = CarpetSettings.fakePlayerNavAutoTool;
        BotSettings.fakePlayerNavAutoEat = CarpetSettings.fakePlayerNavAutoEat;
        BotSettings.fakePlayerNavAutoEatBelow = CarpetSettings.fakePlayerNavAutoEatBelow;
        BotSettings.fakePlayerNavAvoidLava = CarpetSettings.fakePlayerNavAvoidLava;
        BotSettings.fakePlayerNavAvoidFire = CarpetSettings.fakePlayerNavAvoidFire;
        BotSettings.fakePlayerNavAvoidCobwebs = CarpetSettings.fakePlayerNavAvoidCobwebs;
        BotSettings.fakePlayerNavBreakCobwebs = CarpetSettings.fakePlayerNavBreakCobwebs;
        BotSettings.fakePlayerNavAvoidPowderSnow = CarpetSettings.fakePlayerNavAvoidPowderSnow;
        BotSettings.fakePlayerNavAllowParkour = CarpetSettings.fakePlayerNavAllowParkour;
        BotSettings.fakePlayerNavAllowPillar = CarpetSettings.fakePlayerNavAllowPillar;
        BotSettings.fakePlayerNavAllowBreakThrough = CarpetSettings.fakePlayerNavAllowBreakThrough;
        BotSettings.fakePlayerNavAllowDescendMine = CarpetSettings.fakePlayerNavAllowDescendMine;
        BotSettings.fakePlayerNavAllowSprint = CarpetSettings.fakePlayerNavAllowSprint;
        BotSettings.fakePlayerNavMobAvoidance = CarpetSettings.fakePlayerNavMobAvoidance;
        BotSettings.fakePlayerNavMobAvoidanceRadius = CarpetSettings.fakePlayerNavMobAvoidanceRadius;
        BotSettings.fakePlayerNavMaxFallHeight = CarpetSettings.fakePlayerNavMaxFallHeight;
        BotSettings.fakePlayerNavAvoidSoulSand = CarpetSettings.fakePlayerNavAvoidSoulSand;
        BotSettings.fakePlayerNavAllowOpenDoors = CarpetSettings.fakePlayerNavAllowOpenDoors;
        BotSettings.fakePlayerNavAllowOpenFenceGates = CarpetSettings.fakePlayerNavAllowOpenFenceGates;
        BotSettings.fakePlayerNavAllowSwimming = CarpetSettings.fakePlayerNavAllowSwimming;
        BotSettings.fakePlayerNavSearchBudget = CarpetSettings.fakePlayerNavSearchBudget;
        BotSettings.fakePlayerNavSearchBudgetTotal = CarpetSettings.fakePlayerNavSearchBudgetTotal;

        BotSettings.spamClickCombat = CarpetSettings.spamClickCombat;
        BotSettings.botCombat = CarpetSettings.botCombat;
        BotSettings.botAutoTarget = CarpetSettings.botAutoTarget;
        BotSettings.botTargetPlayers = CarpetSettings.botTargetPlayers;
        BotSettings.botTargetMobs = CarpetSettings.botTargetMobs;
        BotSettings.botTargetBots = CarpetSettings.botTargetBots;
        BotSettings.botRevenge = CarpetSettings.botRevenge;
        BotSettings.botTargetRange = CarpetSettings.botTargetRange;
        BotSettings.botRetreatHealth = CarpetSettings.botRetreatHealth;
        BotSettings.botAutoTotem = CarpetSettings.botAutoTotem;
        BotSettings.botAutoShield = CarpetSettings.botAutoShield;
        BotSettings.botAutoFood = CarpetSettings.botAutoFood;
        BotSettings.botAutoPotion = CarpetSettings.botAutoPotion;
        BotSettings.botAutoArmor = CarpetSettings.botAutoArmor;
        BotSettings.botAutoWeapon = CarpetSettings.botAutoWeapon;
        BotSettings.botAutoRepair = CarpetSettings.botAutoRepair;
        BotSettings.botCombatStyle = CarpetSettings.botCombatStyle;
        BotSettings.botDifficulty = CarpetSettings.botDifficulty;
        BotSettings.botPreferSword = CarpetSettings.botPreferSword;
        BotSettings.botShieldBreak = CarpetSettings.botShieldBreak;
        BotSettings.botCritical = CarpetSettings.botCritical;
        BotSettings.botStrafe = CarpetSettings.botStrafe;
        BotSettings.botBhop = CarpetSettings.botBhop;
        BotSettings.botWTap = CarpetSettings.botWTap;
        BotSettings.botShieldPlay = CarpetSettings.botShieldPlay;
        BotSettings.botMeleeRange = CarpetSettings.botMeleeRange;
        BotSettings.botAttackCooldown = CarpetSettings.botAttackCooldown;
        BotSettings.botMissChance = CarpetSettings.botMissChance;
        BotSettings.botMistakeChance = CarpetSettings.botMistakeChance;
        BotSettings.botReactionDelay = CarpetSettings.botReactionDelay;
        BotSettings.botSkill = CarpetSettings.botSkill;
        BotSettings.botPingTicks = CarpetSettings.botPingTicks;
        BotSettings.botClicksPerSecond = CarpetSettings.botClicksPerSecond;
        BotSettings.botPlannerRange = CarpetSettings.botPlannerRange;
        BotSettings.botPlannerHorizon = CarpetSettings.botPlannerHorizon;
        BotSettings.botPlannerPopulation = CarpetSettings.botPlannerPopulation;
        BotSettings.botSimBudget = CarpetSettings.botSimBudget;
    }
}
