package carpet.pvp;

import carpet.CarpetSettings;

import java.util.UUID;

/**
 * Per-bot PvP combat-AI configuration for a single {@link carpet.patches.EntityPlayerMPFake}.
 *
 * <p>Every field is seeded from the matching global {@code /carpet} rule (see
 * {@link CarpetSettings}) when the bot spawns, and may be overridden per-bot through
 * {@code /player <name> ai <setting> <value>}. Global rules therefore act as the default,
 * while per-bot overrides win.</p>
 */
public final class BotPvpConfig
{
    /** Combat styles a bot can use against its target. */
    public enum CombatStyle { MELEE, CRYSTAL, ANCHOR, RANGED, MACE }

    // --- master / targeting ---
    public boolean combat;
    public boolean autoTarget;
    public boolean targetPlayers;
    public boolean targetMobs;
    public boolean targetBots;
    public boolean revenge;
    public double targetRange;
    public int retreatHealth;

    // --- survival auto-utils ---
    public boolean autoTotem;
    public boolean autoShield;
    public boolean autoFood;
    public boolean autoPotion;
    public boolean autoArmor;
    public boolean autoWeapon;
    public boolean autoRepair;

    // --- combat tactics ---
    public CombatStyle combatStyle;
    public boolean preferSword;
    public boolean shieldBreak;
    public boolean critical;
    public boolean strafe;
    public boolean bhop;
    public double meleeRange;
    public int attackCooldown;

    // --- realism ---
    public int missChance;     // 0-100
    public int mistakeChance;  // 0-100
    public int reactionDelay;  // ticks

    // --- faction membership (null = no faction) ---
    public String faction;

    /**
     * Builds a config snapshotting the current global {@code /carpet} rule values.
     */
    public BotPvpConfig()
    {
        combat        = CarpetSettings.botCombat;
        autoTarget    = CarpetSettings.botAutoTarget;
        targetPlayers = CarpetSettings.botTargetPlayers;
        targetMobs    = CarpetSettings.botTargetMobs;
        targetBots    = CarpetSettings.botTargetBots;
        revenge       = CarpetSettings.botRevenge;
        targetRange   = CarpetSettings.botTargetRange;
        retreatHealth = CarpetSettings.botRetreatHealth;

        autoTotem  = CarpetSettings.botAutoTotem;
        autoShield = CarpetSettings.botAutoShield;
        autoFood   = CarpetSettings.botAutoFood;
        autoPotion = CarpetSettings.botAutoPotion;
        autoArmor  = CarpetSettings.botAutoArmor;
        autoWeapon = CarpetSettings.botAutoWeapon;
        autoRepair = CarpetSettings.botAutoRepair;

        combatStyle    = parseStyle(CarpetSettings.botCombatStyle);
        preferSword    = CarpetSettings.botPreferSword;
        shieldBreak    = CarpetSettings.botShieldBreak;
        critical       = CarpetSettings.botCritical;
        strafe         = CarpetSettings.botStrafe;
        bhop           = CarpetSettings.botBhop;
        meleeRange     = CarpetSettings.botMeleeRange;
        attackCooldown = CarpetSettings.botAttackCooldown;

        missChance    = CarpetSettings.botMissChance;
        mistakeChance = CarpetSettings.botMistakeChance;
        reactionDelay = CarpetSettings.botReactionDelay;

        faction = null;
    }

    private static CombatStyle parseStyle(String s)
    {
        try { return CombatStyle.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) { return CombatStyle.MELEE; }
    }

    /**
     * Applies a single setting by name. Returns {@code null} on success, otherwise an
     * error string describing the problem (unknown key or bad value). Used by
     * {@code /player <name> ai <setting> <value>}.
     */
    public String apply(String key, String value)
    {
        try
        {
            switch (key.toLowerCase())
            {
                case "combat"        -> combat = parseBool(value);
                case "autotarget"    -> autoTarget = parseBool(value);
                case "targetplayers" -> targetPlayers = parseBool(value);
                case "targetmobs"    -> targetMobs = parseBool(value);
                case "targetbots"    -> targetBots = parseBool(value);
                case "revenge"       -> revenge = parseBool(value);
                case "targetrange"   -> targetRange = clampD(Double.parseDouble(value), 2.0, 64.0);
                case "retreathealth" -> retreatHealth = clampI(Integer.parseInt(value), 0, 20);

                case "autototem"  -> autoTotem = parseBool(value);
                case "autoshield" -> autoShield = parseBool(value);
                case "autofood"   -> autoFood = parseBool(value);
                case "autopotion" -> autoPotion = parseBool(value);
                case "autoarmor"  -> autoArmor = parseBool(value);
                case "autoweapon" -> autoWeapon = parseBool(value);
                case "autorepair" -> autoRepair = parseBool(value);

                case "combatstyle"    -> combatStyle = CombatStyle.valueOf(value.toUpperCase());
                case "prefersword"    -> preferSword = parseBool(value);
                case "shieldbreak"    -> shieldBreak = parseBool(value);
                case "critical"       -> critical = parseBool(value);
                case "strafe"         -> strafe = parseBool(value);
                case "bhop"           -> bhop = parseBool(value);
                case "meleerange"     -> meleeRange = clampD(Double.parseDouble(value), 2.0, 6.0);
                case "attackcooldown" -> attackCooldown = clampI(Integer.parseInt(value), 0, 40);

                case "misschance"    -> missChance = clampI(Integer.parseInt(value), 0, 100);
                case "mistakechance" -> mistakeChance = clampI(Integer.parseInt(value), 0, 100);
                case "reactiondelay" -> reactionDelay = clampI(Integer.parseInt(value), 0, 40);

                case "faction" -> faction = value.isEmpty() || value.equalsIgnoreCase("none") ? null : value;

                default -> { return "Unknown setting: " + key; }
            }
        }
        catch (NumberFormatException e)
        {
            return "Invalid number for " + key + ": " + value;
        }
        catch (IllegalArgumentException e)
        {
            return "Invalid value for " + key + ": " + value;
        }
        return null;
    }

    public String describe()
    {
        return "combat=" + combat
                + " autoTarget=" + autoTarget
                + " targets[players=" + targetPlayers + ",mobs=" + targetMobs + ",bots=" + targetBots + "]"
                + " revenge=" + revenge
                + " range=" + targetRange + " retreatHP=" + retreatHealth
                + " | auto[totem=" + autoTotem + ",shield=" + autoShield + ",food=" + autoFood
                + ",potion=" + autoPotion + ",armor=" + autoArmor + ",weapon=" + autoWeapon + ",repair=" + autoRepair + "]"
                + " | style=" + combatStyle + " preferSword=" + preferSword + " shieldBreak=" + shieldBreak
                + " crit=" + critical + " strafe=" + strafe + " bhop=" + bhop
                + " meleeRange=" + meleeRange + " atkCd=" + attackCooldown
                + " | realism[miss=" + missChance + ",mistake=" + mistakeChance + ",reaction=" + reactionDelay + "]"
                + " | faction=" + (faction == null ? "none" : faction);
    }

    private static boolean parseBool(String v)
    {
        if (v.equalsIgnoreCase("true") || v.equals("1")) return true;
        if (v.equalsIgnoreCase("false") || v.equals("0")) return false;
        throw new IllegalArgumentException("expected true/false");
    }

    private static int clampI(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Setting names exposed via {@code /player <name> ai}, for tab-completion. */
    public static final String[] KEYS = {
            "combat", "autotarget", "targetplayers", "targetmobs", "targetbots", "revenge",
            "targetrange", "retreathealth",
            "autototem", "autoshield", "autofood", "autopotion", "autoarmor", "autoweapon", "autorepair",
            "combatstyle", "prefersword", "shieldbreak", "critical", "strafe", "bhop",
            "meleerange", "attackcooldown",
            "misschance", "mistakechance", "reactiondelay", "faction"
    };
}
