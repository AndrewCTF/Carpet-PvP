package carpet.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.world.entity.Entity.class)
public interface Entity_invulnerableTimeAccessor
{
    @Accessor("invulnerableTime")
    int carpet$getInvulnerableTime();

    @Accessor("invulnerableTime")
    void carpet$setInvulnerableTime(int ticks);
}
