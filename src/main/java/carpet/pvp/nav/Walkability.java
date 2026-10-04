package carpet.pvp.nav;

/**
 * The three questions navigation asks about a block, with no Minecraft types in the way so that the
 * smoothing, steering and flow field code can be reasoned about (and tested) on their own.
 *
 * Block coordinates are foot positions: {@code y} is the block the player's feet are in, the head is at
 * {@code y + 1} and the ground is at {@code y - 1}.
 *
 * Implementations must answer false for anything they cannot see, so an unloaded chunk reads as solid.
 */
public interface Walkability
{
    /** True when a player can stand here: feet and head free, ground underfoot, and nothing to avoid. */
    boolean canStand(int x, int y, int z);

    /** True when a player's body can be in this block, ignoring whether there is ground to stand on. */
    boolean canPass(int x, int y, int z);

    /** True when the block must not be walked into at all, such as lava, fire, powder snow or a cobweb. */
    boolean hazard(int x, int y, int z);
}