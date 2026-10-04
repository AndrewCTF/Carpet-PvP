package carpet.fakes;

import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * ItemCooldowns keeps the active cooldowns in a map and offers no way to list them, so clearing all of
 * them needs a look at the map itself.
 */
public interface ItemCooldownsInterface
{
    Map<Identifier, ?> carpet$getCooldowns();
}
