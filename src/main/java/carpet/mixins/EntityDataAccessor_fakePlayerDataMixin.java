package carpet.mixins;

import carpet.CarpetSettings;
import carpet.patches.EntityPlayerMPFake;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.server.commands.data.EntityDataAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(EntityDataAccessor.class)
public class EntityDataAccessor_fakePlayerDataMixin
{
    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "setData", at = @At("HEAD"), cancellable = true)
    private void allowFakePlayerData(CompoundTag tag, CallbackInfo ci)
    {
        if (CarpetSettings.fakePlayerDataModifiable && this.entity instanceof EntityPlayerMPFake)
        {
            UUID uuid = this.entity.getUUID();
            try (ProblemReporter.ScopedCollector collector = new ProblemReporter.ScopedCollector(
                    this.entity.problemPath(),
                    org.slf4j.LoggerFactory.getLogger(EntityDataAccessor.class)))
            {
                this.entity.load(TagValueInput.create(collector, this.entity.registryAccess(), tag));
            }
            this.entity.setUUID(uuid);
            ci.cancel();
        }
    }
}
