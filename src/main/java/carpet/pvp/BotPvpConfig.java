package carpet.pvp;

import java.util.Locale;
import java.util.UUID;

/**
 * Per-bot PvP combat-AI configuration for a single {@link carpet.patches.EntityPlayerMPFake}.
 *
 * <p>Every field is seeded from the matching global {@code /carpet} rule (see
 * {@link BotSettings}) when the bot spawns, and may be overridden per-bot through
 * {@code /player <name> ai <setting> <value>}. Global rules therefore act as the default,
 * while per-bot overrides win.</p>
 */
public final class BotPvpConfig
{
    /** Combat styles a bot can use against its target. */
    public enum CombatStyle { MELEE, CRYSTAL, ANCHOR, RANGED, MACE, SMP }

    /** Options only one combat style reads, by the names {@link carpet.pvp.style.StyleIndex#options()} declares. */
    private final java.util.Map<String, String> styleOptions = new java.util.HashMap<>();

    /** Skill presets; {@link #difficulty} picks the one a bot starts from. */
    public enum Difficulty { BEGINNER, CASUAL, AVERAGE, SKILLED, EXPERT }

    /** One named set of skill, pace and technique settings. */
    private record Preset(double skill, int reactionDelay, int pingTicks, double clicksPerSecond,
                           boolean critical, boolean strafe, boolean wtap, boolean shieldPlay,
                           int horizon, int population)
    {
        Preset
        {
            skill = clampD(skill, 0.0, 1.0);
            reactionDelay = clampI(reactionDelay, 0, 40);
            pingTicks = clampI(pingTicks, 0, 20);
            clicksPerSecond = clampD(clicksPerSecond, 1.0, 20.0);
            horizon = clampI(horizon, 2, 40);
            population = clampI(population, 2, 64);
        }
    }

    private static Preset presetOf(Difficulty difficulty)
    {
        return switch (difficulty)
        {
            case BEGINNER -> new Preset(0.15, 9, 2, 6.0, false, false, false, true, 8, 6);
            case CASUAL -> new Preset(0.35, 7, 1, 8.0, true, false, false, true, 10, 8);
            case AVERAGE -> new Preset(0.60, 5, 1, 10.0, true, true, false, true, 12, 10);
            case SKILLED -> new Preset(0.80, 3, 0, 12.0, true, true, true, true, 14, 12);
            case EXPERT -> new Preset(1.00, 2, 0, 14.0, true, true, true, true, 16, 16);
        };
    }

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
    public Difficulty difficulty;
    public boolean preferSword;
    public boolean shieldBreak;
    public boolean critical;
    public boolean strafe;
    public boolean bhop;
    public boolean wtap;
    public boolean shieldPlay;
    public double meleeRange;
    public int attackCooldown;

    // --- human model ---
    /** Skill from 0 (beginner) to 1 (expert); drives the look profile and the presets. */
    public double skill;
    /** Ticks between seeing the target and reacting to it. */
    public int reactionDelay;
    /** Ticks the target's state is behind, as if it were on a laggy connection. */
    public int pingTicks;
    /** Mouse clicks per second. */
    public double clicksPerSecond;
    /** Blocks from the target within which the navigation controller takes over. */
    public double plannerRange;

    // --- planner ---
    public int plannerHorizon;
    public int plannerPopulation;

    // --- realism ---
    public int missChance;     // 0-100
    public int mistakeChance;  // 0-100

    // --- faction membership (null = no faction) ---
    public String faction;

    /**
     * Builds a config snapshotting the current global {@code /carpet} rule values.
     */
    public BotPvpConfig()
    {
        combat        = BotSettings.botCombat;
        autoTarget    = BotSettings.botAutoTarget;
        targetPlayers = BotSettings.botTargetPlayers;
        targetMobs    = BotSettings.botTargetMobs;
        targetBots    = BotSettings.botTargetBots;
        revenge       = BotSettings.botRevenge;
        targetRange   = BotSettings.botTargetRange;
        retreatHealth = BotSettings.botRetreatHealth;

        autoTotem  = BotSettings.botAutoTotem;
        autoShield = BotSettings.botAutoShield;
        autoFood   = BotSettings.botAutoFood;
        autoPotion = BotSettings.botAutoPotion;
        autoArmor  = BotSettings.botAutoArmor;
        autoWeapon = BotSettings.botAutoWeapon;
        autoRepair = BotSettings.botAutoRepair;

        combatStyle    = parseStyle(BotSettings.botCombatStyle);
        difficulty     = parseDifficulty(BotSettings.botDifficulty);
        preferSword    = BotSettings.botPreferSword;
        shieldBreak    = BotSettings.botShieldBreak;
        critical       = BotSettings.botCritical;
        strafe         = BotSettings.botStrafe;
        bhop           = BotSettings.botBhop;
        wtap           = BotSettings.botWTap;
        shieldPlay     = BotSettings.botShieldPlay;
        meleeRange     = BotSettings.botMeleeRange;
        attackCooldown = BotSettings.botAttackCooldown;

        skill          = BotSettings.botSkill;
        reactionDelay  = BotSettings.botReactionDelay;
        pingTicks      = BotSettings.botPingTicks;
        clicksPerSecond = BotSettings.botClicksPerSecond;
        plannerRange   = BotSettings.botPlannerRange;
        plannerHorizon = BotSettings.botPlannerHorizon;
        plannerPopulation = BotSettings.botPlannerPopulation;

        missChance    = BotSettings.botMissChance;
        mistakeChance = BotSettings.botMistakeChance;

        faction = null;
    }

    private static CombatStyle parseStyle(String s)
    {
        try { return styleOf(s); }
        catch (IllegalArgumentException e) { return CombatStyle.MELEE; }
    }

    /** A combat style by name; "sword" is the melee style. Throws IllegalArgumentException for an unknown name. */
    public static CombatStyle styleOf(String name)
    {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.equals("SWORD") ? CombatStyle.MELEE : CombatStyle.valueOf(upper);
    }

    /** The value of a style option, or the default the style declared for it. */
    public String option(String key)
    {
        String value = styleOptions.get(key);
        return value != null ? value : carpet.pvp.style.StyleIndex.options().get(key);
    }

    public boolean flag(String key)
    {
        return Boolean.parseBoolean(option(key));
    }

    public double number(String key)
    {
        return Double.parseDouble(option(key));
    }

    /** Every setting name /bot option and /player ai accept: the common ones and the style options. */
    public static String[] keys()
    {
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of(KEYS));
        all.addAll(carpet.pvp.style.StyleIndex.options().keySet());
        return all.toArray(new String[0]);
    }

    private static Difficulty parseDifficulty(String s)
    {
        try { return Difficulty.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return Difficulty.AVERAGE; }
    }

    /** The named difficulty presets, for tab-completion. */
    public static String[] difficulties()
    {
        Difficulty[] values = Difficulty.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++)
        {
            names[i] = values[i].name().toLowerCase(Locale.ROOT);
        }
        return names;
    }

    /**
     * Applies a named difficulty preset: the skill, the pace and the techniques of that preset
     * replace the current values, everything else is left alone.
     *
     * @return the error string, or null on success
     */
    public String applyDifficulty(String name)
    {
        Difficulty wanted;
        try
        {
            wanted = Difficulty.valueOf(name.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            return "Unknown difficulty: " + name;
        }
        Preset preset = presetOf(wanted);
        difficulty = wanted;
        skill = preset.skill();
        reactionDelay = preset.reactionDelay();
        pingTicks = preset.pingTicks();
        clicksPerSecond = preset.clicksPerSecond();
        critical = preset.critical();
        strafe = preset.strafe();
        wtap = preset.wtap();
        shieldPlay = preset.shieldPlay();
        plannerHorizon = preset.horizon();
        plannerPopulation = preset.population();
        return null;
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
            switch (key.toLowerCase(Locale.ROOT))
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

                case "combatstyle"    -> combatStyle = styleOf(value);
                case "difficulty"     -> {
                    String error = applyDifficulty(value);
                    if (error != null) return error;
                }
                case "prefersword"    -> preferSword = parseBool(value);
                case "shieldbreak"    -> shieldBreak = parseBool(value);
                case "critical"       -> critical = parseBool(value);
                case "strafe"         -> strafe = parseBool(value);
                case "bhop"           -> bhop = parseBool(value);
                case "wtap"           -> wtap = parseBool(value);
                case "shieldplay"     -> shieldPlay = parseBool(value);
                case "meleerange"     -> meleeRange = clampD(Double.parseDouble(value), 2.0, 6.0);
                case "attackcooldown" -> attackCooldown = clampI(Integer.parseInt(value), 0, 40);

                case "skill"           -> skill = clampD(Double.parseDouble(value), 0.0, 1.0);
                case "reactiondelay"   -> reactionDelay = clampI(Integer.parseInt(value), 0, 40);
                case "pingticks"       -> pingTicks = clampI(Integer.parseInt(value), 0, 20);
                case "clickspersecond" -> clicksPerSecond = clampD(Double.parseDouble(value), 1.0, 20.0);
                case "plannerrange"    -> plannerRange = clampD(Double.parseDouble(value), 2.0, 16.0);
                case "plannerhorizon"  -> plannerHorizon = clampI(Integer.parseInt(value), 2, 40);
                case "plannerpopulation" -> plannerPopulation = clampI(Integer.parseInt(value), 2, 64);

                case "misschance"    -> missChance = clampI(Integer.parseInt(value), 0, 100);
                case "mistakechance" -> mistakeChance = clampI(Integer.parseInt(value), 0, 100);

                case "faction" -> faction = value.isEmpty() || value.equalsIgnoreCase("none") ? null : value;

                default -> {
                    String option = key.toLowerCase(Locale.ROOT);
                    String standard = carpet.pvp.style.StyleIndex.options().get(option);
                    if (standard == null) return "Unknown setting: " + key;
                    // a style option keeps the type of its default: a number stays a number, a switch a switch
                    if (standard.equals("true") || standard.equals("false")) value = String.valueOf(parseBool(value));
                    else if (isNumber(standard)) value = String.valueOf(Double.parseDouble(value));
                    styleOptions.put(option, value);
                }
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
                + " | style=" + combatStyle + " difficulty=" + difficulty
                + " preferSword=" + preferSword + " shieldBreak=" + shieldBreak
                + " crit=" + critical + " strafe=" + strafe + " bhop=" + bhop
                + " wtap=" + wtap + " shieldPlay=" + shieldPlay
                + " meleeRange=" + meleeRange + " atkCd=" + attackCooldown
                + " | human[skill=" + skill + ",reaction=" + reactionDelay + ",ping=" + pingTicks
                + ",clicks/s=" + clicksPerSecond + ",plannerRange=" + plannerRange + "]"
                + " planner[horizon=" + plannerHorizon + ",population=" + plannerPopulation + "]"
                + " | realism[miss=" + missChance + ",mistake=" + mistakeChance + "]"
                + " | faction=" + (faction == null ? "none" : faction)
                + (styleOptions.isEmpty() ? "" : " | style options " + new java.util.TreeMap<>(styleOptions));
    }

    private static boolean isNumber(String v)
    {
        try { Double.parseDouble(v); return true; }
        catch (NumberFormatException e) { return false; }
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
            "combatstyle", "difficulty", "prefersword", "shieldbreak", "critical", "strafe", "bhop",
            "wtap", "shieldplay", "meleerange", "attackcooldown",
            "skill", "reactiondelay", "pingticks", "clickspersecond", "plannerrange",
            "plannerhorizon", "plannerpopulation",
            "misschance", "mistakechance", "faction"
    };

    /** Default mouse sensitivity the bot's look profile is built for. */
    public static final float SENSITIVITY = 0.5F;
}