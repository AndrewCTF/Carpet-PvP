package carpet.pvp.gui;

import carpet.pvp.BotPvpConfig;
import carpet.pvp.style.StyleIndex;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the bot page of the menu makes of a bot's settings: the kind of item each setting gets, how
 * much of the page it takes and how far its two buttons move it.
 *
 * <p>The list of settings is {@link BotPvpConfig#KEYS} without the three the header of the page holds
 * itself, followed by the style options {@link StyleIndex#options()} declares. What kind of button a
 * setting gets is read off the config field its name belongs to, so a setting added to the config
 * shows up in the menu without anything being listed here.</p>
 */
public final class BotOptionLayout
{
    /** What a setting's button does. */
    public enum Kind { TOGGLE, NUMBER, TEXT, STYLE, DIFFICULTY }

    /** What one of the slots an option takes does. */
    public enum Role { MINUS, VALUE, PLUS }

    /** One setting, as the menu needs to know it. */
    public record Option(String key, Kind kind, double step)
    {
        /** How many chest slots the setting takes: a number wants a minus and a plus next to it. */
        public int width()
        {
            return kind == Kind.NUMBER ? 3 : 1;
        }

        public boolean numeric()
        {
            return kind == Kind.NUMBER;
        }
    }

    /** One slot of the option area: which setting it belongs to and what pressing it does. */
    public record Entry(int slot, Option option, Role role) {}

    /** The first and the last chest slot the option area of the bot page uses. */
    public static final int FIRST_SLOT = 18;
    public static final int LAST_SLOT = 53;
    private static final int WIDTH = LAST_SLOT - FIRST_SLOT + 1;

    /** Marks the choice that is made on a menu that offers several of them. */
    public static final String CHONEN = "» ";

    /** The settings the header of the page holds itself, so they are not listed twice. */
    private static final Set<String> HEADER = Set.of("combatstyle", "difficulty", "faction");

    /** How far the two buttons of a setting move it. Everything else moves a step at a time. */
    private static final Map<String, Double> STEPS = Map.of(
            "skill", 0.05D,
            "clickspersecond", 0.5D,
            "targetrange", 0.5D,
            "meleerange", 0.05D,
            "plannerrange", 0.5D);

    /** What a setting is called in the menu, and what its lore line says it does. */
    private record Note(String title, String text) {}

    private static final Map<String, Note> NOTES = notes();

    /** The kind of every config field, by the lower case name of the field. */
    private static final Map<String, Kind> KINDS = kindsOf();

    /** Every non-static config field, by the lower case name of the field. */
    private static final Map<String, Field> FIELDS = fieldsOf();

    private static List<Option> options;

    private BotOptionLayout() {}

    private static Map<String, Note> notes()
    {
        Map<String, Note> notes = new TreeMap<>();
        notes.put("combat", note("Fighting", "Whether the bot goes looking for someone to fight."));
        notes.put("autotarget", note("Auto target", "Look for a target on its own, without being told to."));
        notes.put("targetplayers", note("Target players", "May attack real players."));
        notes.put("targetmobs", note("Target mobs", "May attack hostile mobs."));
        notes.put("targetbots", note("Target bots", "May attack other bots."));
        notes.put("revenge", note("Revenge", "Fight back whoever hit it in the last few seconds."));
        notes.put("targetrange", note("Target range", "How far away it looks for someone to fight."));
        notes.put("retreathealth", note("Retreat health", "Below this much health it breaks the fight off."));
        notes.put("autototem", note("Auto totem", "Keeps a totem of undying in its off hand."));
        notes.put("autoshield", note("Auto shield", "Raises a shield once it is hurt."));
        notes.put("autofood", note("Auto food", "Eats when it gets hungry."));
        notes.put("autoweapon", note("Auto weapon", "Swaps to the better weapon it picks up."));
        notes.put("prefersword", note("Prefer sword", "Hold a sword when it carries one."));
        notes.put("shieldbreak", note("Shield break", "Hit with an axe when the target is blocking."));
        notes.put("critical", note("Crits", "Time its swings to land as critical hits."));
        notes.put("strafe", note("Strafing", "Circle the target while fighting."));
        notes.put("bhop", note("Bunny hop", "Jump while it runs."));
        notes.put("wtap", note("W-tap", "Let go of sprint for a tick after every hit."));
        notes.put("shieldplay", note("Shield play", "Raise the shield when the target winds up."));
        notes.put("meleerange", note("Melee range", "How close it tries to stay to its target."));
        notes.put("attackcooldown", note("Attack cooldown", "Ticks to wait between two swings."));
        notes.put("skill", note("Skill", "How good it is, from 0 to 1. Sets how fast it turns and how fast it reacts."));
        notes.put("reactiondelay", note("Reaction delay", "Ticks between seeing the target and reacting to it."));
        notes.put("pingticks", note("Ping", "Ticks its view of the target lags behind, as on a slow connection."));
        notes.put("clickspersecond", note("Clicks per second", "How fast its mouse hand moves."));
        notes.put("plannerrange", note("Planner range", "How far away it starts planning the fight."));
        notes.put("plannerhorizon", note("Planner horizon", "Ticks ahead it plans the fight for."));
        notes.put("plannerpopulation", note("Planner population", "How many different futures it tries per plan."));
        notes.put("misschance", note("Miss chance", "Percent of swings that deliberately miss."));
        notes.put("mistakechance", note("Mistake chance", "Percent of plans that are wrong on purpose."));
        return notes;
    }

    private static Note note(String title, String text)
    {
        return new Note(title, text);
    }

    /** Every setting the option area of the bot page shows, in the order the page lays them out. */
    public static synchronized List<Option> options()
    {
        if (options == null)
        {
            List<Option> found = new ArrayList<>();
            for (String key : BotPvpConfig.KEYS)
            {
                if (!HEADER.contains(key)) found.add(new Option(key, kindOf(key), stepOf(key)));
            }
            // A style option keeps the type of its declared default, the same rule BotPvpConfig.apply uses.
            for (Map.Entry<String, String> option : StyleIndex.options().entrySet())
            {
                found.add(new Option(option.getKey(), kindOfDefault(option.getValue()), 1.0D));
            }
            options = List.copyOf(found);
        }
        return options;
    }

    /** The option area split into pages of whole settings. */
    public static List<List<Entry>> allPages()
    {
        List<List<Entry>> pages = new ArrayList<>();
        List<Entry> page = new ArrayList<>();
        int used = 0;
        for (Option option : options())
        {
            if (used + option.width() > WIDTH)
            {
                pages.add(page);
                page = new ArrayList<>();
                used = 0;
            }
            int slot = FIRST_SLOT + used;
            if (option.numeric())
            {
                page.add(new Entry(slot, option, Role.MINUS));
                page.add(new Entry(slot + 1, option, Role.VALUE));
                page.add(new Entry(slot + 2, option, Role.PLUS));
            }
            else
            {
                page.add(new Entry(slot, option, Role.VALUE));
            }
            used += option.width();
        }
        if (!page.isEmpty()) pages.add(page);
        return pages;
    }

    /** How many pages of settings there are; always at least one. */
    public static int pageCount()
    {
        return allPages().size();
    }

    /** The slots of one page of settings, empty for a page that does not exist. */
    public static List<Entry> page(int index)
    {
        List<List<Entry>> pages = allPages();
        return index >= 0 && index < pages.size() ? List.copyOf(pages.get(index)) : List.of();
    }

    /** What kind of button a setting gets, from the config field its name belongs to. */
    public static Kind kindOf(String key)
    {
        Kind kind = KINDS.get(key.toLowerCase(Locale.ROOT));
        if (kind != null) return kind;
        String standard = StyleIndex.options().get(key.toLowerCase(Locale.ROOT));
        return standard == null ? Kind.TEXT : kindOfDefault(standard);
    }

    private static Kind kindOfDefault(String standard)
    {
        if (standard.equals("true") || standard.equals("false")) return Kind.TOGGLE;
        return isNumber(standard) ? Kind.NUMBER : Kind.TEXT;
    }

    /** How far a setting's two buttons move it. */
    public static double stepOf(String key)
    {
        return STEPS.getOrDefault(key, 1.0D);
    }

    /** What the setting is called on its button. */
    public static String title(String key)
    {
        Note note = NOTES.get(key);
        if (note != null) return note.title();
        int dot = key.indexOf('.');
        if (dot < 0) return key;
        // A style option belongs to one style, so it is called by that style and its own words:
        // ranged.bow reads as "Ranged: bow" and crystal.retotem_delay as "Crystal: retotem delay".
        String style = BotPvpConfig.CombatStyle.valueOf(key.substring(0, dot).toUpperCase(Locale.ROOT)).name();
        return style + ": " + key.substring(dot + 1).replace('_', ' ');
    }

    /** The lore line that explains what the setting does. */
    public static String text(String key)
    {
        Note note = NOTES.get(key);
        return note == null ? "Set with /bot option <bot> " + key + " <value>." : note.text();
    }

    /** The value of a setting as the menu writes it, or null when the config holds no such setting. */
    public static String value(BotPvpConfig cfg, String key)
    {
        Field field = fieldOf(key);
        return field != null ? String.valueOf(get(cfg, field)) : cfg.option(key);
    }

    /** The value of a number setting. */
    public static double number(BotPvpConfig cfg, String key)
    {
        Field field = fieldOf(key);
        Object value = field != null ? get(cfg, field) : cfg.option(key);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    /** The value of a switch. */
    public static boolean flag(BotPvpConfig cfg, String key)
    {
        Field field = fieldOf(key);
        Object value = field != null ? get(cfg, field) : cfg.option(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    /** A number as the menu writes it: whole numbers without a fraction, the rest to two places. */
    public static String format(double value)
    {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E9D) return Long.toString((long) value);
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** The field of the config a setting name belongs to, or null when the setting is a style option. */
    private static Field fieldOf(String key)
    {
        return FIELDS.get(key.toLowerCase(Locale.ROOT));
    }

    private static Object get(BotPvpConfig cfg, Field field)
    {
        try
        {
            return field.get(cfg);
        }
        catch (IllegalAccessException e)
        {
            throw new IllegalStateException("cannot read " + field.getName(), e);
        }
    }

    private static Map<String, Field> fieldsOf()
    {
        Map<String, Field> fields = new HashMap<>();
        for (Field field : BotPvpConfig.class.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers())) continue;
            fields.put(field.getName().toLowerCase(Locale.ROOT), field);
        }
        return Map.copyOf(fields);
    }

    private static Map<String, Kind> kindsOf()
    {
        Map<String, Kind> kinds = new HashMap<>();
        for (Field field : BotPvpConfig.class.getDeclaredFields())
        {
            if (Modifier.isStatic(field.getModifiers())) continue;
            kinds.put(field.getName().toLowerCase(Locale.ROOT), kindOf(field.getType()));
        }
        return Map.copyOf(kinds);
    }

    private static Kind kindOf(Class<?> type)
    {
        if (type == boolean.class) return Kind.TOGGLE;
        if (type == int.class || type == double.class) return Kind.NUMBER;
        if (type == BotPvpConfig.CombatStyle.class) return Kind.STYLE;
        if (type == BotPvpConfig.Difficulty.class) return Kind.DIFFICULTY;
        return Kind.TEXT;
    }

    private static boolean isNumber(String value)
    {
        try
        {
            Double.parseDouble(value);
            return true;
        }
        catch (NumberFormatException notANumber)
        {
            return false;
        }
    }
}