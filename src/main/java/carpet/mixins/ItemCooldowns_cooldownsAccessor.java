package carpet.mixins;

import carpet.fakes.ItemCooldownsInterface;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ItemCooldowns.class)
public interface ItemCooldowns_cooldownsAccessor extends ItemCooldownsInterface
{
    @Accessor("cooldowns")
    @Override
    Map<Identifier, ?> carpet$getCooldowns();
}
