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
        STYLES.put(CombatStyle.CRYSTAL, CrystalStyle::new);
        STYLES.put(CombatStyle.ANCHOR, CrystalStyle::new);
        KITS.put(CombatStyle.CRYSTAL, "crystal");
        KITS.put(CombatStyle.ANCHOR, "crystal");
        OPTIONS.put("crystal.crystals", "true");
        OPTIONS.put("crystal.obsidian", "true");
        OPTIONS.put("crystal.anchors", "true");
        OPTIONS.put("crystal.pearls", "true");
        OPTIONS.put("crystal.doubletap", "true");
        OPTIONS.put("crystal.hand_totem", "true");
        OPTIONS.put("crystal.retotem", "true");
        OPTIONS.put("crystal.retotem_delay", "20");
        OPTIONS.put("crystal.reach", "4.0");
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
