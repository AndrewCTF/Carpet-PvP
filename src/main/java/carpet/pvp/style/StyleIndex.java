package carpet.pvp.style;

import carpet.patches.EntityPlayerMPFake;
import carpet.pvp.BotBody;
import carpet.pvp.BotPvpConfig;
import carpet.pvp.BotPvpConfig.CombatStyle;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * What each combat style is made of: the class that fights, the kit a bot spawned with that style is given, and
 * the options only that style reads. Every entry is one line at the end of the block below; .gitattributes merges
 * this file by union, so branches that each add a style do not conflict.
 */
public final class StyleIndex
{
    public interface Factory
    {
        BotStyle create(EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random);
    }

    private static final Map<CombatStyle, Factory> STYLES = new EnumMap<>(CombatStyle.class);
    private static final Map<CombatStyle, String> KITS = new EnumMap<>(CombatStyle.class);
    /** Style options and their defaults, named {@code <style>.<option>} in lower case, as /bot option takes them. */
    private static final Map<String, String> OPTIONS = new LinkedHashMap<>();

    private StyleIndex() {}

    static
    {
        STYLES.put(CombatStyle.MELEE, SwordStyle::new);
        KITS.put(CombatStyle.MELEE, "sword");
        KITS.put(CombatStyle.SMP, "smp");
        KITS.put(CombatStyle.MACE, "mace");
        KITS.put(CombatStyle.CRYSTAL, "crystal");
        KITS.put(CombatStyle.ANCHOR, "crystal");
        STYLES.put(CombatStyle.MACE, MaceStyle::new);
        OPTIONS.put("mace.windcharge", "true");
        OPTIONS.put("mace.chain", "true");
        OPTIONS.put("mace.pearl", "true");
        OPTIONS.put("mace.elytra", "true");
        OPTIONS.put("mace.stunslam", "true");
        OPTIONS.put("mace.enchants", "true");
        OPTIONS.put("mace.bounce", "true");
        OPTIONS.put("mace.safeland", "true");
        OPTIONS.put("mace.swap", "true");
    }

    public static boolean has(CombatStyle style)
    {
        return STYLES.containsKey(style);
    }

    /** The style's own implementation, or the sword's while it has none. */
    public static BotStyle create(CombatStyle style, EntityPlayerMPFake bot, BotBody body, BotPvpConfig cfg, Random random)
    {
        return STYLES.getOrDefault(style, STYLES.get(CombatStyle.MELEE)).create(bot, body, cfg, random);
    }

    /** The built-in kit a bot spawned with this style is given, or null. */
    public static String kit(CombatStyle style)
    {
        return KITS.get(style);
    }

    public static Map<String, String> options()
    {
        return OPTIONS;
    }
}
