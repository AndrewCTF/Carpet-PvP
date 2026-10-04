package carpet.pvp;

/**
 * Every rule value the shared bot code reads, in one place and with no annotations, so that the same
 * code can be built against a mod loader's settings (on Fabric, filled from
 * {@code CarpetSettings} after the configuration file is read and again whenever a rule changes) or
 * against a plugin's own {@code config.yml} (on Paper).
 *
 * <p>The fields hold the rule defaults, so reading them before anything filled them in is safe. The
 * two that name an enum keep the rule's spelling: {@link BotPvpConfig} parses them where it uses
 * them.</p>
 */
public final class BotSettings
{
    // fake player rules
    public static boolean allowSpawningOfflinePlayers = true;
    public static boolean fakePlayerDropInventoryOnDeath = false;
    public static boolean allowListingFakePlayers = false;
    public static boolean fakePlayerFallDamage = true;
    public static boolean shieldStunning = false;
    public static double swordBlockDamageMultiplier = 0.5D;

    // navigation rules
    public static boolean fakePlayerNavigation = true;
    public static boolean fakePlayerElytraGlide = false;
    public static boolean fakePlayerNavBreakBlocks = false;
    public static boolean fakePlayerNavPlaceBlocks = false;
    public static boolean fakePlayerNavAutoTool = true;
    public static boolean fakePlayerNavAutoEat = true;
    public static int fakePlayerNavAutoEatBelow = 10;
    public static boolean fakePlayerNavAvoidLava = true;
    public static boolean fakePlayerNavAvoidFire = true;
    public static boolean fakePlayerNavAvoidCobwebs = true;
    public static boolean fakePlayerNavBreakCobwebs = true;
    public static boolean fakePlayerNavAvoidPowderSnow = true;
    public static boolean fakePlayerNavAllowParkour = true;
    public static boolean fakePlayerNavAllowPillar = false;
    public static boolean fakePlayerNavAllowBreakThrough = false;
    public static boolean fakePlayerNavAllowDescendMine = false;
    public static boolean fakePlayerNavAllowSprint = true;
    public static boolean fakePlayerNavMobAvoidance = false;
    public static int fakePlayerNavMobAvoidanceRadius = 8;
    public static int fakePlayerNavMaxFallHeight = 4;
    public static boolean fakePlayerNavAvoidSoulSand = false;
    public static boolean fakePlayerNavAllowOpenDoors = true;
    public static boolean fakePlayerNavAllowOpenFenceGates = true;
    public static boolean fakePlayerNavAllowSwimming = false;
    public static int fakePlayerNavSearchBudget = 1500;
    public static int fakePlayerNavSearchBudgetTotal = 12000;

    // combat rules
    public static boolean spamClickCombat = false;
    public static boolean botCombat = false;
    public static boolean botAutoTarget = true;
    public static boolean botTargetPlayers = true;
    public static boolean botTargetMobs = false;
    public static boolean botTargetBots = true;
    public static boolean botRevenge = true;
    public static double botTargetRange = 16.0D;
    public static int botRetreatHealth = 0;
    public static boolean botAutoTotem = true;
    public static boolean botAutoShield = false;
    public static boolean botAutoFood = true;
    public static boolean botAutoWeapon = false;
    public static String botCombatStyle = "MELEE";
    public static String botDifficulty = "AVERAGE";
    public static boolean botPreferSword = true;
    public static boolean botShieldBreak = false;
    public static boolean botCritical = true;
    public static boolean botStrafe = true;
    public static boolean botBhop = false;
    public static boolean botWTap = true;
    public static boolean botShieldPlay = true;
    public static double botMeleeRange = 3.0D;
    public static int botAttackCooldown = 0;
    public static int botMissChance = 0;
    public static int botMistakeChance = 0;
    public static int botReactionDelay = 0;
    public static double botSkill = 0.6D;
    public static int botPingTicks = 1;
    public static double botClicksPerSecond = 10.0D;
    public static double botPlannerRange = 8.0D;
    public static int botPlannerHorizon = 12;
    public static int botPlannerPopulation = 10;
    public static int botSimBudget = 20000;

    private BotSettings() {}
}
