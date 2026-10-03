package carpet.pvp.kit;

/**
 * An enchantment a kit entry is built with, at the level it is built at.
 */
public record KitEnchantment(String id, int level)
{
    public KitEnchantment
    {
        if (level < 1) throw new IllegalArgumentException("enchantment level must be at least 1: " + id + " " + level);
    }
}